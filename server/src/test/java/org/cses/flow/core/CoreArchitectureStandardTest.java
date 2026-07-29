package org.cses.flow.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreArchitectureStandardTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Path TEST_JAVA = Path.of("src/test/java");
    private static final Path FLOW = MAIN_JAVA.resolve("org/cses/flow");
    private static final Path CORE = FLOW.resolve("core");
    private static final Set<String> PARTITIONED_DIRECTORIES = Set.of(
        "commands",
        "exceptions",
        "handlers",
        "queries",
        "repositories",
        "services"
    );
    private static final Set<String> FORBIDDEN_CORE_RUNTIME_DIRECTORIES =
        Set.of(
            "executor",
            "executors",
            "worker",
            "workers"
        );
    private static final Pattern RECORD_DECLARATION = Pattern.compile(
        "\\brecord\\s+[A-Za-z_$][A-Za-z\\d_$]*"
    );
    private static final Pattern NUMERIC_TECHNICAL_ID = Pattern.compile(
        "\\b(?:long|Long|UUID)\\s+"
            + "(?:id|flowId|taskId|executionId|taskRunId|"
            + "externalTaskId|parentId)\\b"
    );
    private static final Pattern NONSTANDARD_ID_GENERATOR = Pattern.compile(
        "UUID\\s*\\.\\s*randomUUID\\s*\\(\\)"
            + "|new\\s+AtomicLong\\s*\\("
    );

    @Test
    void domainsContainOnlyDomainTypes() throws IOException {
        Path domains = CORE.resolve("domains");
        List<String> invalid = new ArrayList<>();

        try (var paths = Files.walk(domains)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                .map(path -> path.getFileName().toString())
                .filter(CoreArchitectureStandardTest::isNonDomainName)
                .forEach(invalid::add);
        }

        assertTrue(
            invalid.isEmpty(),
            () -> "Non-domain types found in core/domains: " + invalid
        );
    }

    @Test
    void coreTechnicalDirectoriesArePartitionedByBusiness() {
        List<String> invalid = PARTITIONED_DIRECTORIES.stream()
            .map(CORE::resolve)
            .filter(Files::isDirectory)
            .flatMap(directory -> directJavaFiles(directory).stream())
            .map(Path::toString)
            .toList();

        assertTrue(
            invalid.isEmpty(),
            () -> "Flat Core technical types found: " + invalid
        );
    }

    @Test
    void productionCoreDoesNotContainDomainFactories() throws IOException {
        assertTrue(
            Files.notExists(CORE.resolve("factories")),
            "Core must not contain a factories directory"
        );

        List<String> factories;
        try (var paths = Files.walk(CORE)) {
            factories = paths
                .filter(path -> path.toString().endsWith("Factory.java"))
                .map(Path::toString)
                .toList();
        }

        assertTrue(
            factories.isEmpty(),
            () -> "Domain factories found in Core: " + factories
        );
    }

    @Test
    void executorAndWorkerAreTopLevelPeersOfCore() throws IOException {
        assertTrue(
            Files.isRegularFile(FLOW.resolve(
                "executor/ExecutorService.java"
            )),
            "ExecutorService must be in the top-level executor package"
        );
        assertTrue(
            Files.isRegularFile(FLOW.resolve(
                "worker/WorkerDispatcher.java"
            )),
            "WorkerDispatcher must be in the top-level worker package"
        );

        List<String> misplaced = new ArrayList<>();
        for (String directory : FORBIDDEN_CORE_RUNTIME_DIRECTORIES) {
            Path path = CORE.resolve(directory);
            if (!Files.isDirectory(path)) {
                continue;
            }
            try (var paths = Files.walk(path)) {
                paths.filter(file -> file.toString().endsWith(".java"))
                    .map(Path::toString)
                    .forEach(misplaced::add);
            }
        }

        assertTrue(
            misplaced.isEmpty(),
            () -> "Executor/Worker sources found inside Core: " + misplaced
        );
    }

    @Test
    void javaSourcesDoNotUseRecordsOrNumericTechnicalIds()
        throws IOException {

        List<String> invalid = new ArrayList<>();
        inspectSources(MAIN_JAVA, invalid);
        inspectSources(TEST_JAVA, invalid);

        assertTrue(
            invalid.isEmpty(),
            () -> "Java development standard violations: " + invalid
        );
    }

    @Test
    void productionSourcesDoNotContainInMemoryAdapters()
        throws IOException {

        List<String> invalid;
        try (var paths = Files.walk(MAIN_JAVA)) {
            invalid = paths
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path ->
                    path.getFileName().toString().startsWith("InMemory")
                )
                .map(Path::toString)
                .toList();
        }

        assertTrue(
            invalid.isEmpty(),
            () -> "In-memory adapters found in production: " + invalid
        );
    }

    @Test
    void serializationIsFlatAndYamlParserIsBusinessNeutral()
        throws IOException {

        Path serialization = CORE.resolve("serializers");
        Path yamlParser = serialization.resolve("YamlParser.java");
        assertTrue(
            Files.isRegularFile(yamlParser),
            "YamlParser must be the Core YAML entry point"
        );

        List<String> nestedDirectories;
        try (var paths = Files.list(serialization)) {
            nestedDirectories = paths
                .filter(Files::isDirectory)
                .map(Path::toString)
                .toList();
        }
        assertTrue(
            nestedDirectories.isEmpty(),
            () -> "Serialization must remain flat: " + nestedDirectories
        );

        String source = Files.readString(yamlParser);
        assertTrue(
            source.contains("com.fasterxml.jackson.databind.ObjectMapper")
                && source.contains(
                    "com.fasterxml.jackson.dataformat.yaml.YAMLFactory"
                )
                && !source.contains("org.yaml.snakeyaml"),
            "YamlParser must use Jackson ObjectMapper with YAMLFactory"
        );
        assertTrue(
            !source.contains("org.cses.flow.core.domains")
                && !source.contains("org.cses.flow.core.commands")
                && !source.contains("org.cses.flow.extensions"),
            "YamlParser must not interpret Flow, Task, or extension types"
        );
        assertTrue(
            Files.notExists(CORE.resolve(
                "services/flows/FlowReader.java"
            )),
            "Flow definition Reader must not be restored"
        );
        assertTrue(
            Files.notExists(CORE.resolve(
                "services/flows/YamlFlowReader.java"
            )),
            "Flow-specific YAML Reader must not be restored"
        );
        assertTrue(
            Files.notExists(CORE.resolve(
                "services/tasks/TaskDefinitionAssembler.java"
            )),
            "Task identity assembly belongs to Flow"
        );
        assertTrue(
            Files.notExists(CORE.resolve(
                "domains/flows/FlowInput.java"
            )),
            "Flow must consume the generic parser result directly"
        );
        assertTrue(
            Files.notExists(CORE.resolve(
                "domains/tasks/TaskDefinitionInput.java"
            )),
            "Task mapping must remain inside Flow"
        );
    }

    @Test
    void taskMaterializationUsesAPluginRegistryWithoutTypeBranches()
        throws IOException {

        Path taskSpi = CORE.resolve("domains/tasks/TaskPlugin.java");
        Path extensions = FLOW.resolve("extensions/tasks");
        Path registry = extensions.resolve("TaskPluginRegistry.java");
        Path dispatcher = extensions.resolve(
            "RegisteredTaskTypeDispatcher.java"
        );

        assertTrue(
            Files.isRegularFile(taskSpi)
                && Files.isRegularFile(registry)
                && Files.isRegularFile(dispatcher),
            "Task plugin SPI, registry, and dispatcher must exist"
        );
        assertTrue(
            Files.notExists(extensions.resolve(
                "BuiltInTaskTypeDispatcher.java"
            )),
            "Hard-coded built-in Task dispatcher must not be restored"
        );

        String dispatcherSource = Files.readString(dispatcher);
        assertTrue(
            !dispatcherSource.contains("switch")
                && !dispatcherSource.contains("AutomaticTask")
                && !dispatcherSource.contains("PauseTask"),
            "Task dispatcher must resolve plugins without concrete branches"
        );

        String registrySource = Files.readString(registry);
        assertTrue(
            registrySource.contains("Collection<TaskPlugin>")
                && registrySource.contains("@Context")
                && registrySource.contains("putIfAbsent")
                && registrySource.contains("Locale.ROOT"),
            "Task plugins must be eagerly registered by unique type"
        );
    }

    private static boolean isNonDomainName(String fileName) {
        return fileName.endsWith("Snapshot.java")
            || fileName.endsWith("Reference.java")
            || fileName.endsWith("Factory.java")
            || fileName.endsWith("Reader.java")
            || fileName.endsWith("Command.java")
            || fileName.endsWith("Query.java")
            || fileName.endsWith("Dto.java")
            || fileName.endsWith("DTO.java");
    }

    private static List<Path> directJavaFiles(Path directory) {
        try (var paths = Files.list(directory)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .toList();
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to inspect " + directory,
                exception
            );
        }
    }

    private static void inspectSources(
        Path sourceRoot,
        List<String> invalid
    ) throws IOException {
        try (var paths = Files.walk(sourceRoot)) {
            for (Path path : paths
                .filter(file -> file.toString().endsWith(".java"))
                .toList()) {

                String source = Files.readString(path);
                if (RECORD_DECLARATION.matcher(source).find()) {
                    invalid.add(path + " declares record");
                }
                if (NUMERIC_TECHNICAL_ID.matcher(source).find()) {
                    invalid.add(path + " declares numeric technical id");
                }
                if (NONSTANDARD_ID_GENERATOR.matcher(source).find()) {
                    invalid.add(path + " generates a non-standard id");
                }
            }
        }
    }
}
