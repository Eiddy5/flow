package org.flow.core.docs;

import com.fasterxml.jackson.annotation.JsonCreator;

import org.flow.core.utils.Enums;

public enum SchemaType {
    FLOW,
    TASK,
    TRIGGER,
    APPS,
    TESTSUITES,
    REUSABLEINPUTS,
    POLICY;

    @JsonCreator
    public static SchemaType fromString(final String value) {
        return Enums.getForNameIgnoreCase(value, SchemaType.class);
    }
}
