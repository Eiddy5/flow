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

import java.io.IOException;
import java.util.Map;

/**
 * Resolves Input subtype names with the same trim-and-case rules as
 * {@link DataType#parse(String)}.
 */
public final class InputTypeIdResolver extends TypeIdResolverBase {

    private static final Map<DataType, Class<? extends Input<?>>> INPUT_TYPES =
        Map.of(
            DataType.STRING, StringInput.class,
            DataType.BOOLEAN, BooleanInput.class,
            DataType.BYTE, ByteInput.class,
            DataType.SHORT, ShortInput.class,
            DataType.INTEGER, IntegerInput.class,
            DataType.LONG, LongInput.class,
            DataType.FLOAT, FloatInput.class,
            DataType.DOUBLE, DoubleInput.class,
            DataType.CHARACTER, CharacterInput.class
        );

    @Override
    public String idFromValue(Object value) {
        return idFromValueAndType(value, value.getClass());
    }

    @Override
    public String idFromValueAndType(Object value, Class<?> suggestedType) {
        if (value instanceof Input<?> input) {
            return input.type().name();
        }
        return suggestedType.getSimpleName();
    }

    @Override
    public JavaType typeFromId(
        DatabindContext context,
        String id
    ) throws IOException {
        DataType type = DataType.parse(id);
        Class<? extends Input<?>> concreteType = INPUT_TYPES.get(type);
        return context.constructType(concreteType);
    }

    @Override
    public String getDescForKnownTypeIds() {
        return INPUT_TYPES.keySet().stream()
            .map(DataType::name)
            .sorted()
            .toList()
            .toString();
    }

    @Override
    public JsonTypeInfo.Id getMechanism() {
        return JsonTypeInfo.Id.CUSTOM;
    }
}
