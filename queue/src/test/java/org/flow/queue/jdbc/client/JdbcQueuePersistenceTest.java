package org.flow.queue.jdbc.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.flow.core.jdbc.runner.JdbcTransactionContext;
import org.flow.core.serializers.JacksonMapper;
import org.flow.jdbc.JooqDSLContextWrapper;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.flow.gen.flow.Tables.QUEUES;
import static org.flow.gen.flow.Tables.SETTINGS;

/** 验证队列模块独立装配后的 PostgreSQL 消息持久化与领取事务边界。 */
@MicronautTest(transactional = false)
class JdbcQueuePersistenceTest {
    @Inject
    JdbcQueueClient client;

    @Inject
    JooqDSLContextWrapper transactions;

    private String queueName;
    private List<String> probeKeys;

    /**
     * 确认测试使用独立 schema，并为本用例分配互不影响的队列及业务探针键。
     * @param testInfo 当前测试信息；不能为 null，必须包含测试方法
     */
    @BeforeEach
    void prepare(TestInfo testInfo) {
        transactions.transaction(configuration -> {
            String schema = DSL.using(configuration).fetchOne("select current_schema()").get(0, String.class);
            assertThat(schema).startsWith("test_");
        });
        queueName = getClass().getName() + "." + testInfo.getTestMethod().orElseThrow().getName();
        probeKeys = List.of(queueName + ".first", queueName + ".second");
    }

    /** 只清理当前用例写入的消息和业务探针，保留其他测试的表和数据。 */
    @AfterEach
    void cleanUp() {
        if (queueName == null) {
            return;
        }
        transactions.transaction(configuration -> {
            var context = DSL.using(configuration);
            context.deleteFrom(QUEUES).where(QUEUES.TYPE.eq(queueName)).execute();
            context.deleteFrom(SETTINGS).where(SETTINGS.KEY.in(probeKeys)).execute();
        });
    }

    /** 发布后可通过生成表读取原始消息，消费确认只删除匹配路由的消息。 */
    @Test
    void publishesAndAcknowledgesOnlyTheSelectedRoute() throws Exception {
        String firstPayload = "{\"message\":\"unrouted\"}";
        String secondPayload = "{\"message\":\"routed\"}";
        client.publish(queueName, null, probeKeys.getFirst(), firstPayload);
        client.publish(queueName, "worker-a", probeKeys.getLast(), secondPayload);

        transactions.transaction(configuration -> {
            var rows = DSL.using(configuration)
                .select(QUEUES.KEY, QUEUES.ROUTING_KEY, QUEUES.VALUE, QUEUES.OFFSET)
                .from(QUEUES)
                .where(QUEUES.TYPE.eq(queueName))
                .orderBy(QUEUES.OFFSET)
                .fetch();
            assertThat(rows.getValues(QUEUES.KEY)).containsExactlyElementsOf(probeKeys);
            assertThat(rows.getFirst().get(QUEUES.ROUTING_KEY)).isNull();
            assertThat(rows.getLast().get(QUEUES.ROUTING_KEY)).isEqualTo("worker-a");
            assertThat(JacksonMapper.ofJson().readTree(rows.getFirst().get(QUEUES.VALUE).data()))
                .isEqualTo(JacksonMapper.ofJson().readTree(firstPayload));
            assertThat(JacksonMapper.ofJson().readTree(rows.getLast().get(QUEUES.VALUE).data()))
                .isEqualTo(JacksonMapper.ofJson().readTree(secondPayload));
        });

        List<String> received = new ArrayList<>();
        assertThat(client.subscribeDispatch(queueName, null,
            bytes -> received.add(new String(bytes, StandardCharsets.UTF_8))))
            .isEqualTo(1);
        assertThat(JacksonMapper.ofJson().readTree(received.getFirst()))
            .isEqualTo(JacksonMapper.ofJson().readTree(firstPayload));
        assertThat(client.queueLag(queueName, null)).isZero();
        assertThat(client.queueLag(queueName, "worker-a")).isEqualTo(1);

        assertThat(client.subscribeDispatch(queueName, List.of("worker-a"),
            bytes -> received.add(new String(bytes, StandardCharsets.UTF_8))))
            .isEqualTo(1);
        assertThat(received).hasSize(2);
        assertThat(JacksonMapper.ofJson().readTree(received.getLast()))
            .isEqualTo(JacksonMapper.ofJson().readTree(secondPayload));
        assertThat(remainingMessages()).isZero();
    }

