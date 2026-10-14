package org.flow.queue.postgres;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import org.flow.core.junit.annotations.FlowTest;
import org.flow.jdbc.JdbcTestUtils;
import org.flow.queue.AbstractBroadcastQueueTest;

import jakarta.inject.Inject;

@FlowTest(environments = { "test", "queue" })
@Execution(ExecutionMode.SAME_THREAD)
class PostgresBroadcastQueueTest extends AbstractBroadcastQueueTest {
    @Inject
    JdbcTestUtils jdbcTestUtils;

    @BeforeEach
    protected void init() {
        jdbcTestUtils.drop();
        jdbcTestUtils.migrate();
    }
}