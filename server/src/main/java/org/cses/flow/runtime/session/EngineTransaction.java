package org.cses.flow.runtime.session;

public interface EngineTransaction extends AutoCloseable {

    void commit();

    void rollback();

    @Override
    void close();
}
