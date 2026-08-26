package org.cses.flow.core.domains.tasks;

import java.util.Map;

public class CommonTask implements TaskInterface {

    String id;
    String type;
    Map<String, Object> properties;

    @Override
    public String id() {
        return id;
    }

    @Override
    public String getType() {
        return type;
    }
}
