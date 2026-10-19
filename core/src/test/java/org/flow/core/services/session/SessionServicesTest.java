package org.flow.core.services.session;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.flow.core.async.AsyncOperationsConfiguration;
import org.flow.core.executor.command.Create;
import org.flow.core.executor.command.ExecutionCommand;
import org.flow.core.executor.command.SessionCommand;
import org.flow.core.models.flows.Flow;
import org.flow.core.models.flows.FlowId;
import org.flow.core.plugins.PluginRegistry;
import org.flow.core.queues.BroadcastQueueInterface;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.repositories.ExecutionRepositoryInterface;
import org.flow.core.repositories.LogDataStoreInterface;
import org.flow.core.repositories.MetricRepositoryInterface;
import org.flow.core.runners.FlowInputOutput;
import org.flow.core.serializers.JacksonMapper;
import org.flow.core.services.AsyncOperationWaiter;
import org.flow.core.services.ConcurrencyLimitService;
import org.flow.core.services.ExecutionOutputService;
import org.flow.core.services.ExecutionService;
import org.flow.core.services.FlowParsingService;
import org.flow.core.services.FlowService;
import org.flow.core.services.PluginService;
import org.flow.core.services.TaskOutputService;
import org.flow.core.storages.StorageInterface;
import org.flow.core.utils.SessionUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.paas.session.Session;
import org.paas.session.SessionFactory;
import org.paas.session.SessionUser;

import io.micronaut.context.event.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 覆盖外部 Session 泛型、租户隔离、操作者传递与队列序列化边界。 */
class SessionServicesTest {
    /** 模拟企业信息只保存在 Session 的宿主类型。 */
    static class HostSession extends Session<HostUser> {
        /** 返回会话企业，验证调用方不会读取 user.companyId。 */
        @Override
        public String getCompanyId() { return companyId; }
    }

    /** 模拟新版 AuthX 用户在绑定后移除旧企业字段。 */
    static class HostUser extends SessionUser {
        /** 清空用户级企业字段，租户必须从 Session 读取。 */
        @Override
        public void onSessionBound() { setCompanyId(null); }
    }

    /** 测试宿主的真实泛型工厂，只有该装配用例启用。 */
    @jakarta.inject.Singleton
    @io.micronaut.context.annotation.Requires(property = "spec.name", value = "SessionServicesTest")
    static class HostFactory implements SessionFactory<HostSession, HostUser> {
        private List<String> requested;
        /** 每次创建独立宿主会话。 */
        @Override
        public HostSession session() { return new HostSession(); }
        /** 每次创建独立宿主用户。 */
        @Override
        public HostUser user() { return new HostUser(); }
        /** 声明宿主具体会话类型。 */
        @Override
        public Class<HostSession> getSessionClass() { return HostSession.class; }
        /** 声明宿主具体用户类型。 */
        @Override
        public Class<HostUser> getUserClass() { return HostUser.class; }
        /** 记录用户查询，缺少资料时由 SessionUtil 创建最小用户。 */
        @Override
        public List<HostUser> buildSessionUser(List<String> ids) {
            requested = List.copyOf(ids);
            return List.of();
        }
    }

    /** 构造可携带认证凭证的宿主会话，凭证不得进入命令。 */
    private HostSession session(String tenant) {
        var session = new HostSession();
        var user = new HostUser();
        user.setId("user-1");
        user.setName("测试用户");
        user.setCompanyId("wrong-user-company");
        session.setUser(user);
        session.setCompanyId(tenant);
        session.setId("private-session-token");
        return session;
    }

    /** 使用原生执行服务的生产构造器，仅替换存储与基础设施协作者。 */
    @SuppressWarnings("unchecked")
    private ExecutionService executionService(
        FlowService flows, ExecutionRepositoryInterface repository, DispatchQueueInterface<ExecutionCommand> queue) {
        return new ExecutionService(
            flows, mock(StorageInterface.class), repository, mock(LogDataStoreInterface.class),
            mock(MetricRepositoryInterface.class), mock(FlowInputOutput.class), mock(ApplicationEventPublisher.class),
            mock(ConcurrencyLimitService.class), mock(FlowParsingService.class), mock(TaskOutputService.class),
            mock(ExecutionOutputService.class), queue, mock(BroadcastQueueInterface.class),
            mock(DispatchQueueInterface.class), mock(AsyncOperationWaiter.class),
            mock(AsyncOperationsConfiguration.class), Optional.empty()
        );
    }

