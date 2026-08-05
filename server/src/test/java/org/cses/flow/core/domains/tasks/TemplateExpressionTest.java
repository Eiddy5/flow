package org.cses.flow.core.domains.tasks;

import org.cses.flow.core.exceptions.WorkflowException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemplateExpressionTest {

    @Test
    void rendersLiteralTextAndMultipleRuntimePaths() {
        TemplateExpression expression = TemplateExpression.parse(
            "处理 {{ outputs.order-id }}，结果："
                + "{{ dependOnOutputs.prepare.result }}"
        );

        assertEquals(
            "处理 order-1，结果：ready",
            expression.render(Map.of(
                "outputs", Map.of("order-id", "order-1"),
                "dependOnOutputs", Map.of(
                    "prepare", Map.of("result", "ready")
                )
            ))
        );
        assertEquals(
            "固定消息",
            TemplateExpression.parse("固定消息").render(Map.of())
        );
    }

    @Test
    void rejectsBlankMalformedAndExecutableSyntax() {
        assertThrows(
            IllegalArgumentException.class,
            () -> TemplateExpression.parse(" ")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TemplateExpression.parse("结果：{{ outputs.result")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TemplateExpression.parse("结果：outputs.result }}")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TemplateExpression.parse("{{ outputs.result() }}")
        );
    }

    @Test
    void rejectsMissingAndNonScalarRuntimePaths() {
        TemplateExpression missing = TemplateExpression.parse(
            "{{ dependOnOutputs.prepare.result }}"
        );
        WorkflowException missingFailure = assertThrows(
            WorkflowException.class,
            () -> missing.render(Map.of(
                "dependOnOutputs", Map.of("prepare", Map.of())
            ))
        );
        assertEquals(
            "Task template expression path is missing: "
                + "dependOnOutputs.prepare.result",
            missingFailure.getMessage()
        );

        TemplateExpression nonScalar = TemplateExpression.parse(
            "{{ outputs }}"
        );
        assertThrows(
            WorkflowException.class,
            () -> nonScalar.render(Map.of(
                "outputs", Map.of("result", "ready")
            ))
        );
    }
}
