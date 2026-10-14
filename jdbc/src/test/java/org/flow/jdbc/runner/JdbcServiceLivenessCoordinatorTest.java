package org.flow.jdbc.runner;

import org.junit.jupiter.api.*;

import org.flow.executor.AbstractServiceLivenessCoordinatorTest;
import org.flow.jdbc.JdbcTestUtils;

import jakarta.inject.Inject;

public abstract class JdbcServiceLivenessCoordinatorTest extends AbstractServiceLivenessCoordinatorTest {
    @Inject
    private JdbcTestUtils jdbcTestUtils;

    @BeforeAll
    void initSchema() {
        jdbcTestUtils.drop();
        jdbcTestUtils.migrate();
    }

    @Inject
    private AbstractJdbcWorkerJobRunningStateStore workerJobRunningStateStore;

    @AfterEach
    void tearDown() {
        workerJobRunningStateStore.findAll().forEach(it -> workerJobRunningStateStore.deleteByKey(it.uid()));
    }
}
