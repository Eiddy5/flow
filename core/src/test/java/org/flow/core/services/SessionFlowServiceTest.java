package org.flow.core.services;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.flow.core.models.flows.FlowInterface;
import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.models.flows.GenericFlow;
import org.flow.core.models.validations.ModelValidator;
import org.flow.core.queues.BroadcastQueueInterface;
import org.flow.core.repositories.FlowRepositoryInterface;
import org.flow.core.runners.FlowMetaStoreInterface;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.paas.session.Session;
import org.paas.session.SessionUser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证原生流程服务直接处理 Session、持久化及运行通知，不依赖第二个流程服务。 */
class SessionFlowServiceTest {
    private static final String SOURCE = "id: hello\nnamespace: test\ntenantId: tenant-b\ntasks: []\n";
    private FlowService service;
    private FlowRepositoryInterface repository;
    private FlowParsingService parser;
    private ModelValidator validator;
    private BroadcastQueueInterface<FlowInterface> queue;
    private HostSession session;

    /** 宿主自行实现具体用户类型。 */
    static class HostUser extends SessionUser {
    }

    /** 企业从宿主 Session 访问器提供，不依赖用户旧企业字段。 */
    static class HostSession extends Session<HostUser> {
        /** 返回会话当前企业，允许与用户对象中的旧字段不同。 */
        @Override
        public String getCompanyId() {
            return companyId;
        }
    }

    /** 构造真实服务，仅为外部协作者注入测试替身；不启用数据库和后台线程。 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        repository = mock(FlowRepositoryInterface.class);
        parser = mock(FlowParsingService.class);
        validator = mock(ModelValidator.class);
        queue = mock(BroadcastQueueInterface.class);
        service = new FlowService();
        inject("flowRepository", repository);
        inject("flowParsingService", parser);
        inject("modelValidator", validator);
        inject("flowQueue", queue);
        inject("flowMetaStore", mock(FlowMetaStoreInterface.class));
        session = new HostSession();
        var user = new HostUser();
        user.setId("user-1");
        user.setCompanyId("wrong-user-company");
        session.setUser(user);
        session.setCompanyId("tenant-a");
    }

    /** 向现有字段注入协作者，避免为单元测试增加生产构造器或存储兜底。 */
    private void inject(String name, Object value) throws ReflectiveOperationException {
        var field = FlowService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    /** 创建同一流程的持久化版本，保留源代码中的外部租户以验证 Session 边界。 */
    private FlowWithSource storedFlow(int revision) {
        return FlowWithSource.builder().tenantId("tenant-a").namespace("test").id("hello")
            .revision(revision).tasks(List.of()).source(SOURCE).build();
    }

    /** 原生校验与持久化都使用 Session 租户，保存后仍发布原生流程通知。 */
    @Test
    void createsAndNotifiesUsingTheSessionTenant() throws Exception {
        var stored = storedFlow(1);
        when(parser.parse("tenant-a", SOURCE, true)).thenReturn(stored);
        when(parser.parseForValidation(stored)).thenReturn(stored);
        when(repository.create(any(GenericFlow.class))).thenReturn(stored);

        assertSame(stored, service.create(session, SOURCE));

        var capture = ArgumentCaptor.forClass(GenericFlow.class);
        verify(repository).create(capture.capture());
        assertEquals("tenant-a", capture.getValue().getTenantId());
        assertEquals(SOURCE, capture.getValue().getSource());
        verify(parser).parse("tenant-a", SOURCE, true);
        verify(validator).validate(stored);
        verify(queue).emit(stored);
    }

    /** 对象入口拒绝不属于会话企业的定义，不能先校验、查询或写入其他租户。 */
    @Test
    void rejectsForeignTenantObjectsBeforeAnyRepositoryAccess() throws Exception {
        var foreign = GenericFlow.fromYaml("tenant-b", SOURCE);
        assertThrows(IllegalArgumentException.class, () -> service.create(session, foreign));
        assertThrows(IllegalArgumentException.class, () -> service.update(session, foreign));
        assertEquals("tenant-b", foreign.getTenantId());
        verifyNoInteractions(repository, parser, validator, queue);
    }

    /** 更新自行读取本企业旧版本，不接受调用方指定的其他租户旧版本。 */
    @Test
    void updatesTheVersionLoadedFromTheSessionTenant() throws Exception {
        var previous = storedFlow(7);
        var updated = storedFlow(8);
        when(repository.findById("tenant-a", "test", "hello", Optional.empty())).thenReturn(Optional.of(previous));
        when(parser.parse("tenant-a", SOURCE, true)).thenReturn(updated);
        when(parser.parseForValidation(updated)).thenReturn(updated);
        when(repository.update(any(GenericFlow.class), same(previous))).thenReturn(updated);

        assertSame(updated, service.update(session, SOURCE));

        var capture = ArgumentCaptor.forClass(GenericFlow.class);
        verify(repository).update(capture.capture(), same(previous));
        assertEquals("tenant-a", capture.getValue().getTenantId());
        verify(repository).findById("tenant-a", "test", "hello", Optional.empty());
        verify(validator).validate(updated);
        verify(queue).emit(updated);
    }

    /** 删除前按会话企业定位最新定义，删除版本继续发送到运行队列。 */
    @Test
    void deletesTheFlowLoadedFromTheSessionTenant() throws Exception {
        var previous = storedFlow(7);
        var deleted = previous.toDeleted();
        when(repository.findByIdWithSource("tenant-a", "test", "hello", Optional.empty())).thenReturn(Optional.of(previous));
        when(repository.delete(previous)).thenReturn(deleted);

        assertSame(deleted, service.delete(session, "test", "hello"));

        verify(repository).findByIdWithSource("tenant-a", "test", "hello", Optional.empty());
        verify(repository).delete(same(previous));
        verify(queue).emit(deleted);
    }

    /** 当前租户没有目标时，更新和删除必须停止，不能进入写入或通知链路。 */
    @Test
    void missingTenantTargetsCannotBeUpdatedOrDeleted() {
        when(repository.findById("tenant-a", "test", "hello", Optional.empty())).thenReturn(Optional.empty());
        when(repository.findByIdWithSource("tenant-a", "test", "hello", Optional.empty())).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.update(session, SOURCE));
        assertThrows(NoSuchElementException.class, () -> service.delete(session, "test", "hello"));

        verify(repository, never()).update(any(), any());
        verify(repository, never()).delete(any());
        verifyNoInteractions(parser, validator, queue);
    }

