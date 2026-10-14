package org.flow.core.models.hierarchies;

import org.flow.core.utils.IdUtils;

import lombok.Getter;

@Getter
public class GraphClusterEnd extends AbstractGraph {
    public GraphClusterEnd() {
        super("end-" + IdUtils.create());
    }
}
