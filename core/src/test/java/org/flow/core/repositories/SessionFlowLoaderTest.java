package org.flow.core.repositories;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.flow.core.models.flows.FlowWithSource;
import org.flow.core.models.flows.GenericFlow;
import org.flow.core.services.FlowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.paas.session.Session;
import org.paas.session.SessionUser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证目录导入的 Session 租户边界和逐文件失败隔离。 */
class SessionFlowLoaderTest {
    @TempDir
    Path directory;

    private LocalFlowRepositoryLoader loader;
    private FlowRepositoryInterface repository;
    private FlowService service;
    private HostSession session;

    /** 宿主的具体用户类型。 */
    static class HostUser extends SessionUser {
    }

    /** 企业信息由宿主会话访问器决定。 */
    static class HostSession extends Session<HostUser> {
        /** 返回当前会话企业，忽略用户对象中的旧企业字段。 */
        @Override
        public String getCompanyId() {
            return companyId;
        }
    }

    /** 装配真实导入器，保留真实文件读取和 YAML 解析，仅替换仓储与流程服务。 */
    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        loader = new LocalFlowRepositoryLoader();
        repository = mock(FlowRepositoryInterface.class);
        service = mock(FlowService.class);
        inject("flowRepository", repository);
        inject("flowService", service);
        session = new HostSession();
        var user = new HostUser();
        user.setId("user-1");
        user.setCompanyId("wrong-user-company");
        session.setUser(user);
        session.setCompanyId("tenant-a");
    }

    /** 注入现有字段，不为技术测试增加生产构造器或共享测试模块。 */
    private void inject(String name, Object value) throws ReflectiveOperationException {
        var field = LocalFlowRepositoryLoader.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(loader, value);
    }

    /** 写入包含其他企业字段的有效 YAML，以检验导入器必须使用 Session 企业。 */
    private void writeFlow(String id) throws java.io.IOException {
        Files.writeString(directory.resolve(id + ".yaml"),
            "id: " + id + "\nnamespace: test\ntenantId: tenant-b\ntasks: []\n");
    }

    /** 构造服务返回的已保存流程。 */
    private FlowWithSource persisted(String id, int revision) {
        return FlowWithSource.builder().tenantId("tenant-a").namespace("test").id(id)
            .revision(revision).tasks(List.of()).build();
    }

    /** 某个目标查库失败只跳过该文件，其余文件仍分别创建或更新到会话租户。 */
    @Test
    void continuesOtherFilesAfterARepositoryReadFailure() throws Exception {
        writeFlow("broken");
        writeFlow("new-flow");
        writeFlow("existing-flow");
        var created = persisted("new-flow", 1);
        var previous = persisted("existing-flow", 1);
        var updated = persisted("existing-flow", 2);
        when(repository.findById("tenant-a", "test", "broken"))
            .thenThrow(new IllegalStateException("Cannot deserialize stored definition"));
        when(repository.findById("tenant-a", "test", "new-flow")).thenReturn(Optional.empty());
        when(repository.findById("tenant-a", "test", "existing-flow")).thenReturn(Optional.of(previous));
        when(service.create(same(session), any(GenericFlow.class))).thenReturn(created);
        when(service.update(same(session), any(GenericFlow.class))).thenReturn(updated);

        var imported = loader.load(session, directory.toFile());

        assertEquals(List.of("existing-flow", "new-flow"), imported.stream().map(FlowWithSource::getId).sorted().toList());
        assertTrue(imported.contains(created));
        assertTrue(imported.contains(updated));
        var create = ArgumentCaptor.forClass(GenericFlow.class);
        var update = ArgumentCaptor.forClass(GenericFlow.class);
        verify(service).create(same(session), create.capture());
        verify(service).update(same(session), update.capture());
        assertEquals("new-flow", create.getValue().getId());
        assertEquals("existing-flow", update.getValue().getId());
        assertEquals("tenant-a", create.getValue().getTenantId());
        assertEquals("tenant-a", update.getValue().getTenantId());
        verify(repository).findById("tenant-a", "test", "broken");
        verify(repository).findById("tenant-a", "test", "new-flow");
        verify(repository).findById("tenant-a", "test", "existing-flow");
        verifyNoMoreInteractions(repository, service);
    }

    /** 文件 URL 入口沿用相同的 Session 租户，不能恢复成 YAML 或用户旧企业。 */
    @Test
    void urlLoadingUsesTheSessionTenant() throws Exception {
        writeFlow("new-flow");
        var created = persisted("new-flow", 1);
        when(repository.findById("tenant-a", "test", "new-flow")).thenReturn(Optional.empty());
        when(service.create(same(session), any(GenericFlow.class))).thenReturn(created);

        assertEquals(List.of(created), loader.load(session, directory.toUri().toURL()));

        var capture = ArgumentCaptor.forClass(GenericFlow.class);
        verify(repository).findById("tenant-a", "test", "new-flow");
        verify(service).create(same(session), capture.capture());
        assertEquals("tenant-a", capture.getValue().getTenantId());
        verifyNoMoreInteractions(repository, service);
    }

    /** 两个入口均在查询、解析或保存之前拒绝不完整的宿主身份。 */
    @Test
    void invalidSessionsCannotReadOrImportFlows() throws Exception {
        writeFlow("new-flow");
        var file = directory.toFile();
        var url = directory.toUri().toURL();

        session.setCompanyId(" ");
        assertThrows(IllegalArgumentException.class, () -> loader.load(session, file));
        assertThrows(IllegalArgumentException.class, () -> loader.load(session, url));
        session.setCompanyId("tenant-a");
        session.getUser().setId(" ");
        assertThrows(IllegalArgumentException.class, () -> loader.load(session, file));
        assertThrows(IllegalArgumentException.class, () -> loader.load(session, url));
        assertThrows(NullPointerException.class, () -> loader.load(null, file));
        assertThrows(NullPointerException.class, () -> loader.load(null, url));
        verifyNoInteractions(repository, service);
    }
}
