package org.flow.core.models.hierarchies;

import org.flow.core.utils.IdUtils;

import lombok.Getter;

@Getter
public class GraphClusterAfterExecution extends AbstractGraph {
    public GraphClusterAfterExecution() {
        super("after-execution-" + IdUtils.create());
    }
}
