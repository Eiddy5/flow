package org.flow.core.services.session;

import java.util.Map;
import java.util.NoSuchElementException;

import org.flow.core.executor.command.Create;
import org.flow.core.executor.command.ExecutionCommand;
import org.flow.core.executor.command.SessionCommand;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.serializers.JacksonMapper;
import org.flow.core.services.ExecutionService;
import org.flow.core.services.FlowService;
import org.flow.core.services.PluginService;
import org.flow.core.storages.StorageInterface;
import org.flow.jdbc.JooqDSLContextWrapper;
import org.flow.plugin.core.log.Log;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.paas.session.Session;
import org.paas.session.SessionUser;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.flow.gen.flow.Tables.QUEUES;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 验证原生服务在同一真实容器中的装配、租户存储和命令持久化，不启动执行引擎。 */
@MicronautTest(transactional = false)
@Property(name = "spec.name", value = "SessionServiceWiringTest")
@EnabledIfEnvironmentVariable(named = "FLOW_UPSTREAM_TEST_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/flow_unit")
class SessionServiceWiringTest {
    @Inject
    ApplicationContext context;

    @Inject
    FlowService flows;

    @Inject
    ExecutionService executions;

    @Inject
    PluginService plugins;

    @Inject
    DispatchQueueInterface<ExecutionCommand> commands;

    @Inject
    JooqDSLContextWrapper transactions;

    @Inject
    StorageInterface storage;

    /** 宿主会话的企业信息直接由 Session 保存，不从用户对象读取。 */
    static class HostSession extends Session<HostUser> {
        /**
         * 返回会话企业，不受用户对象中企业信息的影响。
         * @return 会话保存的企业标识，未设置时为 null
         */
        @Override
        public String getCompanyId() {
            return companyId;
        }
    }

    /** 为本测试提供满足新版 Session 泛型边界的用户类型。 */
    static class HostUser extends SessionUser {
    }

    /** 只在本测试中提供不实现文件读写行为的宿主存储替身。 */
    @Factory
    @Requires(property = "spec.name", value = "SessionServiceWiringTest")
    static class HostStorageFactory {
        /**
         * 提供用于验证服务装配的存储替身，不创建目录或读写文件。
         * @return 本容器唯一的存储替身，不与其他测试容器共享
         */
        @Singleton
        StorageInterface storage() {
            return mock(StorageInterface.class);
        }
    }

    /**
     * 创建带有会话企业和操作者的宿主会话，用户级企业故意设置为其他值。
     * @param tenant 待写入会话的企业标识，不能为 null 或空白
     * @return 新建的会话和用户，每次调用均不共享可变对象
     */
    private HostSession session(String tenant) {
        var session = new HostSession();
        var user = new HostUser();
        user.setId("wiring-actor");
        user.setName("服务装配测试用户");
        user.setCompanyId("different-user-tenant");
        session.setUser(user);
        session.setCompanyId(tenant);
        session.setId("wiring-private-session-token");
        return session;
    }

    /**
     * 装配三个唯一原生服务，直接保存并查询流程，再确认带会话身份的创建命令已写入隔离数据库。
     * 不启动执行器，不访问文件存储；执行结束时清理本用例创建的流程和命令。
     * @throws Exception 流程解析、命令持久化或消息反序列化失败时使测试失败
     */
    @Test
    void persistsFlowsAndSessionCommandsThroughTheNativeServices() throws Exception {
        String schema = transactions.transactionResult(configuration ->
            DSL.using(configuration).select(DSL.currentSchema()).fetchOne(0, String.class));
        assertThat(schema).startsWith("test_");
        assertThat(context.getBeansOfType(FlowService.class)).containsExactly(flows);
        assertThat(context.getBeansOfType(ExecutionService.class)).containsExactly(executions);
        assertThat(context.getBeansOfType(PluginService.class)).containsExactly(plugins);

        var owner = session("wiring-tenant");
        var otherTenant = session("wiring-other-tenant");
        String namespace = "wiring.session";
        String flowId = "direct-session-services";
        String source = """
            id: direct-session-services
            namespace: wiring.session
            tenantId: yaml-cannot-override-session
            inputs:
              - id: greeting
                type: STRING
            tasks:
              - id: log
                type: org.flow.plugin.core.log.Log
                message: "{{ inputs.greeting }}"
            """;
        var saved = flows.create(owner, source);
        Create submitted = null;
        try {
            assertThat(saved.getTenantId()).isEqualTo("wiring-tenant");
            assertThat(flows.findById(owner, namespace, flowId).orElseThrow().getRevision())
                .isEqualTo(saved.getRevision());
            assertThat(flows.findById(otherTenant, namespace, flowId)).isEmpty();
            assertThat(plugins.plugins(owner)).anyMatch(plugin -> plugin.hasClass(Log.class.getName()));
            assertThat(plugins.plugin(owner, Log.class.getName())).isPresent();
            assertThatThrownBy(() -> executions.create(otherTenant, namespace, flowId, null, Map.of("greeting", "hello")))
                .isInstanceOf(NoSuchElementException.class);

            submitted = executions.create(owner, namespace, flowId, null, Map.of("greeting", "hello"));
            String commandKey = submitted.key();
            String payload = transactions.transactionResult(configuration -> DSL.using(configuration)
                .select(QUEUES.VALUE)
                .from(QUEUES)
                .where(QUEUES.TYPE.eq(commands.queueName()).and(QUEUES.KEY.eq(commandKey)))
                .fetchSingle(QUEUES.VALUE).data());
            var restored = JacksonMapper.ofJson().readValue(payload, ExecutionCommand.class);
            assertThat(restored).isInstanceOf(SessionCommand.class);
            var envelope = (SessionCommand) restored;
            assertThat(envelope.tenantId()).isEqualTo(owner.getCompanyId());
            assertThat(envelope.actorId()).isEqualTo(owner.getUser().getId());
            assertThat(envelope.executionId()).isEqualTo(submitted.executionId());
            assertThat(envelope.operationId()).isEqualTo(submitted.operationId()).isNotBlank();
            assertThat(envelope.command()).isInstanceOf(Create.class);
            assertThat(((Create) envelope.command()).inputs()).containsEntry("greeting", "hello");
            assertThat(payload).doesNotContain(owner.getId(), "different-user-tenant", "yaml-cannot-override-session");
            assertThat(executions.findById(owner, submitted.executionId())).isEmpty();
            verifyNoInteractions(storage);
        } finally {
            if (submitted != null) {
                String commandKey = submitted.key();
                transactions.transaction(configuration -> DSL.using(configuration)
                    .deleteFrom(QUEUES)
                    .where(QUEUES.TYPE.eq(commands.queueName()).and(QUEUES.KEY.eq(commandKey)))
                    .execute());
            }
            flows.delete(owner, namespace, flowId);
        }
    }
}
