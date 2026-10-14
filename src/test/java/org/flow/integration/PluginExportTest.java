package org.flow.integration;

import org.flow.core.docs.PluginExportService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;

import org.flow.core.models.flows.Flow;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.plugins.PluginRegistry;
import org.flow.core.plugins.PluginSchemaBundleConfig;
import org.flow.core.plugins.PluginSchemaBundleService;
import org.flow.core.utils.VersionProvider;
import org.flow.core.serializers.JacksonMapper;
import org.flow.core.serializers.YamlParser;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(startApplication = false)
@Property(name = "flow.migration.auto", value = "false")
class PluginExportTest {
    @Inject PluginRegistry registry;
    @Inject PluginExportService exports;
    @Inject VersionProvider version;
    @Inject org.flow.core.models.validations.ModelValidator validator;
    @Inject org.flow.core.services.FlowParsingService parsing;
    @TempDir Path directory;

    /** 验证外部 JAR 的生成资源、真实加载、YAML 类型解析及文档/Schema/图标导出。
     * @throws Exception 制品缺失、类型解析或导出失败时使测试失败
     */
    @Test
    void loadsBuiltPluginAndExportsItsDefinition() throws Exception {
        Path plugins = Path.of(System.getProperty("fixture.plugins"));
        Path jar;
        try (var paths = Files.list(plugins)) {
            jar = paths.filter(path -> path.toString().endsWith(".jar")).findFirst().orElseThrow();
        }
        try (JarFile archive = new JarFile(jar.toFile())) {
            var metadata = archive.getManifest().getMainAttributes();
            assertEquals("org.flow.fixture", metadata.getValue("X-Flow-Group"));
            assertEquals("plugin-fixture", metadata.getValue("X-Flow-Name"));
            assertEquals("2.0.5", metadata.getValue("X-Flow-Version"));
            var service = archive.getJarEntry("META-INF/services/org.flow.core.models.Plugin");
            assertNotNull(service);
            assertTrue(new String(archive.getInputStream(service).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("org.flow.fixture.Echo"));
        }
        Flow flow = YamlParser.parse("""
            id: fixture
            namespace: migration
            tasks:
              - id: echo
                type: org.flow.fixture.Echo
                text: migrated
            """, Flow.class);
        var task = flow.getTasks().getFirst();
        assertNotEquals(getClass().getClassLoader(), task.getClass().getClassLoader());
        assertEquals("migrated", ((RunnableTask<?>) task).run(null).toMap().get("text"));
        var paths = exports.export(directory.resolve("export"), false);
        assertTrue(paths.stream().anyMatch(path -> path.toString().endsWith(".svg")));
        boolean foundSchema = false;
        boolean foundDocumentation = false;
        for (Path path : paths) {
            String content = Files.readString(path);
            if (path.toString().endsWith(".json")) {
                assertNotNull(JacksonMapper.ofJson().readTree(content));
                foundSchema |= content.contains("text");
            }
            if (path.toString().endsWith(".md")) {
                foundDocumentation |= content.contains("org.flow.fixture.Echo");
            }
        }
        assertTrue(foundSchema);
        assertTrue(foundDocumentation);
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
            () -> exports.export(directory.resolve("export"), false));
    }

