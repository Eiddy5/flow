package org.flow.core.jdbc.postgres;

import java.io.IOException;
import java.sql.SQLException;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;

@Context
@Order(Ordered.HIGHEST_PRECEDENCE)
@Requires(property = "flow.test.initialize-schema", value = "true")
@Requires(property = "flow.repository.type", value = "postgres")
class PostgresTestSchema {
    PostgresTestSchema(PostgresSchema schema) throws IOException, SQLException {
        schema.initialize();
    }
}
