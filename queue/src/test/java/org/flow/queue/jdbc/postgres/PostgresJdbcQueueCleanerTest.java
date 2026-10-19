package org.flow.queue.jdbc.postgres;

import org.flow.queue.jdbc.queue.AbstractJdbcQueueCleanerTest;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;

@MicronautTest(rebuildContext = true)
@Property(name = "flow.jdbc.queue.cleaner.retention", value = "PT0S")
public class PostgresJdbcQueueCleanerTest extends AbstractJdbcQueueCleanerTest {
}
