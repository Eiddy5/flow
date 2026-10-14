package org.flow.core.runners.pebble.filters;

import java.util.Map;

import org.flow.core.runners.pebble.AbstractDate;
import org.flow.core.utils.Slugify;

import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.extension.Filter;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;

public class SlugifyFilter extends AbstractDate implements Filter {
    @Override
    public Object apply(Object input, Map<String, Object> args, PebbleTemplate self, EvaluationContext context, int lineNumber) throws PebbleException {
        if (input == null) {
            return null;
        }

        return Slugify.of(input.toString());
    }
}
