package org.cses.flow.core.services.executions;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.plugins.PluginRegistry;
import org.cses.flow.core.services.flows.FlowService;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.cses.flow.core.repositories.executions.ExecutionRepository;
import org.cses.flow.executor.commands.Create;
import org.cses.flow.infrastructure.jooq.PostgresJooqTestAdapter;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.cses.flow.infrastructure.queues.pulsar.PulsarTestEnvironment;
import org.paas.session.Session;
import org.paas.session.User;
import org.paas.common.util.StringUtil;
import org.x9.jooq.JOOQ;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public class WorkflowUcFixture implements AutoCloseable {

    private static Map<String, Object> PROPERTIES = Map.of(
        "datasources.default.enabled", false,
        "flyway.datasources.default.enabled", false,
        "micronaut.config-client.enabled", false,
        "consul.client.registration.enabled", false,
        "consul.client.watch.service.enabled", false,
        "grpc.server.enabled", false,
        "thrift.server.enabled", false,
        "jooq.send-event", false
    );

    private ApplicationContext context;
    private FlowService flowService;
    private ExecutionService executionService;
    private Session<User> session;
    private PostgresJooqTestAdapter jooq;
    private boolean cleanupOnClose;
    private Map<String, Object> properties;
    private Object[] singletons;
    private Set<String> companyIds = new LinkedHashSet<>();

    /**
     * 为一个测试用户范围启动真实数据库与 Pulsar 公开服务，启动失败时关闭已创建的上下文。
     * @param jooq 本夹具拥有的专属 PostgreSQL 适配器
     * @param companyId 重启场景的稳定租户编号；空时创建新测试租户
     * @param cleanupOnClose 是否允许最终清理自己的测试数据
     * @param additionalProperties 场景所需的额外 Micronaut 属性；空时使用默认属性
     * @param singletons 技术测试明确提供的非队列协作者；空时不注册
     */
    private WorkflowUcFixture(
        PostgresJooqTestAdapter jooq,
        String companyId,
        boolean cleanupOnClose,
        Map<String, Object> additionalProperties,
        Object... singletons
    ) {
        this.jooq = jooq;
        this.cleanupOnClose = cleanupOnClose;
        Map<String, Object> mergedProperties =
            new java.util.LinkedHashMap<>(PROPERTIES);
        mergedProperties.putAll(PulsarTestEnvironment.properties());
        if (additionalProperties != null) {
            mergedProperties.putAll(additionalProperties);
        }
        this.properties = Map.copyOf(mergedProperties);
        this.singletons = singletons == null
            ? new Object[0]
            : singletons.clone();
        this.session = newSession(companyId == null
            ? "uc-" + StringUtil.newId()
            : companyId);
        this.companyIds.add(session.getCompanyId());
        try {
            startServer();
        } catch (RuntimeException | Error failure) {
            closeServer();
            throw failure;
        }
    }

    public static WorkflowUcFixture open() {
        return open(true, null, Map.of());
    }

    public static WorkflowUcFixture openLeavingWaiting(
        String companyId
    ) {
        return open(false, companyId, Map.of());
    }

    public static WorkflowUcFixture openWithSingletons(
        Object... singletons
    ) {
        return open(true, null, Map.of(), singletons);
    }

    public static WorkflowUcFixture openWithProperties(
        Map<String, Object> properties
    ) {
        return open(true, null, properties);
    }

    /**
     * 以标准具名数据源装配生产 JOOQ，供长时压力使用连接池；普通 UC 保持原直连适配器。
     * @param properties 明确指向专属环境的数据源和 Broker 属性
     * @return 调用方负责关闭的真实服务夹具
     */
    public static WorkflowUcFixture openWithRuntimeDataSource(Map<String, Object> properties) {
        Map<String, Object> runtime = new java.util.LinkedHashMap<>(properties);
        runtime.put("flow.test.runtime-datasource", true);
        return open(true, null, runtime);
    }

    /**
     * 创建测试拥有的连接池和公开服务夹具，初始化失败时也释放连接池。
     * @param cleanupOnClose 是否允许最终清理本次业务数据
     * @param companyId 稳定测试租户；空时创建独立租户
     * @param properties 场景额外配置，不修改输入
     * @param singletons 显式非队列协作者，不修改输入数组
     * @return 调用方必须关闭的独立夹具
     * @throws RuntimeException 真实环境或上下文无法初始化时抛出
     */
    private static WorkflowUcFixture open(
        boolean cleanupOnClose,
        String companyId,
        Map<String, Object> properties,
        Object... singletons
    ) {
        PostgresJooqTestAdapter jooq =
            PostgresJooqTestAdapter.fromEnvironment();
        try {
            return new WorkflowUcFixture(jooq, companyId, cleanupOnClose, properties, singletons);
        } catch (RuntimeException | Error failure) {
            jooq.close();
            throw failure;
        }
    }

    /**
     * 返回当前真实应用上下文中的组件，供 HTTP 和触发器边界测试调用。
     * @param type 需要获取的生产或明确测试组件类型，不能为 null
     * @param <T> 组件类型
     * @return 由当前上下文管理的组件，调用方不单独销毁
     */
    public <T> T bean(Class<T> type) { return context.getBean(type); }

    /**
     * 查询具有明确名称的真实组件，用于核对压力运行链路的数据源归属。
     * @param type 组件接口
     * @param name Micronaut Bean 限定名
     * @param <T> 组件类型
     * @return 当前上下文管理的具名组件
     */
    public <T> T bean(Class<T> type, String name) { return context.getBean(type, Qualifiers.byName(name)); }

    public FlowService flowService() {
        return flowService;
    }

    public ExecutionService executionService() {
        return executionService;
    }

    /**
     * 返回真实上下文中的 Core 触发启动入口，供独立技术边界验证使用。
     * @return 当前上下文管理的发布器；调用方不负责关闭
     */
    public org.cses.flow.scheduler.TriggerExecutionPublisher triggerExecutionPublisher() {
        return context.getBean(org.cses.flow.scheduler.TriggerExecutionPublisher.class);
    }

    public PluginRegistry pluginRegistry() {
        return context.getBean(PluginRegistry.class);
    }

    public Session<User> session() {
        return session;
    }

    /**
     * Closes the current server and starts a new server backed only by the
     * persisted PostgreSQL state and the same durable Pulsar subscriptions.
     */
    public void restartServer() {
        closeServer();
        startServer();
    }

    /**
     * 使用具名 Flow 数据源查询本测试租户的运行实例数量。
     * @return 当前已经持久化的实例总数
     */
    public long executionCount() {
        ExecutionRepository repository =
            context.getBean(ExecutionRepository.class);
        JOOQ jooq = context.getBean(JOOQ.class, Qualifiers.byName(FlowDatabase.DATA_SOURCE_NAME));
        return jooq.get(dsl -> repository.count(
            dsl,
            session.getCompanyId()
        ));
    }

    public Flow deploy(String yaml) {
        Flow draft = flowService.save(
            session,
            PublishFlowCommand.from(yaml)
        );
        return flowService.save(
            session,
            PublishFlowCommand.from(draft.key(), false)
        );
    }

    /**
     * Starts a Flow and waits until its asynchronous first drive reaches an
     * observable stable state. Tests for Queue acceptance use create directly.
     */
    public Execution startAndAwait(Flow flow) {
        return startAndAwait(session, flow);
    }

    public Execution startAndAwait(
        Session<User> startSession,
        Flow flow
    ) {
        Execution accepted = startCreated(startSession, flow);
        return awaitStable(startSession, accepted.id());
    }

    /**
     * Publishes a Create command and waits only until its consumer has
     * materialized the accepted stable Execution identity.
     */
    public Execution startCreated(Flow flow) {
        return startCreated(session, flow);
    }

    public Execution startCreated(
        Session<User> startSession,
        Flow flow
    ) {
        Create accepted = executionService.create(
            startSession,
            flow.key(),
            Optional.empty(),
            Map.of()
        );
        return awaitCreated(
            startSession,
            accepted.getExecutionId()
        );
    }

    private Execution awaitCreated(
        Session<User> querySession,
        String executionId
    ) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Optional<Execution> created = executionService.execution(
                querySession,
                executionId
            );
            if (created.isPresent()) {
                return created.orElseThrow();
            }
            awaitChangeSignal(deadline);
        }
        throw new IllegalStateException(
            "Create command did not materialize Execution: " + executionId
        );
    }

    public Execution awaitStable(Execution accepted) {
        return awaitStable(session, accepted.id());
    }

    public Execution awaitStable(String executionId) {
        return awaitStable(session, executionId);
    }

    public Execution awaitStable(
        Session<User> querySession,
        String executionId
    ) {
        return awaitExecution(
            querySession,
            executionId,
            execution -> execution.isTerminal()
                || execution.state().isPaused()
        );
    }

    public Execution awaitExecution(
        Session<User> querySession,
        String executionId,
        Predicate<Execution> expected
    ) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Execution observed = null;
        while (System.nanoTime() < deadline) {
            Optional<Execution> current = executionService.execution(
                querySession,
                executionId
            );
            if (current.isPresent()) {
                observed = current.orElseThrow();
                if (expected.test(observed)) {
                    return observed;
                }
            }
            awaitChangeSignal(deadline);
        }
        throw new IllegalStateException(
            "Execution did not reach the expected state: "
                + executionId
                + ", last state="
                + (observed == null ? "missing" : observed.state().current())
        );
    }

    public PausedTaskRunRef waiting(Execution execution) {
        return waitingForExecution(execution.id());
    }

    public PausedTaskRunRef waitingForExecution(String executionId) {
        return waitingForExecution(session, executionId);
    }

    public PausedTaskRunRef waitingForExecution(
        Session<User> querySession,
        String executionId
    ) {
        awaitExecution(
            querySession,
            executionId,
            execution -> execution.state().isPaused()
        );
        List<PausedTaskRunRef> waiting = pausedTaskRuns(querySession).stream()
            .filter(task -> task.executionId().equals(executionId))
            .toList();
        if (waiting.size() != 1) {
            throw new IllegalStateException(
                "Expected one PAUSED TaskRun for Execution "
                    + executionId
                    + " but found "
                    + waiting.size()
            );
        }
        return waiting.getFirst();
    }

    public PausedTaskRunRef waitingForOutput(
        String executionId,
        String output
    ) {
        return waitingForOutput(session, executionId, output);
    }

    public PausedTaskRunRef waitingForOutput(
        Session<User> querySession,
        String executionId,
        String output
    ) {
        awaitExecution(
            querySession,
            executionId,
            execution -> execution.state().isPaused()
        );
        return pausedTaskRuns(querySession).stream()
            .filter(task -> task.executionId().equals(executionId))
            .filter(task -> declaresOutput(querySession, task, output))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No PAUSED TaskRun for Execution "
                    + executionId
                    + " and output "
                    + output
            ));
    }

    public List<PausedTaskRunRef> pausedTaskRuns() {
        return pausedTaskRuns(session);
    }

    public List<PausedTaskRunRef> pausedTaskRuns(
        Session<User> querySession
    ) {
        return executionService.executions(querySession).stream()
            .flatMap(execution -> execution.pausedTaskRuns().stream()
                .map(taskRun -> PausedTaskRunRef.from(
                    execution.id(),
                    taskRun.id()
                )))
            .toList();
    }

    private boolean declaresOutput(
        Session<User> querySession,
        PausedTaskRunRef pausedTaskRun,
        String output
    ) {
        Execution execution = executionService.execution(
            querySession,
            pausedTaskRun.executionId()
        ).orElseThrow(() -> new IllegalStateException(
            "Paused TaskRun references a missing Execution: "
                + pausedTaskRun.taskRunId()
        ));
        Flow flow = flowService.flow(
            querySession,
            execution.flowKey(),
            execution.flowVersion()
        ).orElseThrow(() -> new IllegalStateException(
            "Execution references a missing Flow reversion: "
                + execution.id()
        ));
        String taskId = execution.requireTaskRun(
            pausedTaskRun.taskRunId()
        ).taskId();
        return flow.findTask(taskId)
            .orElseThrow(() -> new IllegalStateException(
                "TaskRun references a missing Task: " + taskId
            ))
            .declaresOutput(output);
    }

    public Execution resume(
        PausedTaskRunRef pausedTaskRun,
        Map<String, ?> outputs
    ) {
        return resume(session, pausedTaskRun, outputs);
    }

    /**
     * 提交退回并查询新实例到达所选片段的 Pause。
     * @param pausedTaskRun 公开查询取得的原实例及当前 Pause
     * @param targetTaskRunId 当前有效历史目标编号
     * @param reason 非空退回原因
     * @return 受理时原快照及已到达 Pause 的新实例
     */
    public RewindAttempt rewind(
        PausedTaskRunRef pausedTaskRun,
        String targetTaskRunId,
        String reason
    ) {
        RewindResult accepted = executionService.rewind(
            session,
            pausedTaskRun.executionId(),
            pausedTaskRun.taskRunId(),
            targetTaskRunId,
            reason
        );
        Execution rewound = awaitExecution(
            session,
            accepted.executionId(),
            execution -> execution.state().isPaused()
                && execution.generation().current()
                    .filter(current -> current.sourceTaskRunId()
                        .filter(pausedTaskRun.taskRunId()::equals)
                        .isPresent())
                    .filter(current -> current.targetTaskRunId()
                        .filter(targetTaskRunId::equals)
                        .isPresent())
                    .filter(current -> current.reason().equals(reason))
                    .isPresent()
        );
        return RewindAttempt.from(accepted, rewound);
    }

    /**
     * 将普通技术测试的持久化命令交给真实消费入口；不用于替代 UC 公开操作。
     * @param command 重投的同一命令或旧回调
     */
    public void deliverCommand(org.cses.flow.executor.commands.ExecutionCommand command) {
        context.getBean(org.cses.flow.executor.handlers.ExecutionCommandEventHandler.class).handle(command);
    }

    /**
     * 将技术重投场景的既有事件发往真实 Broker，不直接调用消费处理器。
     * @param eventType 已声明 FlowQueue 的事件根类型
     * @param event 稳定身份的原事件；不得为 null
     * @param <E> 队列事件协议类型
     */
    @SuppressWarnings("unchecked")
    public <E extends org.cses.flow.queues.event.Event> void emitEvent(Class<E> eventType, E event) {
        org.cses.flow.queues.Queue<E> queue = context.getBean(
            org.cses.flow.queues.Queue.class, Qualifiers.byTypeArguments(eventType));
        queue.emit(event);
    }

    /**
     * 通过真实 Pulsar 发布带最早投递时间的技术测试消息，不直接调用消费者。
     * @param eventType 已声明队列的协议类型
     * @param event 原消息身份，不允许为 null
     * @param deliverAt 非负 Unix 毫秒时间
     * @param <E> 队列协议类型
     */
    @SuppressWarnings("unchecked")
    public <E extends org.cses.flow.queues.event.Event> void emitEventAt(Class<E> eventType, E event, long deliverAt) {
        org.cses.flow.infrastructure.queues.pulsar.PulsarQueue<E> queue = context.getBean(
            org.cses.flow.infrastructure.queues.pulsar.PulsarQueue.class, Qualifiers.byTypeArguments(eventType));
        queue.emitAt(event, deliverAt);
    }

    public Execution cancel(String executionId) {
        return cancel(session, executionId);
    }

    public Execution cancel(
        Session<User> cancelSession,
        String executionId
    ) {
        Execution accepted = executionService.cancel(
            cancelSession,
            executionId
        );
        return awaitExecution(
            cancelSession,
            accepted.id(),
            Execution::isTerminal
        );
    }

    public Execution resume(
        Session<User> resumeSession,
        PausedTaskRunRef pausedTaskRun,
        Map<String, ?> outputs
    ) {
        Execution accepted = executionService.resume(
            resumeSession,
            pausedTaskRun.executionId(),
            pausedTaskRun.taskRunId(),
            outputs
        );
        awaitExecution(
            resumeSession,
            accepted.id(),
            execution -> execution.isTerminal()
                || !execution.requireTaskRun(pausedTaskRun.taskRunId())
                    .state()
                    .is(State.Type.PAUSED)
        );
        return awaitStable(resumeSession, accepted.id());
    }

    public TaskRun taskRun(
        Session<User> querySession,
        PausedTaskRunRef pausedTaskRun
    ) {
        return executionService.execution(
            querySession,
            pausedTaskRun.executionId()
        ).orElseThrow(() -> new IllegalStateException(
            "Execution does not exist: " + pausedTaskRun.executionId()
        )).requireTaskRun(pausedTaskRun.taskRunId());
    }

    public TaskRun taskRun(PausedTaskRunRef pausedTaskRun) {
        return taskRun(session, pausedTaskRun);
    }

    public Execution resumeAfterRestart(
        String executionId,
        Map<String, Object> outputs
    ) {
        restartServer();
        PausedTaskRunRef task = waitingForExecution(executionId);
        return resume(task, outputs);
    }

    public Execution resumeAfterRestart(
        String executionId,
        String output,
        Map<String, Object> outputs
    ) {
        restartServer();
        PausedTaskRunRef task = waitingForOutput(executionId, output);
        return resume(task, outputs);
    }

    public static String pauseYaml(
        String key,
        String description,
        boolean trailingLogTask
    ) {
        String trailing = trailingLogTask
            ? """
              - key: record-result
                type: org.cses.flow.extensions.log.Log
                message: "test step"
            """
            : "";
        return """
            key: %s
            description: %s
            tasks:
              - key: wait-confirmation
                type: org.cses.flow.extensions.flow.Pause
                onPause:
                  key: create-confirmation
                  type: org.cses.flow.extensions.log.Log
                  message: "test step"
                onResume:
                  - key: decision
                    type: STRING
            %s
            """.formatted(key, description, trailing);
    }

    /**
     * 先关闭真实应用，再按显式开关清理专属 Broker 和本租户数据，最后释放测试连接池。
     * 即使业务清理失败也关闭连接池，物理资源清理不代替场景终态断言。
     */
    @Override
    public void close() {
        try {
            closeServer();
        } finally {
            try {
                if (cleanupOnClose && jooq.cleanupEnabled()) {
                    try {
                        PulsarTestEnvironment.clearExecutorBacklogs();
                    } finally {
                        companyIds.forEach(jooq::removeTenant);
                    }
                }
            } finally {
                jooq.close();
            }
        }
    }

    /** 使用本夹具的真实组件和显式环境参数启动应用，HTTP专项避免依赖Jar默认配置冲突。 */
    private void startServer() {
        var builder = ApplicationContext.builder()
            .properties(properties);
        if (Boolean.TRUE.equals(properties.get("flow.webhook.http-test"))) builder.enableDefaultPropertySources(false);
        if (singletons.length > 0) {
            builder.singletons(singletons);
        }
        context = builder.build();
        if (!Boolean.TRUE.equals(properties.get("flow.test.runtime-datasource"))) {
            context.registerSingleton(
                JOOQ.class,
                jooq,
                Qualifiers.byName(FlowDatabase.DATA_SOURCE_NAME)
            );
        }
        context.registerSingleton(
            org.cses.flow.infrastructure.datapilot.FlowDataSourceEngine.class,
            jooq.engine(), null, false
        );
        context.start();
        flowService = context.getBean(FlowService.class);
        executionService = context.getBean(ExecutionService.class);
    }

    private void closeServer() {
        if (context != null) {
            context.close();
            context = null;
            flowService = null;
            executionService = null;
        }
    }

    public Session<User> sessionFor(String tenantLabel) {
        String companyId = session.getCompanyId() + "-" + tenantLabel;
        companyIds.add(companyId);
        return newSession(companyId);
    }

    public Session<User> sessionForExactCompany(String companyId) {
        companyIds.add(companyId);
        return newSession(companyId);
    }

    private static Session<User> newSession(String companyId) {
        User user = new User();
        user.setId("user-1");
        user.setName("UC Test User");
        user.setCompanyId(companyId);
        Session<User> session = new Session<>();
        session.setUser(user);
        return session;
    }

    private static void awaitChangeSignal(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        try {
            new CountDownLatch(1).await(
                Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)),
                TimeUnit.NANOSECONDS
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Interrupted while awaiting Execution state",
                exception
            );
        }
    }

    public record PausedTaskRunRef(
        String executionId,
        String taskRunId
    ) {

        public static PausedTaskRunRef from(
            String executionId,
            String taskRunId
        ) {
            return new PausedTaskRunRef(executionId, taskRunId);
        }
    }

    public record RewindAttempt(
        RewindResult accepted,
        Execution rewound
    ) {

        public RewindAttempt {
            accepted = Objects.requireNonNull(
                accepted,
                "Accepted rewind result"
            );
            rewound = Objects.requireNonNull(
                rewound,
                "Rewound Execution"
            ).copy();
        }

        public static RewindAttempt from(
            RewindResult accepted,
            Execution rewound
        ) {
            return new RewindAttempt(accepted, rewound);
        }

        @Override
        public Execution rewound() {
            return rewound.copy();
        }
    }
}
