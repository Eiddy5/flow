package org.flow.repository.postgres;

import java.util.List;
import java.util.stream.Collectors;

import org.jooq.Condition;
import org.jooq.impl.DSL;
import org.slf4j.event.Level;



public final class PostgresLogRepositoryService {
    private PostgresLogRepositoryService() {
        // utility class pattern
    }

    public static Condition levelsCondition(List<Level> levels) {
        return DSL.condition(
            "level in (" +
                levels
                    .stream()
                    .map(s -> "'" + s + "'::log_level")
                    .collect(Collectors.joining(", "))
                +
                ")"
        );
    }

    public static Condition notLevelsCondition(List<Level> levels) {
        return DSL.condition(
            "level not in (" +
                levels
                    .stream()
                    .map(s -> "'" + s + "'::log_level")
                    .collect(Collectors.joining(", "))
                +
                ")"
        );
    }

}
