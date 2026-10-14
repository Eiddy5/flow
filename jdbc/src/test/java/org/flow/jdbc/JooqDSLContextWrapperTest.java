package org.flow.jdbc;

import javax.sql.DataSource;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class JooqDSLContextWrapperTest {
    @Test
    void rejectsUnsupportedDialectBeforeAcquiringAConnection() {
        DataSource source = mock(DataSource.class);
        assertThatThrownBy(() -> new JooqDSLContextWrapper(DSL.using(SQLDialect.DEFAULT), source))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Only PostgreSQL");
        verifyNoInteractions(source);
    }

    @Test
    void acceptsPostgresWithoutOpeningAConnection() {
        DataSource source = mock(DataSource.class);
        assertThatCode(() -> new JooqDSLContextWrapper(DSL.using(SQLDialect.POSTGRES), source))
            .doesNotThrowAnyException();
        verifyNoInteractions(source);
    }
}
