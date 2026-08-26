package org.cses.flow.core.domains.flows;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DatabindContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase;
import org.cses.flow.core.domains.flows.inputs.BooleanInput;
import org.cses.flow.core.domains.flows.inputs.ByteInput;
import org.cses.flow.core.domains.flows.inputs.CharacterInput;
import org.cses.flow.core.domains.flows.inputs.DoubleInput;
import org.cses.flow.core.domains.flows.inputs.FloatInput;
import org.cses.flow.core.domains.flows.inputs.IntegerInput;
import org.cses.flow.core.domains.flows.inputs.LongInput;
import org.cses.flow.core.domains.flows.inputs.ShortInput;
import org.cses.flow.core.domains.flows.inputs.StringInput;

/**
 * Resolves Input subtype names through DataType, accepting trimmed and
 * case-insensitive YAML values while retaining Jackson polymorphism.
 */
public class InputTypeIdResolver extends TypeIdResolverBase {

    @Override
    public JavaType typeFromId(
        DatabindContext context,
        String id
    ) {
        DataType type = DataType.parse(id);
        return context.constructType(inputType(type));
    }

    @Override
    public String idFromValue(Object value) {
        return ((Input<?>) value).getType().name();
    }

    @Override
    public String idFromValueAndType(Object value, Class<?> suggestedType) {
        return idFromValue(value);
    }

    @Override
    public JsonTypeInfo.Id getMechanism() {
        return JsonTypeInfo.Id.NAME;
    }

    private static Class<? extends Input<?>> inputType(DataType type) {
        return switch (type) {
            case STRING -> StringInput.class;
            case BOOLEAN -> BooleanInput.class;
            case BYTE -> ByteInput.class;
            case SHORT -> ShortInput.class;
            case INTEGER -> IntegerInput.class;
            case LONG -> LongInput.class;
            case FLOAT -> FloatInput.class;
            case DOUBLE -> DoubleInput.class;
            case CHARACTER -> CharacterInput.class;
        };
    }
}