    /**
     * 导出全部内置与外部插件，逐个验证已注册 Task 有文档并能解析所有 Schema。
     * @throws Exception 任一类型生成或文件解析失败时使测试失败
     */
    @Test
    void exportsAllRegisteredTasks() throws Exception {
        var paths = exports.export(directory.resolve("all-plugins"), true);
        StringBuilder documents = new StringBuilder();
        int schemas = 0;
        for (Path path : paths) {
            if (path.toString().endsWith(".json")) {
                assertNotNull(JacksonMapper.ofJson().readTree(path.toFile()));
                schemas++;
            } else if (path.toString().endsWith(".md")) {
                documents.append(Files.readString(path));
            }
        }
        var tasks = registry.plugins().stream().flatMap(plugin -> plugin.getTasks().stream()).toList();
        Set<String> declaredTasks = new HashSet<>();
        var resources = getClass().getClassLoader().getResources("META-INF/services/org.flow.core.models.Plugin");
        while (resources.hasMoreElements()) {
            try (var stream = resources.nextElement().openStream()) {
                for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                    String name = line.strip();
                    if (name.startsWith("org.flow.") && Task.class.isAssignableFrom(Class.forName(name))) {
                        declaredTasks.add(name);
                    }
                }
            }
        }
        assertTrue(declaredTasks.contains("org.flow.plugin.core.flow.If"));
        assertEquals(declaredTasks, tasks.stream()
            .filter(task -> task.getClassLoader() == Task.class.getClassLoader())
            .map(Class::getName)
            .collect(java.util.stream.Collectors.toSet()), "所有内置注册资源中的 Task 都必须被发现");
        assertTrue(schemas >= tasks.size(), "不能仅导出目录而遗漏 Task Schema");
        for (var task : tasks) {
            assertTrue(documents.indexOf(task.getName()) >= 0, task.getName());
        }
        System.out.println("完整插件导出：Task=" + tasks.size() + ", Schema=" + schemas + ", 文件=" + paths.size());
    }

    /**
     * 验证提取的离线工具真实生成的聚合资源可以离线重载，并且所有内部引用均可解析。
     * @throws Exception 聚合资源缺失、引用断裂或重载失败时使测试失败
     */
    @Test
    void reloadsOfflineSchemaBundle() throws Exception {
        Path bundlePath = Path.of(System.getProperty("flow.schema-bundle"));
        JsonNode bundle = JacksonMapper.ofJson().readTree(bundlePath.toFile());
        assertEquals(3, bundle.path("roots").size());
        for (String root : Set.of("flow", "task", "trigger")) {
            String reference = bundle.path("roots").path(root).asText();
            assertTrue(reference.startsWith("#/definitions/"), root);
            assertFalse(bundle.at(reference.substring(1)).isMissingNode(), root);
        }
        assertFalse(bundle.path("definitions").path("org.flow.fixture.Echo").isMissingNode());
        assertFalse(bundle.path("definitions").path("org.flow.fixture.ScriptProbe").isMissingNode());
        assertFalse(bundle.path("definitions").path("org.flow.fixture.LocalProcess").isMissingNode());
        assertFalse(bundle.toString().contains("org.flow.plugin.core.dashboard"));
        assertFalse(bundle.toString().contains("org.flow.plugin.scripts.runner.docker"));
        verifiesReferences(bundle, bundle);
        var service = new PluginSchemaBundleService(
            new PluginSchemaBundleConfig(Optional.of(bundlePath.toString()), Optional.empty()), version);
        assertTrue(service.isEnabled());
        assertTrue(service.containsType("org.flow.fixture.Echo"));
        assertTrue(service.containsType("org.flow.plugin.core.log.Log"));
        assertTrue(service.catalogEntries().stream().anyMatch(plugin ->
            plugin.groupId().equals("org.flow.fixture") && plugin.artifactId().equals("plugin-fixture")));
    }

    /** 验证退役类型退出 classpath，并且脚本不会自动回退为本地进程执行。 */
    @Test
    void rejectsRetiredTypesAndRequiresExplicitScriptRunner() throws Exception {
        for (String type : Set.of(
            "org.flow.core.models.dashboards.Dashboard",
            "org.flow.plugin.core.trigger.McpToolTrigger",
            "org.flow.plugin.scripts.runner.docker.Docker",
            "org.flow.plugin.scripts.exec.scripts.models.DockerOptions",
            "org.flow.plugin.scripts.exec.scripts.models.RunnerType",
            "org.flow.core.plugins.PluginAutoInstallService",
            "org.flow.core.reporter.ServerEventSender",
            "org.flow.core.services.LogStreamingService",
            "org.flow.core.services.ExpressionContextService",
            "org.flow.core.models.ui.PluginUiManifest",
            "org.flow.jdbc.migration.V2_0_04BasicAuthPasswordMigration")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(type), type);
        }
        Flow flow = YamlParser.parse("""
            id: missing_runner
            namespace: migration
            tasks:
              - id: script
                type: org.flow.fixture.ScriptProbe
                commands:
                  - echo must-not-run
            """, Flow.class);
        var violation = assertThrows(jakarta.validation.ConstraintViolationException.class,
            () -> validator.validate(flow.getTasks().getFirst()));
        assertTrue(violation.getMessage().contains("taskRunner"));
        for (String retiredConfiguration : Set.of(
            "    taskRunner:\n      type: org.flow.plugin.scripts.runner.docker.Docker\n",
            "    runner: DOCKER\n",
            "    docker: {}\n",
            "    warningOnStdErr: true\n")) {
            var error = assertThrows(org.flow.core.exceptions.FlowProcessingException.class,
                () -> parsing.parse("main", """
                    id: retired_runner
                    namespace: migration
                    tasks:
                      - id: script
                        type: org.flow.fixture.ScriptProbe
                        commands: [echo must-not-run]
                    """ + retiredConfiguration, true));
            assertNotNull(error.getCause());
        }
    }

    /** 验证退役数据库类型不能被加载，MySQL 专用日志清理参数不再被流程解析接受。 */
    @Test
    void rejectsRetiredDatabaseAndLogConfiguration() {
        for (String type : Set.of(
            "org.h2.Driver",
            "org.flow.repository.h2.H2Repository",
            "org.flow.runner.memory.DatasourceProvider",
            "org.flow.runner.memory.EphemeralDatasourceRewriter",
            "org.flow.jdbc.EphemeralDatabase")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(type), type);
        }
        assertThrows(org.flow.core.exceptions.FlowProcessingException.class,
            () -> parsing.parse("main", """
                id: retired_log_batch
                namespace: migration
                tasks:
                  - id: purge
                    type: org.flow.plugin.core.log.PurgeLogs
                    batchSize: 100
                """, true));
    }

    /**
     * 递归核验 JSON Pointer；未知引用必须让验收失败。
     * @param root 聚合资源的根对象
     * @param node 当前检查的节点
     */
    private void verifiesReferences(JsonNode root, JsonNode node) {
        if (node.isObject() && node.has("$ref")) {
            String reference = node.path("$ref").asText();
            assertTrue(reference.startsWith("#/"), reference);
            assertFalse(root.at(reference.substring(1)).isMissingNode(), reference);
        }
        node.forEach(child -> verifiesReferences(root, child));
    }
}
