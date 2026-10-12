package org.cses.flow.infrastructure.repositories.worker;

import org.cses.flow.core.repositories.worker.ExecutionMessage;
import org.cses.flow.core.repositories.worker.WorkerDelivery;
import org.cses.flow.core.utils.TimeUtil;
import org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter;
import org.cses.flow.infrastructure.queues.pulsar.PulsarTestEnvironment;
import org.cses.flow.worker.WorkerCommand;
import org.junit.jupiter.api.Test;
import org.paas.common.util.StringUtil;
import org.paas.json.JsonObject;

import static org.junit.jupiter.api.Assertions.*;

/** 原生 Engine 与真实 PG 的消息删除、发布标记及短事务原子性；不代表用户场景验收。 */
class WorkerRuntimeRepositoryIntegrationTest {
    /**
     * 重复创建不替换原正文，消费删除后迟到发布标记不重新插入记录。
     */
    @Test
    void consumedOutboxCannotBeResurrectedByLatePublicationMark() {
        PulsarTestEnvironment.initializeJson();
        String company = StringUtil.newId();
        try (var database = PostgresJooqTestAdapter.fromEnvironment()) {
            var repository = new WorkerRuntimeRepositoryImpl(database.engine());
            var original = ExecutionMessage.command(company, StringUtil.newId(), StringUtil.newId(), 1);
            try {
                database.run(dsl -> repository.add(dsl, original));
                var duplicate = ExecutionMessage.from(company, original.id(), original.executionId(), original.taskRunId(),
                    1, null, original.kind(), "{\"unexpected\":true}", original.createdAt(), null);
                database.run(dsl -> repository.add(dsl, duplicate));
                var retained = database.get(dsl -> repository.message(dsl, company, original.id())).orElseThrow();
                assertEquals(JsonObject.Parse(original.payload()).asObject(WorkerCommand.class),
                    JsonObject.Parse(retained.payload()).asObject(WorkerCommand.class));
                database.run(dsl -> repository.published(dsl, company, original.id(), TimeUtil.now()));
                assertNotNull(database.get(dsl -> repository.message(dsl, company, original.id())).orElseThrow().publishedAt());
                database.run(dsl -> repository.consumed(dsl, company, original.id()));
                database.run(dsl -> repository.published(dsl, company, original.id(), TimeUtil.now()));
                database.run(dsl -> repository.consumed(dsl, company, original.id()));
                assertTrue(database.get(dsl -> repository.message(dsl, company, original.id())).isEmpty());
            } finally {
                database.removeTenant(company);
            }
        }
    }

    /**
     * 在短事务中同时保存许可和消息后注入失败，两者回滚；提交后消费删除独立成功。
     */
    @Test
    void deliveryAndMessageRollbackTogether() {
        PulsarTestEnvironment.initializeJson();
        String company = StringUtil.newId();
        try (var database = PostgresJooqTestAdapter.fromEnvironment()) {
            var repository = new WorkerRuntimeRepositoryImpl(database.engine());
            var message = ExecutionMessage.command(company, StringUtil.newId(), StringUtil.newId(), 1);
            var delivery = WorkerDelivery.from(company, message.executionId(), message.taskRunId(), 1, 1,
                StringUtil.newId(), TimeUtil.now() + 10000, WorkerDelivery.Phase.RUNNING, null);
            try {
                assertThrows(IllegalStateException.class, () -> database.run(dsl -> repository.transaction(dsl, transaction -> {
                    repository.saveDelivery(transaction, delivery);
                    repository.add(transaction, message);
                    throw new IllegalStateException("注入提交前失败");
                })));
                assertTrue(database.get(dsl -> repository.delivery(dsl, company, message.executionId(), message.taskRunId(), false)).isEmpty());
                assertTrue(database.get(dsl -> repository.message(dsl, company, message.id())).isEmpty());
                database.run(dsl -> repository.transaction(dsl, transaction -> {
                    repository.saveDelivery(transaction, delivery);
                    repository.add(transaction, message);
                    return null;
                }));
                assertTrue(database.get(dsl -> repository.delivery(dsl, company, message.executionId(), message.taskRunId(), false)).isPresent());
                assertTrue(database.get(dsl -> repository.message(dsl, company, message.id())).isPresent());
                database.run(dsl -> repository.consumed(dsl, company, message.id()));
                assertTrue(database.get(dsl -> repository.message(dsl, company, message.id())).isEmpty());
            } finally {
                database.removeTenant(company);
            }
        }
    }
}