    /** 读取 Session 的企业和用户；拒绝无企业、无用户、无操作者的会话。 */
    @Test
    void validatesIdentityThroughSessionAccessors() {
        var session = session(" tenant-a ");
        assertEquals("tenant-a", SessionUtil.company(session));
        assertEquals("user-1", SessionUtil.user(session).id());
        assertThrows(NullPointerException.class, () -> SessionUtil.user(null));
        session.setCompanyId(" ");
        assertThrows(IllegalArgumentException.class, () -> SessionUtil.user(session));
        session.setCompanyId("tenant-a");
        session.getUser().setId(" ");
        assertThrows(IllegalArgumentException.class, () -> SessionUtil.user(session));
        session.setUser(null);
        assertThrows(NullPointerException.class, () -> SessionUtil.user(session));
    }

    /** 保留宿主具体类型并执行 onSessionBound；不能从工厂拿到无关用户后改写其身份。 */
    @Test
    @SuppressWarnings("unchecked")
    void restoresUsingTheHostFactoryAndBindingCallback() {
        SessionFactory<HostSession, HostUser> factory = mock(SessionFactory.class);
        var unrelated = new HostUser();
        unrelated.setId("other-user");
        when(factory.session()).thenReturn(new HostSession());
        when(factory.user()).thenAnswer(ignored -> new HostUser());
        when(factory.buildSessionUser(List.of("user-1"))).thenReturn(List.of(unrelated));
        HostSession restored = SessionUtil.restore(factory, "tenant-a", "user-1");
        assertEquals("tenant-a", restored.getCompanyId());
        assertEquals("user-1", restored.getUserId());
        assertNull(restored.getUser().getCompanyId());
        assertEquals("other-user", unrelated.getId());
    }

    /** 在真实 Micronaut 容器中解析宿主工厂，验证泛型 BeanProvider 能恢复具体会话。 */
    @Test
    void resolvesTheHostFactoryThroughMicronaut() {
        var command = SessionCommand.from(session("tenant-a"), Create.of(FlowId.of("tenant-a", "test", "hello", 1)));
        try (var context = io.micronaut.context.ApplicationContext.builder()
            .deduceEnvironment(false).environmentPropertySource(false).enableDefaultPropertySources(false)
            .properties(Map.of("spec.name", "SessionServicesTest"))
            .packages("org.flow.core.services.executions", "org.flow.core.services.session").start()) {
            var actor = context.getBean(org.flow.core.services.executions.SessionCommandContext.class).restore(command);
            assertEquals("user-1", actor.id());
            assertEquals(List.of("user-1"), context.getBean(HostFactory.class).requested);
        }
    }

    /** 插件所有查询统一拒绝非法会话；合法会话读取同一注册表。 */
    @Test
    void pluginQueriesRequireACompleteSession() {
        var registry = mock(PluginRegistry.class);
        var service = new PluginService(registry);
        when(registry.plugins()).thenReturn(List.of());
        when(registry.findMetadataByIdentifier("missing")).thenReturn(Optional.empty());
        when(registry.getAllVersionsForType("missing")).thenReturn(List.of());
        assertThrows(NullPointerException.class, () -> service.plugins(null));
        assertThrows(NullPointerException.class, () -> service.plugin(null, "missing"));
        assertThrows(NullPointerException.class, () -> service.versions(null, "missing"));
        verifyNoInteractions(registry);
        assertTrue(service.plugins(session("tenant-a")).isEmpty());
        assertTrue(service.plugin(session("tenant-a"), "missing").isEmpty());
        assertTrue(service.versions(session("tenant-b"), "missing").isEmpty());
        verify(registry).plugins();
    }

