package org.cses.flow.runtime.context;

import java.util.Objects;
import org.cses.flow.definition.session.DefinitionSession;
import org.cses.flow.runtime.session.EngineSessionFactory;
import org.cses.flow.runtime.session.EngineTransaction;
import org.cses.flow.runtime.session.RuntimeSession;
import org.cses.flow.runtime.engine.EngineConfiguration;

public class CommandContextFactory {

    private final EngineSessionFactory sessionFactory;
    private final EngineConfiguration configuration;

    public CommandContextFactory(
            EngineSessionFactory sessionFactory,
            EngineConfiguration configuration) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    public CommandContext open() {
        EngineTransaction transaction = sessionFactory.openTransaction();
        try {
            DefinitionSession definitionSession = sessionFactory.openDefinitionSession(transaction);
            RuntimeSession runtimeSession = sessionFactory.openRuntimeSession(transaction);
            return new CommandContext(transaction, definitionSession, runtimeSession, configuration);
        } catch (Throwable failure) {
            transaction.rollback();
            transaction.close();
            throw failure;
        }
    }
}
