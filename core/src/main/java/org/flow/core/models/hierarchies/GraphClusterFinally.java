package org.flow.core.models.hierarchies;

import org.flow.core.utils.IdUtils;

import lombok.Getter;

@Getter
public class GraphClusterFinally extends AbstractGraph {
    public GraphClusterFinally() {
        super("finally-" + IdUtils.create());
    }
}