    /** 所有对外流程入口在读取定义或保存之前要求有效操作者。 */
    @Test
    void invalidSessionsCannotReachPersistenceOrNotifications() {
        session.getUser().setId(" ");

        assertThrows(IllegalArgumentException.class, () -> service.create(session, SOURCE));
        assertThrows(IllegalArgumentException.class, () -> service.update(session, SOURCE));
        assertThrows(IllegalArgumentException.class, () -> service.delete(session, "test", "hello"));
        assertThrows(IllegalArgumentException.class, () -> service.findById(session, "test", "hello", null));
        assertThrows(IllegalArgumentException.class, () -> service.findAll(session));
        assertThrows(IllegalArgumentException.class, () -> service.findByNamespace(session, "test"));
        assertThrows(IllegalArgumentException.class, () -> service.findByNamespaceWithSource(session, "test"));
        assertThrows(IllegalArgumentException.class, () -> service.findByNamespacePrefix(session, "test"));
        assertThrows(IllegalArgumentException.class, () -> service.getFlowIfExecutableOrThrow(session, "test", "hello", Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> service.validate(session, List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.importFlow(session, SOURCE));
        verifyNoInteractions(repository, parser, validator, queue);
    }

    /** 查询与可执行版本解析均从 Session 读取租户，不读取用户旧 companyId。 */
    @Test
    void queriesAndExecutableLookupUseTheSessionTenant() {
        var stored = storedFlow(3);
        when(repository.findByIdForExecutionWithoutAcl("tenant-a", "test", "hello")).thenReturn(Optional.of(stored));

        service.findById(session, "test", "hello", 3);
        service.findAll(session);
        service.findByNamespace(session, "test");
        service.findByNamespaceWithSource(session, "test");
        service.findByNamespacePrefix(session, "test");
        assertSame(stored, service.getFlowIfExecutableOrThrow(session, "test", "hello", Optional.empty()));

        verify(repository).findById("tenant-a", "test", "hello", Optional.of(3));
        verify(repository).findAll("tenant-a");
        verify(repository).findByNamespace("tenant-a", "test");
        verify(repository).findByNamespaceWithSource("tenant-a", "test");
        verify(repository).findByNamespacePrefix("tenant-a", "test");
        verify(repository).findByIdForExecutionWithoutAcl("tenant-a", "test", "hello");
    }
}
