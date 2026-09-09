package org.cses.flow.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        "(?m)^\\s*(?:(?:public|protected|private|static|final)\\s+)*"
            + "record\\s+[A-Za-z_$][A-Za-z\\d_$]*"
    );
    private static final Pattern RECORD_DECLARATION_NAME = Pattern.compile(
        "(?m)^\\s*(?:(?:public|protected|private|static|final)\\s+)*"
            + "record\\s+([A-Za-z_$][A-Za-z\\d_$]*)"
    );
    private static final Pattern NUMERIC_TECHNICAL_ID = Pattern.compile(
        "\\b(?:long|Long|UUID)\\s+"
            + "(?:id|flowId|taskId|executionId|taskRunId|"
            + "parentId)\\b"
    );
    private static final Pattern NONSTANDARD_ID_GENERATOR = Pattern.compile(
        "UUID\\s*\\.\\s*randomUUID\\s*\\(\\)"
            + "|new\\s+AtomicLong\\s*\\("
    );
    private static Pattern DATABASE_CONVERSION_METHOD = Pattern.compile(
        "(?m)^\\s*(?:(?:public|protected|private|static|final|"
            + "synchronized|abstract|default)\\s+)*"
            + "[A-Za-z_$][A-Za-z\\d_$<>,.?\\[\\] ]*\\s+"
            + "(?:from|to|toDomain|fromDomain|toEntry|fromRecord)\\s*\\("
    );
    private static Pattern LEGACY_ENTRY_CONVERSION_METHOD = Pattern.compile(
        "\\b(?:toDomain|fromDomain|toEntry|fromRecord)\\s*\\("
    );
    private static Pattern ENTRY_FROM_METHOD = Pattern.compile(
        "(?m)^\\s*public\\s+static\\s+"
            + "[A-Za-z_$][A-Za-z\\d_$]*Entry\\s+from\\s*\\("
    );
    private static Pattern ENTRY_TO_METHOD = Pattern.compile(
        "(?m)^\\s*public\\s+"
            + "[A-Za-z_$][A-Za-z\\d_$<>,.?\\[\\] ]*\\s+to\\s*\\("
    );

    @Test
    void coreGradleModuleContainsNoHttpServerSources() throws IOException {
        assertTrue(
            Files.notExists(FLOW.resolve("Application.java"))
                && Files.notExists(FLOW.resolve("controller")),
            "Application and HTTP controllers belong to the server module"
        );

        List<String> invalid = new ArrayList<>();
        try (var paths = Files.walk(MAIN_JAVA)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        String source = Files.readString(path);
                        if (source.contains("import io.micronaut.http.")) {
                            invalid.add(path.toString());
                        }
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
        }

        assertTrue(
            invalid.isEmpty(),
            () -> "HTTP imports found in the Core Gradle module: " + invalid
        );
    }

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
    void conditionAndTemplateValuesKeepSeparateDomainOwnership() {
        Path conditions = CORE.resolve("domains/conditions");
        Path expressions = CORE.resolve("domains/expressions");
        Path tasks = CORE.resolve("domains/tasks");

        assertTrue(
            Files.isRegularFile(conditions.resolve("Condition.java"))
                && Files.isRegularFile(conditions.resolve(
                    "ConditionParser.java"
                ))
                && Files.notExists(conditions.resolve(
                    "OperandKind.java"
                ))
                && Files.isRegularFile(expressions.resolve(
                    "TemplateExpression.java"
                ))
                && Files.notExists(tasks.resolve("TaskRoute.java"))
                && Files.notExists(tasks.resolve("TemplateExpression.java")),
            "Condition and template expression values must keep their "
                + "separate domain ownership"
        );
    }

    @Test
    void coreTechnicalDirectoriesArePartitionedByBusiness() {
        List<String> invalid = PARTITIONED_DIRECTORIES.stream()
            .map(CORE::resolve)
            .filter(Files::isDirectory)
            .flatMap(directory -> directJavaFiles(directory).stream())
            .filter(path -> !isSharedServiceProtocol(path))
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

    /**
     * Verifies that runtime ownership, command publication, and Execution
     * creation entry points remain at their documented module boundaries.
     *
     * @throws IOException when an inspected source file cannot be read
     */
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
                ))
                && Files.isRegularFile(FLOW.resolve(
                    "executor/ExecutorEvent.java"
                ))
                && Files.isRegularFile(FLOW.resolve(
                    "executor/ExecutorEventHandler.java"
                ))
                && Files.isRegularFile(FLOW.resolve(
                    "executor/handlers/ExecutionCommandEventHandler.java"
                ))
                && Files.isRegularFile(FLOW.resolve(
                    "executor/handlers/ExecutorEventHandler.java"
                ))
                && Files.isRegularFile(FLOW.resolve(
                    "executor/commands/ExecutionCommand.java"
                ))
                && Files.isRegularFile(FLOW.resolve(
                    "executor/commands/Create.java"
                ))
                && Files.isRegularFile(FLOW.resolve(
                    "executor/commands/Resume.java"
                ))
                && Files.isRegularFile(FLOW.resolve(
                    "executor/commands/Cancel.java"
                )),
            "Execution commands, routing, handling and state-machine runtime "
                + "must stay in the top-level executor package"
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
                && Files.isRegularFile(runner.resolve(
                    "RunVariables.java"
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
            "ExecutorContext must expose TaskRun nexts and orchestration "
                + "completions "
                + "without carrying transaction runtime objects"
        );

        String executionService = Files.readString(CORE.resolve(
            "services/executions/ExecutionService.java"
        ));
        String executionDomain = Files.readString(CORE.resolve(
            "domains/executions/Execution.java"
        ));
        String defaultExecutor = Files.readString(FLOW.resolve(
            "executor/DefaultExecutor.java"
        ));
        String commandHandler = Files.readString(FLOW.resolve(
            "executor/handlers/ExecutionCommandEventHandler.java"
        ));
        String eventHandler = Files.readString(FLOW.resolve(
            "executor/handlers/ExecutorEventHandler.java"
        ));
        long publicCreateMethods = Pattern.compile(
            "(?m)^\\s*public\\s+.*\\sCreate\\s+create\\("
        ).matcher(executionService).results().count();
        assertTrue(
            publicCreateMethods == 2
                && executionService.contains("Create.from(")
                && executionService.contains("normalizedInputs")
                && executionService.contains("String executionId")
                && executionService.contains("StringUtil.newId()")
                && executionService.contains("executorCommandQueue.emit(command)")
                && !executionService.contains("createPending")
                && !executionService.contains("continueExecution")
                && !executionService.contains("CommandExecutor")
                && !executionService.contains("DispatchQueue<ExecutorEvent>")
                && !executionDomain.contains("bindInputs(")
                && Files.notExists(CORE.resolve(
                    "services/executions/commands/CreateExecutionCommand.java"
                ))
                && Files.notExists(CORE.resolve(
                    "services/executions/commands/ContinueExecutionCommand.java"
                ))
                && Files.notExists(CORE.resolve(
                    "services/executions/handlers/CreateExecutionHandler.java"
                ))
                && Files.notExists(CORE.resolve(
                    "services/executions/handlers/ContinueExecutionHandler.java"
                ))
                && executionService.contains("Resume.from(")
                && executionService.contains("Cancel.from(")
                && defaultExecutor.contains(
                    "commandHandler.handle(command)"
                )
                && defaultExecutor.contains(
                    "eventHandler.handle(event)"
                )
                && !defaultExecutor.contains("ExecutionRepository")
                && !defaultExecutor.contains("WorkerDispatcher")
                && commandHandler.contains("case Create create")
                && commandHandler.contains("case Resume resume")
                && commandHandler.contains("case Cancel cancel")
                && commandHandler.contains("command.getExecutionId()")
                && commandHandler.contains("validateRepeatedCreate(")
                && commandHandler.contains("eventQueue.emit(")
                && eventHandler.contains("executorService.process(context)")
                && eventHandler.contains("eventQueue.emit(")
                && eventHandler.contains("new ExecutorContext(flow, execution)"),
            "ExecutionService must support generated and preallocated "
                + "Execution IDs while publishing Create, Resume and Cancel commands, "
                + "the command handler must only publish ExecutorEvents, "
                + "and the internal event handler must drive ExecutorContext"
        );
    }

    /**
     * 检查队列契约边界，仅允许 ADR0092 确认的监听注解元数据依赖。
     * @throws IOException 当被检查的生产源码无法读取时抛出
     */
    @Test
    void queueContractsRemainTopLevelPeers()
        throws IOException {
        Path queues = FLOW.resolve("queues");
        Path events = queues.resolve("event");

        for (Path contract : List.of(
            queues.resolve("Queue.java"),
            queues.resolve("DispatchQueue.java"),
            queues.resolve("QueueSubscription.java"),
            queues.resolve("QueueException.java"),
            events.resolve("Event.java"),
            events.resolve("DispatchEvent.java")
        )) {
            assertTrue(
                Files.isRegularFile(contract),
                () -> "Missing Queue contract: " + contract
            );
        }

        assertTrue(
            Files.notExists(CORE.resolve("queues")),
            "Queue contracts must remain a top-level peer of Core"
        );

        String queueContract = Files.readString(queues.resolve("Queue.java"));
        String dispatchContract = Files.readString(
            queues.resolve("DispatchQueue.java")
        );
        String eventContract = Files.readString(
            events.resolve("Event.java")
        );
        assertTrue(
            !queueContract.contains("QueueSubscription subscribe(")
                && !queueContract.contains("extends Event")
                && !queueContract.contains("AutoCloseable")
                && !queueContract.contains("void close(")
                && queueContract.contains("CompletionStage<Void> emitAsync(T event)")
                && dispatchContract.contains("AutoCloseable"),
            "Base Queue must not own Dispatch subscription semantics"
        );
        assertTrue(
            dispatchContract.contains(
                "QueueSubscription subscribe(Consumer<T> consumer);"
            ),
            "DispatchQueue must own competing Consumer registration"
        );
        assertTrue(
            !eventContract.contains("DSLContext")
                && !eventContract.contains("dsl()")
                && dispatchContract.contains(
                    "void emitInTransaction(T event, DSLContext dsl);"
                ),
            "Event must remain pure business data and DispatchQueue must "
                + "own explicit caller transaction publishing"
        );

        List<String> coupled = new ArrayList<>();
        List<String> unexpectedJooqDependencies = new ArrayList<>();
        try (var paths = Files.walk(queues)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        String source = Files.readString(path);
                        if (path.equals(queues.resolve(
                            "annotations/FlowQueueListener.java"
                        ))) {
                            source = source.replace(
                                "import io.micronaut.context.annotation.Executable;",
                                ""
                            );
                        }
                        if (source.contains("import io.micronaut.")
                            || source.contains(
                                "import org.cses.flow.core."
                            )
                            || source.contains(
                                "import org.cses.flow.executor."
                            )
                            || source.contains(
                                "import org.cses.flow.worker."
                            )
                            || source.contains(
                                "import org.cses.flow.infrastructure."
                            )) {
                            coupled.add(path.toString());
                        }
                        if (source.contains("import org.jooq.")
                            && !path.equals(queues.resolve(
                                "DispatchQueue.java"
                            ))) {
                            unexpectedJooqDependencies.add(path.toString());
                        }
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
        }

        assertTrue(
            coupled.isEmpty(),
            () -> "Queue contracts depend on unrelated Flow modules: "
                + coupled
        );
        assertTrue(
            unexpectedJooqDependencies.isEmpty(),
            () -> "Only DispatchQueue transaction publishing may "
                + "expose the confirmed JOOQ dependency: "
                + unexpectedJooqDependencies
        );
    }

    /**
     * Verifies annotation-selected publisher injection and the two production listener declarations.
     * @throws IOException when an inspected source cannot be read
     */
    @Test
    void executorUsesTheAnnotationDrivenPulsarQueues() throws IOException {
        String executor = Files.readString(FLOW.resolve("executor/DefaultExecutor.java"));
        String service = Files.readString(CORE.resolve("services/executions/ExecutionService.java"));
        String commandHandler = Files.readString(FLOW.resolve("executor/handlers/ExecutionCommandEventHandler.java"));
        String eventHandler = Files.readString(FLOW.resolve("executor/handlers/ExecutorEventMessageHandler.java"));
        assertTrue(executor.contains("@FlowQueueListener(subscription = ExecutionCommand.QUEUE_NAME)")
            && executor.contains("@FlowQueueListener(subscription = ExecutorEvent.QUEUE_NAME)")
            && executor.contains("public void onCommand(ExecutionCommand command)")
            && executor.contains("public void onEvent(ExecutorEvent event)")
            && !executor.contains(".subscribe(") && !executor.contains("DispatchQueue")
            && !executor.contains(".close("));
        assertTrue(service.contains("Queue<ExecutionCommand> executorCommandQueue")
            && commandHandler.contains("Queue<ExecutorEvent> eventQueue")
            && eventHandler.contains("Queue<ExecutorEvent> eventQueue"));
        for (String source : List.of(service, commandHandler, eventHandler)) {
            assertFalse(source.contains("DispatchQueue<"));
            assertFalse(source.contains("@Named(ExecutionCommand.QUEUE_NAME)"));
            assertFalse(source.contains("@Named(ExecutorEvent.QUEUE_NAME)"));
            assertFalse(source.contains("emitInTransaction("));
        }
        assertTrue(Files.notExists(FLOW.resolve("infrastructure/queues/ExecutorCommandQueueFactory.java")));
        assertTrue(Files.notExists(FLOW.resolve("infrastructure/queues/ExecutorEventQueueFactory.java")));
    }

    @Test
    void defaultQueueAdapterRemainsInInfrastructure() throws IOException {
        Path defaultQueue = FLOW.resolve("infrastructure/queues");
        for (Path adapter : List.of(
            defaultQueue.resolve("DefaultDispatchQueue.java"),
            defaultQueue.resolve("PostgresQueueStore.java"),
            defaultQueue.resolve("PollingQueueSubscription.java"),
            defaultQueue.resolve(
                "entries/QueueMessageEntry.java"
            )
        )) {
            assertTrue(
                Files.isRegularFile(adapter),
                () -> "Missing Default Queue adapter: " + adapter
            );
        }

        String queue = Files.readString(
            defaultQueue.resolve("DefaultDispatchQueue.java")
        );
        String store = Files.readString(
            defaultQueue.resolve("PostgresQueueStore.java")
        );
        String entry = Files.readString(defaultQueue.resolve(
            "entries/QueueMessageEntry.java"
        ));
        assertTrue(
            queue.contains("implements DispatchQueue<T>")
                && queue.contains("Class<T> eventType")
                && queue.contains("emitInTransaction")
                && !queue.contains("event.dsl()"),
            "Default Queue must implement the Core seam and accept caller "
                + "transactions only through explicit publishing"
        );
        assertTrue(
            store.contains(".forUpdate()")
                && store.contains(".skipLocked()"),
            "Default Queue competition must remain database-backed"
        );
        assertTrue(
            store.contains(
                ".fetchOneInto(QueueMessageEntry.class)"
            )
                && !store.contains("QueuesRecord")
                && !entry.contains("QueuesRecord")
                && !entry.contains("fromRecord("),
            "Default Queue reads must map complete rows directly into "
                + "QueueMessageEntry"
        );
        assertTrue(
            Files.isRegularFile(Path.of(
                "../gen/src/main/java/org/flow/gen/flow/tables/"
                    + "QueuesTable.java"
            )),
            "Default Queue table must come from generated JOOQ sources"
        );
    }

    /**
     * Keeps generated Record conversion chains out of repositories and checks
     * Flow definition Entry mapping. Real PostgreSQL round trips verify the
     * complete Execution snapshot mapping without prescribing its query shape.
     *
     * @throws IOException when a repository source cannot be read
     */
    @Test
    void postgresTableReadsMapDirectlyIntoEntries() throws IOException {
        Path repositories = FLOW.resolve("infrastructure/repositories");
        List<String> invalid = new ArrayList<>();
        try (var paths = Files.walk(repositories)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        String source = Files.readString(path);
                        if (source.contains("fromRecord(")
                            || source.contains(".into(FlowsRecord.class)")
                            || source.contains(
                                ".into(FlowTasksRecord.class)"
                            )
                            || source.contains(
                                ".into(ExecutionsRecord.class)"
                            )
                            || source.contains(
                                ".into(TaskRunsRecord.class)"
                            )) {
                            invalid.add(path.toString());
                        }
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
        }

        String flows = Files.readString(FLOW.resolve(
            "infrastructure/repositories/flows/FlowRepositoryImpl.java"
        ));
        assertTrue(
            invalid.isEmpty()
                && flows.contains("fetchOneInto(FlowEntry.class)")
                && flows.contains("fetchInto(FlowEntry.class)")
                && flows.contains("fetchInto(FlowTaskEntry.class)"),
            () -> "PostgreSQL table reads must map directly into Entries: "
                + invalid
        );
    }

    @Test
    void databaseAdaptersKeepDomainConversionsInEntriesAndCodecsSibling()
        throws IOException {

        Path repositories = FLOW.resolve("infrastructure/repositories");
        List<String> invalidAdapters = new ArrayList<>();
        List<String> invalidEntries = new ArrayList<>();
        List<String> invalidCodecs = new ArrayList<>();
        try (var paths = Files.walk(repositories)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        String location = path.toString();
                        String source = Files.readString(path);
                        if (location.endsWith("Entry.java")) {
                            if (!location.contains("/entries/")
                                || LEGACY_ENTRY_CONVERSION_METHOD.matcher(
                                    source
                                ).find()
                                || !ENTRY_FROM_METHOD.matcher(source).find()
                                || !ENTRY_TO_METHOD.matcher(source).find()
                                || source.contains("JacksonMapper")
                                || source.contains("ObjectMapper")
                                || source.contains("JsonFactory")
                                || source.contains(
                                    "import org.paas.json.JsonObject;"
                                )) {
                                invalidEntries.add(location);
                            }
                        } else if (location.endsWith("Codec.java")) {
                            if (!location.contains("/codec/")
                                || location.contains("/entries/")
                                || !source.contains("public static ")) {
                                invalidCodecs.add(location);
                            }
                        } else if (DATABASE_CONVERSION_METHOD.matcher(
                            source
                        ).find()) {
                            invalidAdapters.add(location);
                        }
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
        }
        Path taskPropertiesCodec = repositories.resolve(
            "flows/codec/TaskPropertiesCodec.java"
        );
        String taskPropertiesSource = Files.readString(
            taskPropertiesCodec
        );

        assertTrue(
            invalidAdapters.isEmpty()
                && invalidEntries.isEmpty()
                && invalidCodecs.isEmpty()
                && Files.isRegularFile(taskPropertiesCodec)
                && taskPropertiesSource.contains(
                    "public static JsonObject encode(Task task)"
                )
                && taskPropertiesSource.contains("public static Task decode(")
                && taskPropertiesSource.contains(
                    "JacksonMapper.toPersistenceMap(task)"
                )
                && taskPropertiesSource.contains(
                    "JacksonMapper.convertPersistenceValue("
                ),
            () -> "Database conversions must use Entry from/to and sibling "
                + "static Codecs: adapters=" + invalidAdapters
                + ", entries=" + invalidEntries
                + ", codecs=" + invalidCodecs
        );
    }

    /**
     * Preserves Flow and Queue batching and rejects generated Record allocation
     * in batch writers. Real PostgreSQL tests verify Execution snapshot
     * atomicity and stale-write rejection without prescribing SQL syntax.
     *
     * @throws IOException when a repository or queue source cannot be read
     */
    @Test
    void postgresBatchWritesBuildOneValuesInsert() throws IOException {
        String flows = Files.readString(FLOW.resolve(
            "infrastructure/repositories/flows/FlowRepositoryImpl.java"
        ));
        String executions = Files.readString(FLOW.resolve(
            "infrastructure/repositories/executions/"
                + "ExecutionRepositoryImpl.java"
        ));
        String queue = Files.readString(FLOW.resolve(
            "infrastructure/queues/PostgresQueueStore.java"
        ));

        assertTrue(
            flows.contains("values(FlowTaskEntry.from(")
                && flows.contains("values.execute()")
                && !flows.contains("newRecord()")
                && !executions.contains("newRecord()")
                && queue.contains("values.values(")
                && queue.contains("values.execute()")
                && !queue.contains("newRecord()"),
            "PostgreSQL batch writes must concatenate VALUES rows and execute once"
        );
    }

    @Test
    void postgresAdaptersDoNotGenerateDomainAuditFacts() throws IOException {
        Path repositories = FLOW.resolve("infrastructure/repositories");
        List<String> invalid = new ArrayList<>();
        try (var paths = Files.walk(repositories)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        String source = Files.readString(path);
                        if (source.contains("PostgresAudit")
                            || source.contains(
                                "configuration().data(Session.class)"
                            )
                            || source.contains("System.currentTimeMillis()")
                            || source.contains("Instant.now()")
                            || source.contains("OffsetDateTime.now()")) {
                            invalid.add(path.toString());
                        }
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
        }
        assertTrue(
            invalid.isEmpty()
                && Files.notExists(repositories.resolve(
                    "shared/postgres/PostgresAudit.java"
                )),
            () -> "PostgreSQL adapters must not generate audit facts: "
                + invalid
        );
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
        String runVariables = Files.readString(runner.resolve(
            "RunVariables.java"
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
        String log = Files.readString(FLOW.resolve(
            "extensions/log/Log.java"
        ));
        String pause = Files.readString(FLOW.resolve(
            "extensions/flow/Pause.java"
        ));
        String parallel = Files.readString(FLOW.resolve(
            "extensions/flow/Parallel.java"
        ));
        String loop = Files.readString(FLOW.resolve(
            "extensions/flow/Loop.java"
        ));
        String loopUntil = Files.readString(FLOW.resolve(
            "extensions/flow/LoopUntil.java"
        ));
        String executorService = Files.readString(executor.resolve(
            "ExecutorService.java"
        ));
        String defaultExecutor = Files.readString(executor.resolve(
            "DefaultExecutor.java"
        ));
        String executorEventHandler = Files.readString(executor.resolve(
            "handlers/ExecutorEventHandler.java"
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
            runContext.contains(
                    "private Map<String, Object> variables;"
                )
                && runContext.contains(
                    "@lombok.Builder(builderClassName = \"Builder\")"
                )
                && runContext.contains("public TaskRunInfo taskRunInfo()")
                && runContext.contains("public FlowInfo flowInfo()")
                && !runContext.contains("public String executionId()")
                && !runContext.contains("public String taskRunId()")
                && runContext.contains("requiredString(\"execution.id\")")
                && runContext.contains("requiredString(\"taskRun.id\")")
                && runContext.contains("requiredMap(\"taskRun.inputs\")")
                && !runContext.contains("private WorkerTask")
                && !runContext.contains("private TaskRun")
                && !runContext.contains("private Execution")
                && !runContext.contains("private String executionId")
                && !runContext.contains("private String taskRunId")
                && !runContext.contains("private String parentTaskRunId")
                && !runContext.contains("org.paas.session.Session")
                && !runContext.contains("BeanContext")
                && !runContext.contains("getBean(")
                && !runContext.contains("DSLContext")
                && !runContext.contains("dsl()")
                && runVariables.contains("public static Builder builder()")
                && runVariables.contains("private Flow flow;")
                && runVariables.contains("private Execution execution;")
                && runVariables.contains("private Task task;")
                && runVariables.contains("private TaskRun taskRun;")
                && !runVariables.contains(
                    "public Builder parentTaskRunId("
                )
                && !runVariables.contains("public Builder inputs(")
                && !runVariables.contains("public Builder outputs(")
                && !runVariables.contains("public Builder flowVariables(")
                && !runVariables.contains("public Builder executionOutputs(")
                && runVariables.contains(
                    "ImmutableMap.Builder<String, Object> builder"
                )
                && runVariables.contains(
                    "builder.put(\"flow\", RunVariables.of(flow))"
                )
                && runVariables.contains(
                    "Optional.ofNullable(flow.versionOrNull())"
                )
                && runVariables.contains(
                    "of(final Task task)"
                )
                && runVariables.contains(
                    "of(final TaskRun taskRun)"
                )
                && runVariables.contains(
                    "of(final Execution execution)"
                )
                && runVariables.contains("\"flow\"")
                && runVariables.contains("\"inputs\"")
                && runVariables.contains("\"outputs\"")
                && runVariables.contains("\"vars\"")
                && runVariables.contains("\"taskRun\"")
                && runVariables.contains("\"execution\"")
                && runVariables.contains("\"parent\"")
                && runVariables.contains("builder.put(\"parents\", parents)")
                && !dispatcher.contains("BeanContext")
                && !dispatcher.contains("DSLContext")
                && !dispatcher.contains("Session")
                && dispatcher.contains("RunContext.builder()")
                && dispatcher.contains(
                    ".variables(workerTask.variables())"
                ),
            "RunVariables must own projection while RunContext only exposes "
                + "one RunnableTask invocation snapshot"
        );
        assertTrue(
            dispatcher.contains("workerTask.runnableTask().run(context)")
                && workerTask.contains(
                    "public record WorkerTask("
                )
                && !workerTask.contains("private final Task task;")
                && !dispatcher.contains("PluginLoader")
                && !dispatcher.contains("WorkerTaskHandler")
                && !workerResult.contains("State.Type.PAUSED")
                && !orchestrationTask.contains("RunResult run("),
            "Worker must directly run RunnableTask and cannot return PAUSED"
        );
        assertTrue(
            log.contains("implements RunnableTask")
                && log.contains("RunResult run(RunContext context)")
                && pause.contains("implements OrchestrationTask")
                && parallel.contains("implements OrchestrationTask")
                && loop.contains("implements OrchestrationTask")
                && loopUntil.contains("implements OrchestrationTask")
                && Files.notExists(taskDomain.resolve("LoopTask.java"))
                && executorService.contains(
                    "public ExecutorContext process(ExecutorContext context)"
                )
                && executorService.contains("handleOrchestration(")
                && executorService.contains("runnable == orchestration"),
            "Concrete Tasks must declare one capability and Executor must "
                + "handle orchestration directly"
        );
        assertTrue(
            defaultExecutor.contains(
                "commandHandler.handle(command)"
            )
                && defaultExecutor.contains(
                    "eventHandler.handle(event)"
                )
                && !defaultExecutor.contains("executorService")
                && executorEventHandler.contains(
                    "executorService.process(context)"
                )
                && executorEventHandler.contains(
                    "eventQueue.emit("
                )
                && !executorEventHandler.contains("dispatchBranch("),
            "DefaultExecutor must only route Queue events while "
                + "ExecutorEventHandler submits Worker effects and "
                + "ExecutorService owns OrchestrationTask state progression"
        );
    }

    @Test
    void flowIsTheOnlyConcreteDefinitionAggregate() throws IOException {
        Path flows = CORE.resolve("domains/flows");
        Path flowPath = flows.resolve("Flow.java");
        Path executionPath = CORE.resolve("domains/executions/Execution.java");

        assertTrue(Files.isRegularFile(flowPath));
        assertTrue(Files.isRegularFile(executionPath));
        assertTrue(Files.notExists(flows.resolve("FlowWithDraft.java")));

        String flow = Files.readString(flowPath);
        String execution = Files.readString(executionPath);
        assertTrue(
            flow.contains(
                "public class Flow extends AbstractFlow"
            )
                && !flow.contains("Lockable")
                && flow.contains("String source;")
                && !flow.contains("lockVersion")
                && flow.contains("public static Flow create(")
                && flow.contains("public static Flow deploy(")
                && flow.contains("public static Flow rehydrate(")
                && flow.contains("public void initialize(")
                && !execution.contains("Lockable")
                && !execution.contains("lockVersion"),
            "Flow must remain the only concrete draft and deployed "
                + "definition aggregate"
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
            state.contains("private Type current;")
                && state.contains("private List<History> history;")
                && state.contains("private State(")
                && !state.contains("setCurrent(")
                && !state.contains("setHistory(")
                && state.contains("public Type current()")
                && state.contains("public List<History> history()")
                && execution.contains("State state;")
                && taskRun.contains("private State state;")
                && workerResult.contains(
                    "public record WorkerTaskResult("
                )
                && workerResult.contains(
                    "State.Type targetState,"
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
        inspectRecordFactories(invalid);
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
    void domainEntityIdsRemainPlainStrings() throws IOException {
        Path domains = CORE.resolve("domains");
        List<Path> removedWrappers = List.of(
            domains.resolve("Identity.java"),
            domains.resolve("executions/ExecutionId.java"),
            domains.resolve("executions/TaskRunId.java"),
            domains.resolve("tasks/TaskId.java")
        );
        assertTrue(
            removedWrappers.stream().noneMatch(Files::exists),
            () -> "Typed id wrappers must remain removed: "
                + removedWrappers
        );

        Path flowId = domains.resolve("flows/FlowId.java");
        assertTrue(
            Files.isRegularFile(flowId),
            "FlowId is the approved Flow Repository business selector"
        );
        String flowIdSource = Files.readString(flowId);
        assertTrue(
            flowIdSource.contains("record FlowId")
                && flowIdSource.contains("String companyId")
                && flowIdSource.contains("String key")
                && flowIdSource.contains("Long version"),
            "FlowId must contain companyId, key and nullable version"
        );

        List<String> parallelIdentityInterfaces = new ArrayList<>();
        try (var paths = Files.walk(domains)) {
            for (Path path : paths
                .filter(file -> file.toString().endsWith(".java"))
                .toList()) {

                String source = Files.readString(path);
                if (source.contains("recordId(")
                    || source.contains("identifier(")) {
                    parallelIdentityInterfaces.add(path.toString());
                }
            }
        }
        assertTrue(
            parallelIdentityInterfaces.isEmpty(),
            () -> "Domain ids must use only String id(): "
                + parallelIdentityInterfaces
        );

        String baseDomain = Files.readString(
            domains.resolve("BaseDomain.java")
        );
        assertTrue(
            baseDomain.contains("String id;")
                && baseDomain.contains("public final String id()"),
            "BaseDomain must own and expose the stable String entity id "
                + "without final property fields"
        );
    }

    @Test
    void serializationCentralizesJacksonAndKeepsYamlParsingNeutral()
        throws IOException {

        Path serialization = CORE.resolve("serializers");
        Path yamlParser = serialization.resolve("YamlParser.java");
        Path jacksonMapper = serialization.resolve("JacksonMapper.java");
        assertTrue(
            Files.isRegularFile(yamlParser)
                && Files.isRegularFile(jacksonMapper)
                && Files.notExists(serialization.resolve(
                    "FlowDefinitionDeserializer.java"
                )),
            "Serialization must expose one mapper and generic YAML parser"
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
        String pluginModuleSource = Files.readString(CORE.resolve(
            "plugins/PluginModule.java"
        ));
        assertTrue(
            mapperSource.contains(
                "com.fasterxml.jackson.databind.ObjectMapper"
            )
                && mapperSource.contains(
                    "com.fasterxml.jackson.dataformat.yaml.YAMLFactory"
                )
                && mapperSource.contains("@Context")
                && mapperSource.contains("PluginModule")
                && mapperSource.contains(
                    "public static <T> T convertPersistenceValue("
                )
                && mapperSource.contains(
                    "public static Map<String, Object> toPersistenceMap("
                )
                && !mapperSource.contains("org.yaml.snakeyaml"),
            "JacksonMapper must own strict JSON/YAML mapper configuration"
        );
        assertTrue(
            source.contains("JacksonMapper")
                && source.contains("public static Map<String, Object> parse")
                && source.contains("public static <T> T parse")
                && source.contains("JacksonMapper.yamlMapper()")
                && source.contains("private YamlParser()")
                && !source.contains("@Singleton")
                && source.contains("parse(String source, Class<T> type)")
                && !source.contains("YAMLFactory")
                && !source.contains("PluginDeserializationContext")
                && !source.contains(
                    "org.cses.flow.core.domains.flows.Flow"
                )
                && !source.contains("org.cses.flow.extensions"),
            "YamlParser must remain generic and must not interpret Flow or "
                + "extension types"
        );
        assertTrue(
            mapperSource.contains("pluginModule.sourceDefinitions()")
                && pluginModuleSource.contains("sourceDefinitions()"),
            "The registered YAML plugin module must own source-specific "
                + "Task binding without caller attributes"
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

    /**
     * 检查插件绑定仍由注册表选择精确类，并按 Task/Input 目标能力约束解析。
     * @throws IOException 当被检查的生产源码无法读取时抛出
     */
    @Test
    void taskPluginsUseCompileTimeDiscoveryAndExactClassTypes()
        throws IOException {

        Path plugins = CORE.resolve("plugins");
        Path pluginSpi = plugins.resolve("Plugin.java");
        Path pluginAnnotation = plugins.resolve("annotations/Plugin.java");
        Path exampleAnnotation = plugins.resolve("annotations/Example.java");
        Path pluginExample = plugins.resolve("PluginExample.java");
        Path pluginDeserializer = plugins.resolve(
            "PluginDeserializer.java"
        );
        Path pluginModule = plugins.resolve("PluginModule.java");
        Path extensions = FLOW.resolve("extensions");
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
                && Files.isRegularFile(pluginExample)
                && Files.isRegularFile(registeredPlugin)
                && Files.isRegularFile(pluginSchema)
                && Files.isRegularFile(pluginAnnotation)
                && Files.isRegularFile(exampleAnnotation)
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
                && Files.notExists(plugins.resolve("PluginSource.java"))
                && Files.notExists(plugins.resolve("CorePluginSource.java"))
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
        String pluginModuleSource = Files.readString(pluginModule);
        assertTrue(
            deserializerSource.contains(
                "registry.resolve(type, expectedType)"
            )
                && deserializerSource.contains("readTreeAsValue")
                && !deserializerSource.contains("ModelValidator")
                && !deserializerSource.contains(
                    "PluginDefinitionPreparer"
                )
                && pluginModuleSource.contains(
                    "new PluginDeserializer<>(registry, sourceDefinition)"
                )
                && !deserializerSource.contains("switch")
                && !deserializerSource.contains("Log")
                && !deserializerSource.contains("Pause"),
            "Plugin deserialization must resolve without concrete branches"
        );

        String registrySource = Files.readString(registry);
        String defaultRegistrySource = Files.readString(defaultRegistry);
        String pluginSpiSource = Files.readString(pluginSpi);
        String pluginMetadataSource = Files.readString(pluginMetadata);
        String registeredPluginSource = Files.readString(registeredPlugin);
        String annotationSource = Files.readString(pluginAnnotation);
        String exampleAnnotationSource = Files.readString(exampleAnnotation);
        assertTrue(
            registrySource.contains("interface PluginRegistry")
                && registrySource.contains("List<RegisteredPlugin>")
                && registrySource.contains("findMetadata")
                && !registrySource.contains("register(")
                && defaultRegistrySource.contains("Collection<")
                && defaultRegistrySource.contains("@Context")
                && defaultRegistrySource.contains("putIfAbsent")
                && defaultRegistrySource.contains("getCanonicalName()")
                && defaultRegistrySource.contains("getPackageName()")
                && !defaultRegistrySource.contains("Locale.ROOT")
                && !defaultRegistrySource.contains("ServiceLoader"),
            "Plugins must be registered by exact canonical class name"
        );
        assertTrue(
            pluginSpiSource.contains("default String getType()")
                && pluginSpiSource.contains("getCanonicalName()")
                && pluginMetadataSource.contains("packageName()")
                && registeredPluginSource.contains("String packageName")
                && annotationSource.contains("@Bean")
                && annotationSource.contains("@DefaultScope")
                && annotationSource.contains("@Introspected")
                && annotationSource.contains(
                    "Introspected.AccessKind.FIELD"
                )
                && annotationSource.contains(
                    "Introspected.Visibility.ANY"
                )
                && !annotationSource.contains("String source()")
                && annotationSource.contains("String title()")
                && annotationSource.contains("String description()")
                && annotationSource.contains("Example[] examples()")
                && pluginMetadataSource.contains(
                    "List<PluginExample> examples"
                )
                && exampleAnnotationSource.contains("@Target({})")
                && exampleAnnotationSource.contains("String[] code()")
                && exampleAnnotationSource.contains(
                    "boolean full() default false"
                ),
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

    private static boolean isSharedServiceProtocol(Path path) {
        return path.getParent().endsWith(Path.of("core/services"))
            && Set.of(
                "Command.java",
                "CommandContext.java",
                "CommandExecutor.java",
                "CommandHandler.java",
                "CommandHandlerRegistry.java"
            ).contains(path.getFileName().toString());
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
                if (RECORD_DECLARATION.matcher(source).find()
                    && !isAllowedDomainRecord(path)) {
                    invalid.add(path + " declares record in a class-only area");
                }
            }
        }
    }

    private static void inspectRecordFactories(
        List<String> invalid
    ) throws IOException {
        Map<String, List<Path>> recordFiles = new LinkedHashMap<>();
        List<Path> javaFiles = new ArrayList<>();
        for (Path sourceRoot : List.of(MAIN_JAVA, TEST_JAVA)) {
            try (var paths = Files.walk(sourceRoot)) {
                for (Path path : paths
                    .filter(file -> file.toString().endsWith(".java"))
                    .toList()) {
                    javaFiles.add(path);
                    String source = Files.readString(path);
                    var declarations = RECORD_DECLARATION_NAME.matcher(source);
                    while (declarations.find()) {
                        String name = declarations.group(1);
                        recordFiles.computeIfAbsent(
                            name,
                            ignored -> new ArrayList<>()
                        ).add(path);
                        if (!source.contains("from(")) {
                            invalid.add(
                                path + " record " + name
                                    + " must provide from(...)"
                            );
                        }
                    }
                }
            }
        }

        for (Map.Entry<String, List<Path>> entry : recordFiles.entrySet()) {
            Pattern constructor = Pattern.compile(
                "\\bnew\\s+" + Pattern.quote(entry.getKey())
                    + "(?:\\s*<[^\\n;{}()]*>)?\\s*\\("
            );
            for (Path path : javaFiles) {
                if (entry.getValue().contains(path)) {
                    continue;
                }
                if (constructor.matcher(Files.readString(path)).find()) {
                    invalid.add(
                        path + " directly constructs record "
                            + entry.getKey() + "; use from(...)"
                    );
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

    private static boolean isAllowedDomainRecord(Path path) {
        return path.endsWith(Path.of(
                "org/cses/flow/core/domains/tasks/RunResult.java"
            ))
            || path.endsWith(Path.of(
                "org/cses/flow/core/domains/flows/FlowId.java"
            ));
    }
}
