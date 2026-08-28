package org.cses.flow.core.domains.tasks;

import org.cses.flow.core.domains.Identified;
import org.cses.flow.core.plugins.Plugin;

public interface TaskInterface extends Plugin, Identified {

    String id();
}
