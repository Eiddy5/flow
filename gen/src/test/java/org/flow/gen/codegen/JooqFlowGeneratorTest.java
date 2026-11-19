package org.flow.gen.codegen;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JooqFlowGeneratorTest {
    @Test
    void acceptsExplicitCredentialsWithoutRequiringEnvironmentVariables() {
        assertNotNull(JooqFlowGenerator.Options.from(new String[]{
            "--url=jdbc:postgresql://localhost/flow", "--user=generator", "--password="
        }, Map.of()));
    }

    @Test
    void acceptsEnvironmentConfigurationAndOverrides() {
        assertNotNull(JooqFlowGenerator.Options.from(new String[]{
            "--url", "jdbc:postgresql://localhost/flow", "--schema", "isolated"
        }, Map.of("FLOW_GEN_URL", "invalid", "FLOW_GEN_USER", "generator", "FLOW_GEN_PASSWORD", "test")));
    }

    @Test
    void missingInputFailsWithoutIncludingCredentials() {
        var exception = assertThrows(IllegalArgumentException.class, () ->
            JooqFlowGenerator.Options.from(new String[]{}, Map.of("FLOW_GEN_PASSWORD", "sensitive-value")));
        assertEquals("FLOW_GEN_URL or --url is required.", exception.getMessage());
    }

    @Test
    void invalidUrlFailsWithoutIncludingUrlCredentials() {
        var exception = assertThrows(IllegalArgumentException.class, () ->
            JooqFlowGenerator.Options.from(new String[]{"--url=invalid?password=sensitive-value"}, Map.of()));
        assertEquals("FLOW_GEN_URL must be a PostgreSQL JDBC URL.", exception.getMessage());
    }

    @Test
    void rejectsUnsupportedTargetOverrideWithoutIncludingItsValue() {
        var exception = assertThrows(IllegalArgumentException.class, () ->
            JooqFlowGenerator.Options.from(new String[]{"--target-directory=sensitive-value"}, Map.of()));
        assertEquals("Unknown or duplicate generation option.", exception.getMessage());
    }

    /**
     * 校验生成配置保留运行时 schema 选择，并使用支持限定重命名的内部扩展生成器。
     */
    @Test
    void configurationKeepsSchemaSelectionAtRuntimeAndHasNoJdbcSecrets() {
        var configuration = JooqFlowGenerator.configuration("isolated");
        var generator = configuration.getGenerator();
        assertNull(configuration.getJdbc());
        assertEquals(FlowJavaGenerator.class.getName(), generator.getName());
        assertEquals("isolated", generator.getDatabase().getInputSchema());
        assertTrue(generator.getDatabase().isOutputSchemaToDefault());
        assertEquals("org.flow.gen.flow", generator.getTarget().getPackageName());
        assertEquals(Path.of("src", "main", "java").toString(), generator.getTarget().getDirectory());
        assertFalse(generator.getGenerate().isGeneratedAnnotationDate());
        assertFalse(generator.getGenerate().isGeneratedAnnotationConfigurationHash());
        assertFalse(generator.getGenerate().isRoutines());
    }

    @Test
    void includesRuntimeTablesAndEnumsButExcludesRetiredObjects() {
        var included = Pattern.compile(JooqFlowGenerator.configuration("public")
            .getGenerator().getDatabase().getIncludes());
        for (String name : new String[]{"flows", "queues", "state_type", "log_level"}) {
            assertTrue(included.matcher(name).matches(), name);
        }
        for (String name : new String[]{"dashboards", "mcp", "mcp_session", "flow_migration_history", "pgp_armor_headers"}) {
            assertFalse(included.matcher(name).matches(), name);
        }
    }
}
