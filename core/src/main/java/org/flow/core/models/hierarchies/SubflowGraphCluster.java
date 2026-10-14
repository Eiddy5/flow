package org.flow.core.models.hierarchies;

import lombok.Getter;

@SuppressWarnings("this-escape")
@Getter
public class SubflowGraphCluster extends GraphCluster {
    public SubflowGraphCluster(String uid, SubflowGraphTask subflowGraphTask) {
        super(subflowGraphTask, uid, RelationType.SEQUENTIAL);

        this.getGraph().addNode(subflowGraphTask);
        this.addEdge(this.getRoot(), subflowGraphTask, new Relation());
    }
}