package org.cses.flow.runtime.session;

import org.cses.flow.definition.session.DefinitionSession;

public interface EngineSessionFactory {

    EngineTransaction openTransaction();

    DefinitionSession openDefinitionSession(EngineTransaction transaction);

    RuntimeSession openRuntimeSession(EngineTransaction transaction);
}
