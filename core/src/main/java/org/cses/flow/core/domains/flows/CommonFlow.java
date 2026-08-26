package org.cses.flow.core.domains.flows;

import org.cses.flow.core.domains.tasks.CommonTask;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;

public class CommonFlow extends AbstractFlow {

    Map<String, Object> properties;
    List<CommonTask> tasks;

    protected CommonFlow(String id, String key, Long version, boolean draft, Session<? extends User> session, String description, Map<String, ?> variables, List<? extends Input<?>> inputs, List<? extends Output> outputs) {
        super(id, key, version, draft, session, description, variables, inputs, outputs);
    }
}
