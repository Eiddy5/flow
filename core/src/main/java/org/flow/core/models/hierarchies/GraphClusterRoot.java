package org.flow.core.models.hierarchies;

import org.flow.core.utils.IdUtils;

import lombok.Getter;

@Getter
public class GraphClusterRoot extends AbstractGraph {
    public GraphClusterRoot() {
        super("root-" + IdUtils.create());
    }
}