    /** 领取回调失败时业务写入和消息确认一起回滚，重试成功后两者一起提交。 */
    @Test
    void rollsBackFailedClaimsAndCommitsSuccessfulClaimsWithBusinessWrites() throws Exception {
        client.publish(queueName, "worker-a", probeKeys.getFirst(), "{\"order\":1}");
        client.publish(queueName, "worker-a", probeKeys.getLast(), "{\"order\":2}");

        List<String> attempted = new ArrayList<>();
        assertThatThrownBy(() -> client.claimDispatch(queueName, "worker-a", 2, (transaction, bytes) -> {
            String key = probeKeys.get(attempted.size());
            attempted.add(key);
            transaction.unwrap(JdbcTransactionContext.class).getDslContext()
                .insertInto(SETTINGS)
                .set(SETTINGS.KEY, key)
                .set(SETTINGS.VALUE, JSONB.valueOf(new String(bytes, StandardCharsets.UTF_8)))
                .execute();
            if (attempted.size() == 2) {
                throw new IllegalStateException("模拟第二条消息登记失败");
            }
            return key;
        })).isInstanceOf(IllegalStateException.class).hasMessage("模拟第二条消息登记失败");

        assertThat(attempted).containsExactlyElementsOf(probeKeys);
        assertThat(remainingMessages()).isEqualTo(2);
        assertThat(persistedProbeCount()).isZero();

        List<String> prepared = new ArrayList<>();
        List<String> accepted = client.claimDispatch(queueName, "worker-a", 2, (transaction, bytes) -> {
            String key = probeKeys.get(prepared.size());
            prepared.add(key);
            transaction.unwrap(JdbcTransactionContext.class).getDslContext()
                .insertInto(SETTINGS)
                .set(SETTINGS.KEY, key)
                .set(SETTINGS.VALUE, JSONB.valueOf(new String(bytes, StandardCharsets.UTF_8)))
                .execute();
            return key;
        });

        assertThat(accepted).containsExactlyElementsOf(probeKeys);
        assertThat(remainingMessages()).isZero();
        assertThat(persistedProbeCount()).isEqualTo(2);
        transactions.transaction(configuration -> {
            var context = DSL.using(configuration);
            for (int index = 0; index < probeKeys.size(); index++) {
                JSONB value = context.select(SETTINGS.VALUE).from(SETTINGS)
                    .where(SETTINGS.KEY.eq(probeKeys.get(index)))
                    .fetchSingle(SETTINGS.VALUE);
                assertThat(JacksonMapper.ofJson().readTree(value.data()).get("order").intValue())
                    .isEqualTo(index + 1);
            }
        });
    }

    /** 身份信封通过真实 PostgreSQL 保存、领取后仍保留租户、操作者和原生命令。 */
    @Test
    void persistsAndRestoresSessionCommandIdentity() throws Exception {
        var session = org.mockito.Mockito.mock(org.paas.session.Session.class);
        var user = org.mockito.Mockito.mock(org.paas.session.SessionUser.class);
        org.mockito.Mockito.when(session.getCompanyId()).thenReturn("tenant-session");
        org.mockito.Mockito.when(session.getUser()).thenReturn(user);
        org.mockito.Mockito.when(user.getId()).thenReturn("actor-session");
        var create = org.flow.core.executor.command.Create.of(
            org.flow.core.models.flows.FlowId.of("tenant-session", "test", "flow-session", 1));
        var command = org.flow.core.executor.command.SessionCommand.from(session, create);
        client.publish(queueName, null, command.key(), JacksonMapper.ofJson().writeValueAsString(command));
        List<org.flow.core.executor.command.ExecutionCommand> received = new ArrayList<>();
        client.subscribeDispatch(queueName, null, bytes -> {
            try {
                received.add(JacksonMapper.ofJson().readValue(bytes, org.flow.core.executor.command.ExecutionCommand.class));
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
        assertThat(received).hasSize(1);
        var restored = (org.flow.core.executor.command.SessionCommand) received.getFirst();
        assertThat(restored.actorId()).isEqualTo("actor-session");
        assertThat(restored.tenantId()).isEqualTo("tenant-session");
        assertThat(restored.executionId()).isEqualTo(create.executionId());
        assertThat(restored.command()).isInstanceOf(org.flow.core.executor.command.Create.class);
        assertThat(remainingMessages()).isZero();
    }

    /**
     * 从提交后的新事务读取当前用例尚未确认的消息数量。
     * @return 当前队列的已提交消息数
     */
    private int remainingMessages() {
        return transactions.transactionResult(configuration -> DSL.using(configuration)
            .fetchCount(QUEUES, QUEUES.TYPE.eq(queueName)));
    }

    /**
     * 从提交后的新事务读取当前用例已持久化的业务探针数量。
     * @return 当前测试键对应的已提交业务探针数
     */
    private int persistedProbeCount() {
        return transactions.transactionResult(configuration -> DSL.using(configuration)
            .fetchCount(SETTINGS, SETTINGS.KEY.in(probeKeys)));
    }
}
