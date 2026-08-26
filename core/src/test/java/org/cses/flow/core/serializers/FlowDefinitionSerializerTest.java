package org.cses.flow.core.serializers;

import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.plugins.TaskPluginTestSupport.Context;
import org.junit.jupiter.api.Test;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;

import static org.cses.flow.core.plugins.TaskPluginTestSupport.builtInContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class FlowDefinitionSerializerTest {

    @Test
    void projectsADeployedRevisionWithoutLosingPluginConfiguration() {
        Context plugins = builtInContext();
        FlowDefinitionSerializer serializer = new FlowDefinitionSerializer(
            plugins.jacksonMapper()
        );
        Flow deployed = plugins.deploy(
            source(),
            "purchase-approval",
            null,
            session()
        );

        String yaml = serializer.serialize(deployed);
        Map<String, Object> definition = new YamlParser(
            plugins.jacksonMapper()
        ).parse(yaml);
        List<?> tasks = (List<?>) definition.get("tasks");
        Map<?, ?> parallel = (Map<?, ?>) tasks.getFirst();
        List<?> branches = (List<?>) parallel.get("tasks");
        Map<?, ?> wait = (Map<?, ?>) branches.getFirst();
        Map<?, ?> pause = (Map<?, ?>) wait.get("pause");
        Map<?, ?> log = (Map<?, ?>) branches.get(1);

        assertEquals("purchase-approval", definition.get("key"));
        assertEquals(Map.of("threshold", 1000), definition.get("variables"));
        assertEquals(2, parallel.get("concurrent"));
        assertEquals("P1M", wait.get("duration"));
        assertEquals("WARN", wait.get("behavior"));
        assertEquals("create-approval", pause.get("key"));
        assertEquals(
            "申请 {{ inputs.amount }}",
            log.get("message")
        );
        assertFalse(parallel.containsKey("id"));
        assertFalse(wait.containsKey("id"));
        assertFalse(pause.containsKey("id"));

        Flow reloaded = plugins.deploy(
            yaml,
            "purchase-approval",
            null,
            session()
        );
        assertEquals(
            definition,
            new YamlParser(plugins.jacksonMapper()).parse(
                serializer.serialize(reloaded)
            )
        );
    }

    private static String source() {
        return """
            key: purchase-approval
            description: 采购审批
            variables:
              threshold: 1000
            inputs:
              - key: amount
                type: INTEGER
                displayName: 采购金额
                required: true
                min: 1
            tasks:
              - key: parallel-review
                type: org.cses.flow.extensions.flow.Parallel
                concurrent: 2
                tasks:
                  - key: wait-approval
                    type: org.cses.flow.extensions.flow.Pause
                    pause:
                      key: create-approval
                      type: org.cses.flow.extensions.tasks.AutomaticTask
                    resume:
                      - key: decision
                        type: STRING
                        displayName: 审批结果
                        required: true
                    duration: P1M
                    behavior: WARN
                  - key: write-log
                    type: org.cses.flow.extensions.log.Log
                    message: '申请 {{ inputs.amount }}'
            """;
    }

    private static Session<User> session() {
        User user = new User();
        user.setId("admin");
        user.setName("管理员");
        user.setCompanyId("company-1");

        Session<User> session = new Session<>();
        session.setCompanyId("company-1");
        session.setUser(user);
        return session;
    }
}
