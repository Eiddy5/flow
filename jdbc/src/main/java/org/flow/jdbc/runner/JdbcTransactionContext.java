package org.flow.jdbc.runner;

import org.jooq.DSLContext;

import org.flow.core.runners.TransactionContext;

public class JdbcTransactionContext implements TransactionContext {

    private final DSLContext dslContext;

    public JdbcTransactionContext(DSLContext dslContext) {
        this.dslContext = dslContext;
    }

    public DSLContext getDslContext() {
        return dslContext;
    }

    @Override
    public <T extends TransactionContext> boolean supports(Class<T> clazz) {
        return JdbcTransactionContext.class.isAssignableFrom(clazz);
    }
}
