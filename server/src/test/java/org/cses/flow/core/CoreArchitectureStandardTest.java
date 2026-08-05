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
        Path taskDomain = CORE.resolve("domains/tasks");
        Path runner = CORE.resolve("runner");
        assertTrue(
            Files.isRegularFile(FLOW.resolve(
                "executor/ExecutorService.java"
            ))
                && Files.isRegularFile(FLOW.resolve(
                    "executor/DefaultExecutor.java"
                )),
            "ExecutorService and DefaultExecutor must be in the top-level "
                + "executor package"
        );
        assertTrue(
            Files.isRegularFile(FLOW.resolve(
                "worker/WorkerDispatcher.java"
            ))
                && Files.isRegularFile(FLOW.resolve(
                    "worker/WorkerTask.java"
                ))
                && Files.isRegularFile(FLOW.resolve(
                    "worker/WorkerTaskResult.java"
                )),
            "Worker runtime components must remain in the top-level worker "
                + "package"
        );
        assertTrue(
            Files.isRegularFile(taskDomain.resolve("RunnableTask.java"))
                && Files.isRegularFile(taskDomain.resolve(
                    "OrchestrationTask.java"
                ))
                && Files.isRegularFile(runner.resolve(
                    "RunContext.java"
                ))
                && Files.isRegularFile(taskDomain.resolve(
                    "RunResult.java"
                )),
            "Task capabilities and results must remain in the Task domain, "
                + "with invocation context in the Core runner"
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

        Path executorContext = FLOW.resolve(
            "executor/ExecutorContext.java"
        );
        String contextSource = Files.readString(executorContext);
        assertTrue(
            contextSource.contains(
                "private final List<TaskRun> nexts;"
            )
                && contextSource.contains(
                    "private final List<TaskRun> pausedTaskRuns;"
                )
                && contextSource.contains(
                    "private final List<String> orchestrationCompletions;"
                )
                && !contextSource.contains("DSLContext")
                && !contextSource.contains("Session<")
                && Files.notExists(FLOW.resolve(
                    "executor/NextTask.java"
                ))
                && Files.notExists(CORE.resolve(
                    "handlers/executions/ExecutionHandler.java"
                )),
            "ExecutorContext must expose TaskRun nexts, pause effects, and "
                + "orchestration completions "
                + "without carrying transaction runtime objects"
        );

        for (String handler : List.of(
            "ContinueExecutionHandler.java",
            "ResumeExecutionHandler.java",
            "CancelExecutionHandler.java"
        )) {
            String source = Files.readString(CORE.resolve(
                "handlers/executions/" + handler
            ));
            assertTrue(
                source.contains("executionRepository.lockById("),
                () -> handler
                    + " must lock the Execution before mutation"
            );
        }
    }

    @Test
    void taskCapabilitiesKeepOrchestrationOutOfWorkers() throws IOException {
        Path taskDomain = CORE.resolve("domains/tasks");
        Path runner = CORE.resolve("runner");
        Path worker = FLOW.resolve("worker");
        Path executor = FLOW.resolve("executor");
        Path extensionWorkers = FLOW.resolve("extensions/workers");
        String runContext = Files.readString(runner.resolve(
            "RunContext.java"
        ));
        String dispatcher = Files.readString(worker.resolve(
            "WorkerDispatcher.java"
        ));
        String workerTask = Files.readString(worker.resolve(
            "WorkerTask.java"
        ));
        String workerResult = Files.readString(worker.resolve(
            "WorkerTaskResult.java"
        ));
        String orchestrationTask = Files.readString(taskDomain.resolve(
            "OrchestrationTask.java"
        ));
        String automatic = Files.readString(FLOW.resolve(
            "extensions/tasks/AutomaticTask.java"
        ));
        String pause = Files.readString(FLOW.resolve(
            "extensions/flow/Pause.java"
        ));
        String parallel = Files.readString(FLOW.resolve(
            "extensions/flow/Parallel.java"
        ));
        String executorService = Files.readString(executor.resolve(
            "ExecutorService.java"
        ));
        String defaultExecutor = Files.readString(executor.resolve(
            "DefaultExecutor.java"
        ));

        assertTrue(
            Files.notExists(worker.resolve("WorkerContext.java"))
                && Files.notExists(worker.resolve(
                    "WorkerTaskHandler.java"
                ))
                && Files.notExists(worker.resolve("RunnableTask.java"))
                && Files.notExists(worker.resolve("RunContext.java"))
                && Files.notExists(worker.resolve("RunResult.java"))
                && Files.notExists(executor.resolve(
                    "OrchestrationTask.java"
                ))
                && (!Files.isDirectory(extensionWorkers)
                    || directJavaFiles(extensionWorkers).isEmpty()),
            "Task capabilities cannot be owned by Worker or Executor, and "
                + "Worker handlers must remain removed"
        );
        assertTrue(
            runContext.contains("private final Map<String, Object> inputs;")
                && !runContext.contains("private final WorkerTask")
                && !runContext.contains("private final TaskRun")
                && !runContext.contains("private final Execution")
                && !runContext.contains("String taskRunId"),
            "RunContext must remain scoped to one RunnableTask invocation"
        );
        assertTrue(
            dispatcher.contains("workerTask.runnableTask().run(context)")
                && workerTask.contains(
                    "private final RunnableTask runnableTask;"
                )
                && !workerTask.contains("private final Task task;")
                && !dispatcher.contains("PluginLoader")
                && !dispatcher.contains("WorkerTaskHandler")
                && !workerResult.contains("State.Type.PAUSED")
                && !orchestrationTask.contains("RunResult run("),
            "Worker must directly run RunnableTask and cannot return PAUSED"
        );
        assertTrue(
            automatic.contains("implements RunnableTask")
                && automatic.contains("RunResult run(RunContext context)")
                && pause.contains("implements OrchestrationTask")
                && parallel.contains("implements OrchestrationTask")
                && executorService.contains(
                    "boolean handle(ExecutorContext context)"
                )
                && executorService.contains("handleOrchestration(")
                && executorService.contains("runnable == orchestration"),
            "Concrete Tasks must declare one capability and Executor must "
                + "handle orchestration directly"
        );
        assertTrue(
            defaultExecutor.contains("executorService.handle(context)")
                && !defaultExecutor.contains("dispatchBranch("),
            "DefaultExecutor must submit Worker effects while "
                + "ExecutorService owns OrchestrationTask state progression"
        );
    }

    @Test
    void workflowRuntimeStateIsOwnedByTheFlowDomain() throws IOException {
        Path flows = CORE.resolve("domains/flows");
        Path executions = CORE.resolve("domains/executions");

        assertTrue(
            Files.isRegularFile(flows.resolve("State.java"))
                && Files.notExists(
                    flows.resolve("FlowDefinitionStatus.java")
                ),
            "Flow domain must own runtime State without a definition "
                + "status enum"
        );
        assertTrue(
            Files.notExists(executions.resolve("ExecutionStatus.java"))
                && Files.notExists(
                    executions.resolve("TaskRunStatus.java")
                )
                && Files.notExists(
                    FLOW.resolve("worker/WorkerTaskOutcome.java")
                )
                && Files.notExists(flows.resolve("FlowStatus.java")),
            "Workflow components must not restore duplicate state types"
        );

        String state = Files.readString(flows.resolve("State.java"));
        String execution = Files.readString(
            executions.resolve("Execution.java")
        );
        String taskRun = Files.readString(
            executions.resolve("TaskRun.java")
        );
        String workerResult = Files.readString(
            FLOW.resolve("worker/WorkerTaskResult.java")
        );
        assertTrue(
            state.contains("private final Type current;")
                && state.contains("private final List<History> history;")
                && state.contains("public Type current()")
                && state.contains("public List<History> history()")
                && execution.contains("private State state;")
                && taskRun.contains("private State state;")
                && workerResult.contains(
                    "private final State.Type targetState;"
                )
                && !execution.contains("public State.Type status()")
                && !taskRun.contains("public State.Type status()"),
            "Lifecycle owners must use State while Worker returns its target "
                + "from the shared State.Type vocabulary"
        );
    }

    @Test
    void javaSourcesFollowRecordAndTechnicalIdRules()
        throws IOException {

        List<String> invalid = new ArrayList<>();
        inspectSources(MAIN_JAVA, invalid);
        inspectSources(TEST_JAVA, invalid);
        inspectRecordDeclarations(CORE.resolve("domains"), invalid);
        inspectRecordDeclarations(
            TEST_JAVA.resolve("org/cses/flow/core/domains"),
            invalid
        );
        inspectEntryRecordDeclarations(invalid);

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
    void serializationCentralizesJacksonAndKeepsYamlParsingNeutral()
        throws IOException {

        Path serialization = CORE.resolve("serializers");
        Path yamlParser = serialization.resolve("YamlParser.java");
        Path jacksonMapper = serialization.resolve("JacksonMapper.java");
        Path flowDeserializer = serialization.resolve(
            "FlowDefinitionDeserializer.java"
        );
        assertTrue(
            Files.isRegularFile(yamlParser)
                && Files.isRegularFile(jacksonMapper)
                && Files.isRegularFile(flowDeserializer),
            "Serialization must expose one mapper, YAML parser, and Flow "
                + "definition deserializer"
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
        String mapperSource = Files.readString(jacksonMapper);
        assertTrue(
            mapperSource.contains(
                "com.fasterxml.jackson.databind.ObjectMapper"
            )
                && mapperSource.contains(
                    "com.fasterxml.jackson.dataformat.yaml.YAMLFactory"
                )
                && mapperSource.contains("PluginModule")
                && !mapperSource.contains("org.yaml.snakeyaml"),
            "JacksonMapper must own strict JSON/YAML mapper configuration"
        );
        assertTrue(
            source.contains("JacksonMapper")
                && !source.contains("YAMLFactory")
                && !source.contains("org.cses.flow.core.domains")
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
            "Task identity assembly belongs to Flow definition binding"
        );
        assertTrue(
            Files.notExists(CORE.resolve(
                "domains/flows/FlowInput.java"
            )),
            "Flow binding must not introduce transport DTOs"
        );
        assertTrue(
            Files.notExists(CORE.resolve(
                "domains/tasks/TaskDefinitionInput.java"
            )),
            "Task binding must use the registered concrete plugin class"
        );
    }

    @Test
    void taskPluginsUseCompileTimeDiscoveryAndExactClassTypes()
        throws IOException {

        Path plugins = CORE.resolve("plugins");
        Path pluginSpi = plugins.resolve("Plugin.java");
        Path pluginAnnotation = plugins.resolve("annotations/Plugin.java");
        Path pluginDeserializer = plugins.resolve(
            "PluginDeserializer.java"
        );
        Path pluginModule = plugins.resolve("PluginModule.java");
        Path extensions = FLOW.resolve("extensions/tasks");
        Path loader = plugins.resolve("PluginLoader.java");
        Path registry = plugins.resolve("PluginRegistry.java");
        Path defaultRegistry = plugins.resolve(
            "DefaultPluginRegistry.java"
        );
        Path pluginMetadata = plugins.resolve("PluginMetadata.java");
        Path registeredPlugin = plugins.resolve("RegisteredPlugin.java");
        Path pluginSchema = CORE.resolve(
            "serializers/PluginSchemaGenerator.java"
        );
        Path dispatcher = plugins.resolve(
            "RegisteredTaskTypeDispatcher.java"
        );

        assertTrue(
            Files.isRegularFile(pluginSpi)
                && Files.isRegularFile(registry)
                && Files.isRegularFile(defaultRegistry)
                && Files.isRegularFile(pluginMetadata)
                && Files.isRegularFile(registeredPlugin)
                && Files.isRegularFile(pluginSchema)
                && Files.isRegularFile(pluginAnnotation)
                && Files.isRegularFile(pluginDeserializer)
                && Files.isRegularFile(pluginModule),
            "Core plugin interface, marker, registry, and Jackson module "
                + "must exist"
        );
        assertTrue(
            List.of(
                "PluginLoader.java",
                "Plugin.java",
                "PluginRegistry.java",
                "TaskExtension.java",
                "TaskTypeDispatcher.java",
                "RegisteredTaskTypeDispatcher.java"
            ).stream()
                .map(extensions::resolve)
                .allMatch(Files::notExists),
            "Extensions must contain implementations, not plugin runtime"
        );
        assertTrue(
            Files.notExists(loader)
                && Files.notExists(dispatcher)
                && Files.notExists(plugins.resolve("TaskExtension.java"))
                && Files.notExists(plugins.resolve("TaskTypeDispatcher.java"))
                && Files.notExists(plugins.resolve("TaskPlugin.java"))
                && Files.notExists(plugins.resolve(
                    "TaskPluginRegistry.java"
                )),
            "Legacy loader, dispatcher, and companion extension contracts "
                + "must stay removed"
        );
        assertTrue(
            Files.notExists(CORE.resolve(
                "domains/tasks/TaskTypeDispatcher.java"
            )),
            "A domain-owned type dispatcher must not be restored"
        );
        assertTrue(
            Files.notExists(extensions.resolve(
                "BuiltInTaskTypeDispatcher.java"
            )),
            "Hard-coded built-in Task dispatcher must not be restored"
        );

        String deserializerSource = Files.readString(pluginDeserializer);
        assertTrue(
            deserializerSource.contains(
                "registry.resolve(type, pluginType)"
            )
                && !deserializerSource.contains("switch")
                && !deserializerSource.contains("AutomaticTask")
                && !deserializerSource.contains("Pause"),
            "Plugin deserialization must resolve without concrete branches"
        );

        String registrySource = Files.readString(registry);
        String defaultRegistrySource = Files.readString(defaultRegistry);
        String pluginSource = Files.readString(pluginSpi);
        String annotationSource = Files.readString(pluginAnnotation);
        assertTrue(
            registrySource.contains("interface PluginRegistry")
                && registrySource.contains("List<RegisteredPlugin>")
                && registrySource.contains("findMetadata")
                && !registrySource.contains("register(")
                && defaultRegistrySource.contains("Collection<")
                && defaultRegistrySource.contains("@Context")
                && defaultRegistrySource.contains("putIfAbsent")
                && defaultRegistrySource.contains("getCanonicalName()")
                && !defaultRegistrySource.contains("Locale.ROOT")
                && !defaultRegistrySource.contains("ServiceLoader"),
            "Plugins must be registered by exact canonical class name"
        );
        assertTrue(
            pluginSource.contains("default String getType()")
                && pluginSource.contains("getCanonicalName()")
                && annotationSource.contains("@Bean")
                && annotationSource.contains("@DefaultScope")
                && annotationSource.contains("@Introspected")
                && annotationSource.contains(
                    "Introspected.AccessKind.FIELD"
                )
                && annotationSource.contains(
                    "Introspected.Visibility.ANY"
                )
                && annotationSource.contains("String title()")
                && annotationSource.contains("String description()"),
            "Plugin and @Plugin must complement each other for type and "
                + "compile-time discovery"
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
                if (NUMERIC_TECHNICAL_ID.matcher(source).find()) {
                    invalid.add(path + " declares numeric technical id");
                }
                if (NONSTANDARD_ID_GENERATOR.matcher(source).find()) {
                    invalid.add(path + " generates a non-standard id");
                }
            }
        }
    }

    private static void inspectRecordDeclarations(
        Path sourceRoot,
        List<String> invalid
    ) throws IOException {
        try (var paths = Files.walk(sourceRoot)) {
            for (Path path : paths
                .filter(file -> file.toString().endsWith(".java"))
                .toList()) {

                String source = Files.readString(path);
                if (RECORD_DECLARATION.matcher(source).find()) {
                    invalid.add(path + " declares record in a class-only area");
                }
            }
        }
    }

    private static void inspectEntryRecordDeclarations(
        List<String> invalid
    ) throws IOException {
        Path repositories = FLOW.resolve("infrastructure/repositories");
        try (var paths = Files.walk(repositories)) {
            for (Path path : paths
                .filter(file -> file.toString().endsWith(".java"))
                .filter(file -> hasPathSegment(file, "entries"))
                .toList()) {

                String source = Files.readString(path);
                if (RECORD_DECLARATION.matcher(source).find()) {
                    invalid.add(path + " declares record in a class-only area");
                }
            }
        }
    }

    private static boolean hasPathSegment(Path path, String segment) {
        for (Path element : path) {
            if (segment.equals(element.toString())) {
                return true;
            }
        }
        return false;
    }
}