    /** 对不同租户使用独立仓储条件；越权目标查不到时不发布控制命令。 */
    @Test
    @SuppressWarnings("unchecked")
    void executionQueriesAndControlsAreTenantScoped() throws Exception {
        var repository = mock(ExecutionRepositoryInterface.class);
        DispatchQueueInterface<ExecutionCommand> queue = mock(DispatchQueueInterface.class);
        var service = executionService(mock(FlowService.class), repository, queue);
        when(repository.findById("tenant-a", "execution-1")).thenReturn(Optional.empty());
        assertTrue(service.findById(session("tenant-a"), "execution-1").isEmpty());
        assertThrows(java.util.NoSuchElementException.class, () -> service.pause(session("tenant-a"), "execution-1"));
        verifyNoInteractions(queue);
        verify(repository, times(2)).findById("tenant-a", "execution-1");
    }

    /** 缺少操作者时，原生服务的查询、创建与控制均不能读取仓储或发布命令。 */
    @Test
    @SuppressWarnings("unchecked")
    void invalidExecutionSessionsCannotReachTheRepositoryOrQueue() {
        var flows = mock(FlowService.class);
        var repository = mock(ExecutionRepositoryInterface.class);
        DispatchQueueInterface<ExecutionCommand> queue = mock(DispatchQueueInterface.class);
        var service = executionService(flows, repository, queue);
        var invalid = session("tenant-a");
        invalid.getUser().setId(" ");

        assertThrows(IllegalArgumentException.class, () -> service.findById(invalid, "execution-1"));
        assertThrows(IllegalArgumentException.class, () -> service.getExecution(invalid, "execution-1"));
        assertThrows(IllegalArgumentException.class, () -> service.getExecutionIfPause(invalid, "execution-1"));
        assertThrows(IllegalArgumentException.class, () -> service.create(invalid, "test", "hello", null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> service.pause(invalid, "execution-1"));
        assertThrows(IllegalArgumentException.class, () -> service.resume(invalid, "execution-1", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> service.restart(invalid, "execution-1", null));
        assertThrows(IllegalArgumentException.class, () -> service.replay(invalid, "execution-1", "task-1", null));
        verifyNoInteractions(flows, repository, queue);
    }

    /** 创建实例使用会话租户和经过原生校验的版本，持久化最小身份快照。 */
    @Test
    @SuppressWarnings("unchecked")
    void createsAnIdentityEnvelopeWithoutSessionCredentials() throws Exception {
        var flows = mock(FlowService.class);
        var repository = mock(ExecutionRepositoryInterface.class);
        DispatchQueueInterface<ExecutionCommand> queue = mock(DispatchQueueInterface.class);
        var flow = Flow.builder().tenantId("tenant-a").namespace("test").id("hello").revision(3).build();
        var session = session("tenant-a");
        when(flows.getFlowIfExecutableOrThrow(session, "test", "hello", Optional.empty())).thenReturn(flow);
        when(flows.validateForExecution(flow)).thenReturn(Optional.empty());
        var service = executionService(flows, repository, queue);
        var created = service.create(session, "test", "hello", null, Map.of("text", "hi"));
        var capture = ArgumentCaptor.forClass(ExecutionCommand.class);
        verify(queue).emit(capture.capture());
        var envelope = assertInstanceOf(SessionCommand.class, capture.getValue());
        assertEquals(created.executionId(), envelope.executionId());
        assertEquals("user-1", envelope.actorId());
        assertEquals(3, created.flowRevision());
        var json = JacksonMapper.ofJson().writeValueAsString(envelope);
        assertFalse(json.contains("private-session-token"));
        assertFalse(json.contains("wrong-user-company"));
        var restored = assertInstanceOf(SessionCommand.class, JacksonMapper.ofJson().readValue(json, ExecutionCommand.class));
        assertEquals("user-1", restored.actorId());
        assertEquals("tenant-a", restored.tenantId());
        assertEquals(created.operationId(), restored.operationId());
        assertEquals("hi", assertInstanceOf(Create.class, restored.command()).inputs().get("text"));
    }

    /** 命令租户与会话不一致时拒绝组装消息。 */
    @Test
    void rejectsCrossTenantCommands() {
        var create = Create.of(FlowId.of("tenant-b", "test", "hello", 1));
        assertThrows(IllegalArgumentException.class, () -> SessionCommand.from(session("tenant-a"), create));
    }
}
