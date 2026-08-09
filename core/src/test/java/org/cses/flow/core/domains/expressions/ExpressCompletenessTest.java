package org.cses.flow.core.domains.expressions;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Completeness matrix for the currently supported Express language.
 */
class ExpressCompletenessTest {

    @TestFactory
    Stream<DynamicTest> coversSupportedOutputConditionScenarios() {
        return Stream.of(
            scenario(
                "route output matches",
                "outputs.decision == \"APPROVED\"",
                Map.of("decision", "APPROVED"),
                List.of("decision"),
                true
            ),
            scenario(
                "outer and operator whitespace is accepted",
                "  outputs.result_2-final==\"ready\"  ",
                Map.of("result_2-final", "ready"),
                List.of("result_2-final"),
                true
            ),
            scenario(
                "empty string value matches",
                "outputs.note == \"\"",
                Map.of("note", ""),
                List.of("note"),
                true
            ),
            scenario(
                "spaces inside value are preserved",
                "outputs.message == \"ready to continue\"",
                Map.of("message", "ready to continue"),
                List.of("message"),
                true
            ),
            scenario(
                "loop iteration output matches",
                "outputs.check.status == \"DONE\"",
                Map.of("check", Map.of("status", "DONE")),
                List.of("check", "status"),
                true
            ),
            scenario(
                "nested output paths are traversed",
                "outputs.group.check.status == \"DONE\"",
                Map.of("group", Map.of(
                    "check",
                    Map.of("status", "DONE")
                )),
                List.of("group", "check", "status"),
                true
            ),
            scenario(
                "comparison is case sensitive",
                "outputs.decision == \"APPROVED\"",
                Map.of("decision", "approved"),
                List.of("decision"),
                false
            ),
            scenario(
                "missing leaf does not match",
                "outputs.check.status == \"DONE\"",
                Map.of("check", Map.of()),
                List.of("check", "status"),
                false
            ),
            scenario(
                "non-map intermediate value does not match",
                "outputs.check.status == \"DONE\"",
                Map.of("check", "DONE"),
                List.of("check", "status"),
                false
            ),
            scenario(
                "non-string leaf does not match",
                "outputs.attempts == \"2\"",
                Map.of("attempts", 2),
                List.of("attempts"),
                false
            ),
            scenario(
                "null outputs do not match",
                "outputs.status == \"DONE\"",
                null,
                List.of("status"),
                false
            )
        ).map(scenario -> DynamicTest.dynamicTest(
            scenario.name(),
            () -> {
                Express expression = Express.parse(scenario.source());
                assertEquals(
                    scenario.path(),
                    expression.outputPath()
                );
                assertEquals(
                    scenario.expectedMatch(),
                    expression.matches(scenario.outputs())
                );
            }
        ));
    }

    @TestFactory
    Stream<DynamicTest> rejectsUnsupportedOrUnsafeExpressionScenarios() {
        return Stream.of(
            invalid("null source", null),
            invalid("blank source", " "),
            invalid("route DIRECT is not Express", "DIRECT"),
            invalid("missing output path", "outputs == \"DONE\""),
            invalid("unsupported input root", "inputs.status == \"DONE\""),
            invalid("digit-leading key", "outputs.1status == \"DONE\""),
            invalid("empty path segment", "outputs.check..status == \"DONE\""),
            invalid("single equals", "outputs.status = \"DONE\""),
            invalid("not equals", "outputs.status != \"DONE\""),
            invalid("unquoted value", "outputs.status == DONE"),
            invalid("single-quoted value", "outputs.status == 'DONE'"),
            invalid("unterminated value", "outputs.status == \"DONE"),
            invalid(
                "method call",
                "outputs.status.trim() == \"DONE\""
            ),
            invalid("array indexing", "outputs.items[0] == \"DONE\""),
            invalid(
                "logical expression",
                "outputs.a == \"A\" && outputs.b == \"B\""
            ),
            invalid(
                "trailing executable text",
                "outputs.status == \"DONE\"; run()"
            )
        ).map(scenario -> DynamicTest.dynamicTest(
            scenario.name(),
            () -> assertThrows(
                IllegalArgumentException.class,
                () -> Express.parse(scenario.source())
            )
        ));
    }

    private static MatchScenario scenario(
        String name,
        String source,
        Map<String, ?> outputs,
        List<String> path,
        boolean expectedMatch
    ) {
        return new MatchScenario(
            name,
            source,
            outputs,
            path,
            expectedMatch
        );
    }

    private static InvalidScenario invalid(String name, String source) {
        return new InvalidScenario(name, source);
    }

    private static final class MatchScenario {

        private final String name;
        private final String source;
        private final Map<String, ?> outputs;
        private final List<String> path;
        private final boolean expectedMatch;

        private MatchScenario(
            String name,
            String source,
            Map<String, ?> outputs,
            List<String> path,
            boolean expectedMatch
        ) {
            this.name = name;
            this.source = source;
            this.outputs = outputs;
            this.path = path;
            this.expectedMatch = expectedMatch;
        }

        private String name() {
            return name;
        }

        private String source() {
            return source;
        }

        private Map<String, ?> outputs() {
            return outputs;
        }

        private List<String> path() {
            return path;
        }

        private boolean expectedMatch() {
            return expectedMatch;
        }
    }

    private static final class InvalidScenario {

        private final String name;
        private final String source;

        private InvalidScenario(String name, String source) {
            this.name = name;
            this.source = source;
        }

        private String name() {
            return name;
        }

        private String source() {
            return source;
        }
    }
}
