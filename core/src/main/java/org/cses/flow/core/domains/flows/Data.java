package org.cses.flow.core.domains.flows;

/**
 * Definition contract shared by Task and Flow inputs and outputs.
 */
public interface Data {

    /**
     * Returns the unique business key within the owning direction.
     */
    String getKey();

    /**
     * Returns the stable value type of this data definition.
     */
    DataType getType();

}
