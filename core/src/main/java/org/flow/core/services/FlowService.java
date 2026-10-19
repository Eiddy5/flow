package org.flow.core.services;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;

import org.flow.core.contexts.FlowContext;
import org.flow.core.exceptions.FlowProcessingException;
import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.plugin.api.Plugin;
import org.flow.core.models.executions.Execution;
import org.flow.core.models.flows.*;
import org.flow.core.models.flows.check.Check;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.ExecutableTask;
import org.flow.core.models.tasks.RunnableTask;
import org.flow.core.models.tasks.Task;
import org.flow.core.models.topologies.FlowTopology;
import org.flow.core.models.triggers.AbstractTrigger;
import org.flow.core.models.triggers.TriggerId;
import org.flow.core.models.triggers.WorkerTriggerInterface;
import org.flow.core.models.validations.ManualConstraintViolation;
import org.flow.core.models.validations.ModelValidator;
import org.flow.core.models.validations.ValidateConstraintViolation;
import org.flow.core.plugins.PluginRegistry;
import org.flow.core.queues.BroadcastQueueInterface;
import org.flow.core.queues.QueueException;
import org.flow.core.repositories.ConcurrencyLimitRepositoryInterface;
import org.flow.core.repositories.FlowRepositoryInterface;
import org.flow.core.repositories.FlowTopologyRepositoryInterface;
import org.flow.core.runners.ConcurrencyLimit;
import org.flow.core.runners.FlowMetaStoreInterface;
import org.flow.core.runners.RunContext;
import org.flow.core.runners.RunContextFactory;
import org.flow.core.scheduler.events.TriggerCreated;
import org.flow.core.scheduler.events.TriggerDeleted;
import org.flow.core.scheduler.events.TriggerEvent;
import org.flow.core.scheduler.events.TriggerFlowRevisionUpdated;
import org.flow.core.scheduler.events.TriggerUpdated;
import org.flow.core.scheduler.queue.TriggerEventQueue;
import org.flow.core.serializers.JacksonMapper;
import org.flow.core.topologies.FlowTopologyService;
import org.flow.core.utils.ExecutorsUtils;
import org.flow.core.utils.ListUtils;
import org.flow.core.utils.PebbleUtil;
import org.flow.core.utils.SecretUtils;
import org.flow.core.utils.SessionUtil;
import org.flow.plugin.core.flow.Pause;
import org.paas.session.Session;
import org.paas.session.SessionUser;

import io.micronaut.core.annotation.Nullable;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/**
 * 直接接收宿主 Session 的流程服务，同时承担流程校验、持久化和运行通知。
 */
@Singleton
@Slf4j
public class FlowService {
    @Inject
    protected FlowRepositoryInterface flowRepository;

    @Inject
    private FlowParsingService flowParsingService;

    @Inject
    private ModelValidator modelValidator;

    @Inject
    private FlowTopologyRepositoryInterface flowTopologyRepository;

    @Inject
    private Provider<RunContextFactory> runContextFactory; // Lazy init: avoid circular dependency error.

    @Inject
    private FlowTopologyService flowTopologyService;

    @Inject
    private BroadcastQueueInterface<FlowInterface> flowQueue;

    @Inject
    private TriggerEventQueue triggerEventQueue;

    @Inject
    private PluginRegistry pluginRegistry;

    @Inject
    private ConcurrencyLimitRepositoryInterface concurrencyLimitRepository;

    @Inject
    private FlowMetaStoreInterface flowMetaStore;

    private ExecutorService executorService;

    @Inject
    public FlowService(ExecutorsUtils executorsUtils) {
        this.executorService = executorsUtils.maxCachedThreadPool(FlowContext.getContext().getAllocatedCpuCores(), "flow-service");
    }

    @PreDestroy
    void close() throws InterruptedException {
        executorService.shutdown();

        if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
            executorService.shutdownNow();
        }
    }

    /**
     * 校验并创建当前会话租户的流程，保存后更新拓扑、触发器和运行缓存。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param flow 带 YAML 源码的流程定义，租户必须与会话一致；保留 draft 标志
     * @return 已保存并分配版本的流程
     * @throws IllegalArgumentException 流程租户不匹配或源码为空
     * @throws FlowProcessingException 定义解析或校验失败
     * @throws QueueException 保存后的运行通知入队失败
     */
    public <S extends Session<U>, U extends SessionUser> FlowWithSource create(S session, GenericFlow flow) throws FlowProcessingException, QueueException {
        requireFlowTenant(session, flow);
        // FIXME validation is done both here and in the repo
        Objects.requireNonNull(flow, "Cannot create null flow");
        if (flow.getSource() == null || flow.getSource().isBlank()) {
            throw new IllegalArgumentException("Cannot create flow with null or blank source");
        }

        // Strict parsing (unknown / duplicate properties) and constraint validation are both skipped
        // for drafts: they are allowed to be saved invalid and will fail at execution time instead.
        // Use flow.isDraft() (set from the API draft flag) rather than the parsed value,
        // since the draft flag is not part of the YAML source.
        if (!flow.isDraft()) {
            FlowWithSource parsed = flowParsingService.parse(flow.getTenantId(), flow.getSource(), true);
            modelValidator.validate(flowParsingService.parseForValidation(parsed));
            throwOnCyclicDependency(parsed);
        }

        FlowWithSource created = flowRepository.create(flow);

        // impact downstream consumers: topology, scheduler, flow metastore, concurrency limit
        impactDownstreamConsumers(created);

        return created;
    }

    /**
     * 从会话租户重新加载旧版本并更新流程，保存后发布运行通知。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param flow 待保存定义，租户必须与会话一致；不接受调用方提供旧版本对象
     * @return 保存后的流程版本
     * @throws IllegalArgumentException 流程租户不匹配或源码为空
     * @throws NoSuchElementException 当前租户不存在目标流程
     * @throws FlowProcessingException 定义解析或校验失败
     * @throws QueueException 保存后的运行通知入队失败
     */
    public <S extends Session<U>, U extends SessionUser> FlowWithSource update(S session, GenericFlow flow) throws FlowProcessingException, QueueException {
        requireFlowTenant(session, flow);
        FlowInterface previous = flowRepository.findById(SessionUtil.company(session), flow.getNamespace(), flow.getId(), Optional.empty())
            .orElseThrow(() -> new NoSuchElementException("流程不存在"));
        // FIXME validation is done both here and in the repo
        Objects.requireNonNull(flow, "Cannot create null flow");
        if (flow.getSource() == null || flow.getSource().isBlank()) {
            throw new IllegalArgumentException("Cannot create flow with null or blank source");
        }
        Objects.requireNonNull(previous, "Cannot update a flow with null previous");

        // Strict parsing (unknown / duplicate properties) and constraint validation are both skipped
        // for drafts: they are allowed to be saved invalid and will fail at execution time instead.
        // Use flow.isDraft() (set from the API draft flag) rather than the parsed value,
        // since the draft flag is not part of the YAML source.
        if (!flow.isDraft()) {
            FlowWithSource parsed = flowParsingService.parse(flow.getTenantId(), flow.getSource(), true);
            modelValidator.validate(flowParsingService.parseForValidation(parsed));
            throwOnCyclicDependency(parsed);
        }

        FlowWithSource updated = flowRepository.update(flow, previous);

        // impact downstream consumers: topology, scheduler and flow metastore
        impactDownstreamConsumers(updated);

        return updated;
    }

    /**
     * 删除会话租户的指定流程并通知运行组件；目标在服务内部按租户重新查询。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param namespace 流程命名空间，不能为 null
     * @param id 流程标识，不能为 null
     * @return 仓储保存的删除版本
     * @throws NoSuchElementException 当前租户不存在目标流程
     * @throws RuntimeException 保存后的运行通知失败
     */
    public <S extends Session<U>, U extends SessionUser> FlowWithSource delete(S session, String namespace, String id) {
        SessionUtil.user(session);
        FlowWithSource flow = flowRepository.findByIdWithSource(SessionUtil.company(session), namespace, id, Optional.empty())
            .orElseThrow(() -> new NoSuchElementException("流程不存在"));
        FlowWithSource deleted = flowRepository.delete(flow);

        // impact downstream consumers: topology, scheduler and flow metastore
        try {
            impactDownstreamConsumers(deleted);
        } catch (QueueException e) {
            // TODO tmp fix for git-ee plugin, but we handle in some way this exception in the whole service
            throw new RuntimeException(e);
        }

        return deleted;
    }

    /**
     * 使用会话租户解析 YAML 并创建流程，YAML 的 tenantId 不覆盖会话。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param source 待解析的 YAML 源码，不能为 null 或空白
     * @return 保存后的流程版本
     * @throws FlowProcessingException YAML 解析或流程校验失败
     * @throws QueueException 保存后的运行通知入队失败
     */
    public <S extends Session<U>, U extends SessionUser> FlowWithSource create(S session, String source) throws FlowProcessingException, QueueException {
        SessionUtil.user(session);
        return create(session, GenericFlow.fromYaml(SessionUtil.company(session), source));
    }

    /**
     * 使用会话租户解析 YAML 并更新已有流程，旧版本由服务自行加载。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param source 待解析的 YAML 源码，不能为 null 或空白
     * @return 保存后的流程版本
     * @throws NoSuchElementException 当前租户不存在目标流程
     * @throws FlowProcessingException YAML 解析或流程校验失败
     * @throws QueueException 保存后的运行通知入队失败
     */
    public <S extends Session<U>, U extends SessionUser> FlowWithSource update(S session, String source) throws FlowProcessingException, QueueException {
        SessionUtil.user(session);
        return update(session, GenericFlow.fromYaml(SessionUtil.company(session), source));
    }

    /**
     * 检查会话和流程对象的租户一致性，不修改传入对象。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param flow 待检查流程，不能为 null
     * @throws IllegalArgumentException 流程租户与会话不一致
     */
    private <S extends Session<U>, U extends SessionUser> void requireFlowTenant(S session, GenericFlow flow) {
        SessionUtil.user(session);
        Objects.requireNonNull(flow, "flow");
        if (!SessionUtil.company(session).equals(flow.getTenantId())) {
            throw new IllegalArgumentException("流程租户与 Session 不一致");
        }
    }

    // overridden in EE
    protected void impactDownstreamConsumers(FlowWithSource flow) throws QueueException {
        // update the topology asynchronously
        executorService.submit(() -> updateTopology(flow));

        // compute triggers events for the Scheduler
        recomputeTriggers(flow);

        // send it to the flow queue for the flow metastore
        flowQueue.emit(flow);

        // update concurrency limit if any
        updateConcurrencyLimit(flow);
    }

    @VisibleForTesting
    void updateTopology(FlowWithSource flow) {
        try {
            // Runs on a background thread with no HTTP request / user context, so the ACL-aware
            // findAllWithSource() would return zero flows in EE: bypass ACLs with findAllWithSourceWithNoAcl().
            List<FlowWithSource> allFlows = flowRepository.findAllWithSourceWithNoAcl(flow.getTenantId())
                .stream()
                .filter(candidate -> !candidate.isDeleted())
                .toList();

            // Taken from the same snapshot as the edges, so "does this flow still exist" and "what are its
            // edges" can never be answered from two different states of the repository.
            Optional<FlowWithSource> current = allFlows.stream()
                .filter(candidate -> candidate.uidWithoutRevision().equals(flow.uidWithoutRevision()))
                .findFirst();

            List<FlowTopology> topologies = current
                .map(it -> flowTopologyService.topology(it, allFlows).distinct().toList())
                .orElseGet(List::of);

            // If the flow changed meanwhile, that change scheduled its own recomputation and saving
            // ours would replace a fresher topology with a staler one.
            Optional<FlowWithSource> latest = flowRepository.findByIdWithSourceWithoutAcl(flow.getTenantId(), flow.getNamespace(), flow.getId(), Optional.empty());
            if (!Objects.equals(liveRevision(current), liveRevision(latest))) {
                log.debug("Skipping an outdated flow topology computation for flow '{}'", flow.uidWithoutRevision());
                return;
            }

            flowTopologyRepository.save(flow, topologies);
        } catch (Exception e) {
            // The Future returned by executorService.submit(...) is never get()-ed, so without
            // this log a topology failure would be silently swallowed.
            log.error("Unable to update the flow topology for flow '{}'", flow.uidWithoutRevision(), e);
        }
    }

    private static Integer liveRevision(Optional<? extends FlowInterface> flow) {
        return flow.filter(it -> !it.isDeleted()).map(FlowInterface::getRevision).orElse(null);
    }

    private void recomputeTriggers(FlowWithSource flow) {
        var previous = flow.getRevision() <= 1 ? null : flowRepository.findById(flow.getTenantId(), flow.getNamespace(), flow.getId(), Optional.of(flow.getRevision() - 1)).orElse(null);

        // If the previous revision was soft-deleted, the scheduler already dropped its
        // trigger state on TriggerDeleted. Re-creating the flow must emit TriggerCreated
        // so the state is rebuilt; treat it as if there was no previous.
        if (previous != null && previous.isDeleted()) {
            previous = null;
        }

        if (flow.isDeleted()) {
            ListUtils.emptyOnNull(flow.getTriggers()).forEach(
                trigger -> sendTriggerEvent(new TriggerDeleted(TriggerId.of(flow, trigger)))
            );
            return;
        }

        // A draft revision must stay invisible to the scheduler: it is never picked up implicitly.
        // Emitting trigger lifecycle events pinned to a draft revision would either create trigger
        // state for a brand-new draft flow (firing its schedule) or repoint the scheduler's flow
        // cache at the draft revision, masking the last non-draft revision. Skip recomputation so
        // the scheduler keeps operating on the latest non-draft revision (or nothing, if none exists).
        if (flow.isDraft()) {
            return;
        }

        if (previous != null) {
            FlowService.findRemovedTrigger(flow, previous).forEach(
                trigger -> sendTriggerEvent(new TriggerDeleted(TriggerId.of(flow, trigger)))
            );

            if (flow.isDeleted()) {
                return;
            }
        }

        if (previous != null && !Objects.equals(previous.getRevision(), flow.getRevision())) {
            FlowService.findUpdatedTrigger(flow, previous)
                .stream()
                .filter(trigger -> trigger instanceof WorkerTriggerInterface)
                .forEach(
                    trigger -> sendTriggerEvent(new TriggerUpdated(TriggerId.of(flow, trigger), flow.getRevision()))
                );
            FlowService.findNewTrigger(flow, previous)
                .stream()
                .filter(trigger -> trigger instanceof WorkerTriggerInterface)
                .forEach(
                    trigger -> sendTriggerEvent(new TriggerCreated(TriggerId.of(flow, trigger), flow.getRevision()))
                );
            FlowService.findUnchangedTrigger(flow, previous)
                .stream()
                .filter(trigger -> trigger instanceof WorkerTriggerInterface)
                .forEach(
                    trigger -> sendTriggerEvent(new TriggerFlowRevisionUpdated(TriggerId.of(flow, trigger), flow.getRevision()))
                );
            return;
        }

        if (flow.getTriggers() != null) {
            flow.getTriggers()
                .stream()
                .filter(trigger -> trigger instanceof WorkerTriggerInterface)
                .forEach(
                    trigger -> sendTriggerEvent(new TriggerCreated(TriggerId.of(flow, trigger), flow.getRevision()))
                );
        }
    }

    private void updateConcurrencyLimit(FlowWithSource flow) {
        if (flow.isDeleted()) {
            removeConcurrencyLimit(flow);
            return;
        }

        // A draft revision is never picked up by the executor, so it must neither get a live
        // concurrency limit nor remove the one belonging to the live revision.
        if (flow.isDraft()) {
            return;
        }

        if (flow.getConcurrency() == null) {
            removeConcurrencyLimit(flow);
            return;
        }

        var previous = flow.getRevision() <= 1 ? null : flowRepository.findById(flow.getTenantId(), flow.getNamespace(), flow.getId(), Optional.of(flow.getRevision() - 1)).orElse(null);

        // If the previous revision was soft-deleted, its concurrency limit was already removed:
        // treat this as if there was no previous so a new concurrency limit is re-initialized.
        if (previous != null && previous.isDeleted()) {
            previous = null;
        }

        if (previous == null || previous.getConcurrency() == null) {
            initConcurrencyLimit(flow);
        }
    }

    private void removeConcurrencyLimit(FlowWithSource flow) {
        var concurrencyLimit = new ConcurrencyLimit(flow.getTenantId(), flow.getNamespace(), flow.getId(), 0);
        concurrencyLimitRepository.delete(concurrencyLimit);
    }

    private void initConcurrencyLimit(FlowWithSource flow) {
        var concurrencyLimit = new ConcurrencyLimit(flow.getTenantId(), flow.getNamespace(), flow.getId(), 0);
        concurrencyLimitRepository.update(concurrencyLimit);
    }

    private void sendTriggerEvent(TriggerEvent event) {
        this.triggerEventQueue.send(event);
    }

    private static String formatValidationError(String message) {
        if (message.startsWith("Illegal flow source:")) {
            // Already formatted by YamlParser, return as-is
            return message;
        } else if (message.startsWith(":")) {
            message = message.substring(1);
        }
        // For other validation errors, provide context
        return "Validation error: " + message;
    }

    /**
     * Evaluates all checks defined in the given flow using the provided inputs.
     * <p>
     * Each check's {@link Check#getWhen()} is evaluated in the context of the flow.
     * If a condition evaluates to {@code false} or fails to evaluate due to a
     * variable error, the corresponding {@link Check} is added to the returned list.
     * </p>
     *
     * @param flow the flow containing the checks to evaluate
     * @param inputs the input values used when evaluating the conditions
     * @return a list of checks whose conditions evaluated to {@code false} or failed to evaluate
     */
    public List<Check> getFailedChecks(Flow flow, Map<String, Object> inputs) {
        if (!ListUtils.isEmpty(flow.getChecks())) {
            RunContext runContext = runContextFactory.get().of(flow, Map.of("inputs", inputs));
            List<Check> falseConditions = new ArrayList<>();
            for (Check check : flow.getChecks()) {
                try {
                    boolean result = Boolean.TRUE.equals(runContext.renderTyped(check.getWhen()));
                    if (!result) {
                        falseConditions.add(check);
                    }
                } catch (IllegalVariableEvaluationException e) {
                    log.debug(
                        "[tenant: {}] [namespace: {}] [flow: {}] Failed to evaluate check condition. Cause.: {}",
                        flow.getTenantId(),
                        flow.getNamespace(),
                        flow.getId(),
                        e.getMessage(),
                        e
                    );
                    falseConditions.add(
                        Check
                            .builder()
                            .message("Failed to evaluate check condition. Cause: " + e.getMessage())
                            .behavior(Check.Behavior.BLOCK_EXECUTION)
                            .style(Check.Style.ERROR)
                            .build()
                    );
                }
            }
            return falseConditions;
        }
        return List.of();
    }

    /**
     * 在会话租户中校验流程源码与依赖，返回每份源码的诊断，不写入仓储。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param flowSources 待校验源码列表，不能为 null；不修改列表
     * @return 按源码顺序排列的校验结果，输入为空时返回空列表
     */
    public <S extends Session<U>, U extends SessionUser> List<ValidateConstraintViolation> validate(S session, List<FlowSource> flowSources) {
        SessionUtil.user(session);
        String tenantId = SessionUtil.company(session);
        AtomicInteger index = new AtomicInteger(0);
        List<ValidateConstraintViolation> constraints = new ArrayList<>();
        flowSources.forEach(flowSource ->
        {
            ValidateConstraintViolation.ValidateConstraintViolationBuilder<?, ?> constraintsBuilder = ValidateConstraintViolation.builder();
            constraintsBuilder.index(index.getAndIncrement());
            constraintsBuilder.filename(flowSource.filename());

            try {
                String source = flowSource.content();
                FlowWithSource flow = flowParsingService.parse(tenantId, source, true);

                Integer sentRevision = flow.getRevision();
                if (sentRevision != null) {
                    Integer lastRevision = Optional.ofNullable(flowRepository.lastRevision(tenantId, flow.getNamespace(), flow.getId())).orElse(0);
                    constraintsBuilder.outdated(!sentRevision.equals(lastRevision + 1));
                }

                FlowWithSource parsedFlow = flowParsingService.parseForValidation(flow);
                constraintsBuilder.deprecationPaths(deprecationPaths(parsedFlow));
                constraintsBuilder.warnings(warnings(parsedFlow, tenantId));
                constraintsBuilder.infos(relocations(source).stream().map(relocation -> relocation.from() + " is replaced by " + relocation.to()).toList());
                constraintsBuilder.flow(flow.getId());
                constraintsBuilder.namespace(flow.getNamespace());

                modelValidator.validate(parsedFlow);
                throwOnCyclicDependency(parsedFlow);
            } catch (ConstraintViolationException e) {
                String friendlyMessage = formatValidationError(e.getMessage());
                constraintsBuilder.constraints(friendlyMessage);
            } catch (FlowProcessingException e) {
                if (e.getCause() instanceof ConstraintViolationException cve) {
                    String friendlyMessage = formatValidationError(cve.getMessage());
                    constraintsBuilder.constraints(friendlyMessage);
                } else {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    constraintsBuilder.constraints("Unable to validate the flow: " + cause.getMessage());
                }
            } catch (RuntimeException e) {
                // In case of any error, we add a validation violation so the error is displayed in the UI.
                // We may change that by throwing an internal error and handle it in the UI, but this should not occur except for rare cases
                // in dev like incompatible plugin versions.
                log.error("Unable to validate the flow", e);
                constraintsBuilder.constraints("Unable to validate the flow: " + e.getMessage());
            }

            constraints.add(constraintsBuilder.build());
        });

        return constraints;
    }

    /**
     * 在会话租户下导入流程，已有定义更新为新版本；YAML 不能改变租户。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param source 待导入 YAML，不能为 null 或空白
     * @return 导入结果或预览版本
     * @throws FlowProcessingException 解析、依赖校验或运行通知失败
     */
    public <S extends Session<U>, U extends SessionUser> FlowWithSource importFlow(S session, String source) throws FlowProcessingException {
        return this.importFlow(session, source, false);
    }

    /**
     * 在会话租户下导入流程，已有定义更新为新版本；YAML 不能改变租户。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param source 待导入 YAML，不能为 null 或空白
     * @param dryRun true 只计算导入结果，false 保存并发布运行通知
     * @return 导入结果或预览版本
     * @throws FlowProcessingException 解析、依赖校验或运行通知失败
     */
    public <S extends Session<U>, U extends SessionUser> FlowWithSource importFlow(S session, String source, boolean dryRun) throws FlowProcessingException {
        SessionUtil.user(session);
        String tenantId = SessionUtil.company(session);
        final GenericFlow flow = GenericFlow.fromYaml(tenantId, source);

        Optional<FlowWithSource> maybeExisting = flowRepository.findByIdWithSource(
            flow.getTenantId(),
            flow.getNamespace(),
            flow.getId(),
            Optional.empty(),
            true
        );

        FlowWithSource flowToImport = flowParsingService.parse(flow, true);
        throwOnCyclicDependency(flowToImport);

        if (dryRun) {
            return maybeExisting
                .map(
                    previous -> previous.isSameWithSource(flowToImport) && !previous.isDeleted() ? previous
                        : FlowWithSource.of(flowToImport.toBuilder().revision(previous.getRevision() + 1).build(), source)
                )
                .orElseGet(() -> FlowWithSource.of(flowToImport, source).toBuilder().tenantId(tenantId).revision(1).build());
        } else {
            FlowWithSource saved = maybeExisting
                .map(previous -> flowRepository.update(flow, previous))
                .orElseGet(() -> flowRepository.create(flow));
            try {
                impactDownstreamConsumers(saved);
            } catch (QueueException e) {
                throw new FlowProcessingException(e.getMessage(), e);
            }
            return saved;
        }
    }

    /**
     * 按会话租户查询流程，其他租户的数据不在返回范围内。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param namespace 查询选择条件，不能为 null
     * @return 匹配的流程；无匹配时为空结果
     */
    public <S extends Session<U>, U extends SessionUser> List<FlowWithSource> findByNamespaceWithSource(S session, String namespace) {
        SessionUtil.user(session);
        return flowRepository.findByNamespaceWithSource(SessionUtil.company(session), namespace);
    }

    /**
     * 按会话租户查询流程，其他租户的数据不在返回范围内。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @return 匹配的流程；无匹配时为空结果
     */
    public <S extends Session<U>, U extends SessionUser> List<Flow> findAll(S session) {
        SessionUtil.user(session);
        return flowRepository.findAll(SessionUtil.company(session));
    }

    /**
     * 按会话租户查询流程，其他租户的数据不在返回范围内。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param namespace 查询选择条件，不能为 null
     * @return 匹配的流程；无匹配时为空结果
     */
    public <S extends Session<U>, U extends SessionUser> List<Flow> findByNamespace(S session, String namespace) {
        SessionUtil.user(session);
        return flowRepository.findByNamespace(SessionUtil.company(session), namespace);
    }

    /**
     * 按会话租户查询流程，其他租户的数据不在返回范围内。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param namespace 查询选择条件，不能为 null
     * @param flowId 查询选择条件，不能为 null
     * @return 匹配的流程；无匹配时为空结果
     */
    public <S extends Session<U>, U extends SessionUser> Optional<Flow> findById(S session, String namespace, String flowId) {
        SessionUtil.user(session);
        return flowRepository.findById(SessionUtil.company(session), namespace, flowId);
    }

    /**
     * 查询会话租户指定流程的版本。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param namespace 流程命名空间，不能为 null
     * @param flowId 流程标识，不能为 null
     * @param revision 指定版本，null 表示最新版本
     * @return 匹配流程；当前租户无匹配时为空
     */
    public <S extends Session<U>, U extends SessionUser> Optional<Flow> findById(S session, String namespace, String flowId, Integer revision) {
        SessionUtil.user(session);
        return flowRepository.findById(SessionUtil.company(session), namespace, flowId, Optional.ofNullable(revision));
    }

    public Stream<FlowInterface> keepLastVersion(Stream<FlowInterface> stream) {
        return keepLastVersionCollector(stream);
    }

    public List<String> deprecationPaths(Flow flow) {
        return deprecationTraversal("", flow).toList();
    }

    /**
     * 生成流程配置与子流程引用的警告，不修改流程。
     * @param flow 已按会话租户解析的流程，null 返回空列表
     * @param tenantId 由会话确定的租户，不能为 null
     * @return 警告列表，无问题时为空
     */
    private List<String> warnings(Flow flow, String tenantId) {
        if (flow == null) {
            return Collections.emptyList();
        }

        List<String> warnings = new ArrayList<>(checkValidSubflows(flow, tenantId));

        List<org.flow.plugin.core.trigger.Flow> flowTriggers = ListUtils.emptyOnNull(flow.getTriggers()).stream()
            .filter(org.flow.plugin.core.trigger.Flow.class::isInstance)
            .map(org.flow.plugin.core.trigger.Flow.class::cast)
            .toList();
        flowTriggers.forEach(flowTrigger ->
        {
            if (
                ListUtils.isEmpty(flowTrigger.getDependsOn())
                    && (flowTrigger.getWhen() == null || "true".equals(flowTrigger.getWhen()))
            ) {
                warnings.add(
                    "This flow will be triggered for EVERY execution of EVERY flow on your instance. We recommend adding the dependsOn property to the Flow trigger '" + flowTrigger.getId()
                        + "'."
                );
            }
        });

        // add warning for runnable properties (timeout, workerSelector, taskCache) when used not in a runnable
        flow.allTasksWithChilds().forEach(task ->
        {
            if (!(task instanceof RunnableTask<?>)) {
                if (task.getTimeout() != null && !(task instanceof Pause)) {
                    warnings.add("The task '" + task.getId() + "' cannot use the 'timeout' property as it's only relevant for runnable tasks.");
                }
                if (task.getTaskCache() != null) {
                    warnings.add("The task '" + task.getId() + "' cannot use the 'taskCache' property as it's only relevant for runnable tasks.");
                }
                if (task.getWorkerSelector() != null) {
                    warnings.add("The task '" + task.getId() + "' cannot use the 'workerSelector' property as it's only relevant for runnable tasks.");
                }
            }
        });

        // warn when @PluginProperty(secret=true) fields have plain-text values
        flow.allTasksWithChilds().forEach(
            task -> SecretUtils.validateSecretFields(task)
                .forEach(msg -> warnings.add("Task '" + task.getId() + "': " + msg))
        );
        ListUtils.emptyOnNull(flow.getTriggers()).forEach(
            trigger -> SecretUtils.validateSecretFields(trigger)
                .forEach(msg -> warnings.add("Trigger '" + trigger.getId() + "': " + msg))
        );

        return warnings;
    }

    public List<Relocation> relocations(String flowSource) {
        try {
            Map<String, Class<?>> aliases = pluginRegistry.plugins().stream()
                .flatMap(plugin -> plugin.getAliases().values().stream())
                .collect(
                    Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (existing, duplicate) -> existing
                    )
                );
            Map<String, Object> stringObjectMap = JacksonMapper.ofYaml().readValue(flowSource, JacksonMapper.MAP_TYPE_REFERENCE);
            return relocations(aliases, stringObjectMap);
        } catch (JsonProcessingException e) {
            // silent failure (we don't compromise the app / response for warnings)
            return Collections.emptyList();
        }
    }

    /**
     * 检查静态子流程引用是否存在且可执行，不解析动态表达式。
     * @param flow 已按会话租户解析的流程，不能为 null
     * @param tenantId 由会话确定的租户，不能为 null
     * @return 引用警告列表，无问题时为空
     */
    private List<String> checkValidSubflows(Flow flow, String tenantId) {
        List<org.flow.plugin.core.flow.Subflow> subFlows = ListUtils.emptyOnNull(flow.getTasks()).stream()
            .filter(org.flow.plugin.core.flow.Subflow.class::isInstance)
            .map(org.flow.plugin.core.flow.Subflow.class::cast)
            .toList();

        List<String> violations = new ArrayList<>();

        subFlows.forEach(subflow ->
        {
            String regex = ".*\\{\\{.+}}.*"; // regex to check if string contains pebble
            String subflowId = subflow.getFlowId();
            String namespace = subflow.getNamespace();
            if ((subflowId != null && subflowId.matches(regex)) || (namespace != null && namespace.matches(regex))) {
                return;
            }
            if (subflowId == null || namespace == null) {
                // those fields are mandatory so the mandatory validation will apply
                return;
            }
            Optional<Flow> optional = flowRepository.findById(tenantId, subflow.getNamespace(), subflow.getFlowId());

            if (optional.isEmpty()) {
                violations.add("The subflow '" + subflow.getFlowId() + "' not found in namespace '" + subflow.getNamespace() + "'.");
            } else if (optional.get().isDisabled()) {
                violations.add("The subflow '" + subflow.getFlowId() + "' is disabled in namespace '" + subflow.getNamespace() + "'.");
            }
        });

        return violations;
    }

    /**
     * Returns a message describing a cross-flow execution cycle the given flow's Subflow tasks and Flow
     * triggers would close, or {@link Optional#empty()} when saving it introduces none.
     * <p>
     * Only edges that fire unconditionally are counted (no {@code disabled}/{@code runIf} on a Subflow
     * task, no {@code when}/{@code conditions} on a Flow trigger or its {@code dependsOn} entries): a
     * cycle gated by a condition may be intentional and terminating, and this cannot decide whether the
     * condition ever goes false. {@code flow.execution.depth.max-depth}
     * ({@link org.flow.core.runners.configuration.ExecutionDepthConfiguration}) bounds that case, and
     * any other cycle this static check cannot see, at runtime instead.
     * <p>
     * Templated ({@code {{ }}}) Subflow namespace/flowId, and a {@code dependsOn} entry naming neither
     * namespace nor flowId, are skipped for the same reason.
     */
    private Optional<String> checkCyclicDependency(Flow flow) {
        String root = flow.uidWithoutRevision();

        Map<String, List<String>> edges = new HashMap<>();
        Map<String, String> displayNames = new HashMap<>();
        for (FlowWithSource candidate : flowMetaStore.allLastVersion()) {
            // The flow being saved contributes its own edges below, from the version being saved rather
            // than whatever stale copy the cache may still hold for it.
            if (!Objects.equals(candidate.getTenantId(), flow.getTenantId()) || candidate.uidWithoutRevision().equals(root) || candidate.isDraft()) {
                continue;
            }
            addUnconditionalEdges(candidate, edges, displayNames);
        }
        addUnconditionalEdges(flow, edges, displayNames);

        if (ListUtils.isEmpty(edges.get(root))) {
            return Optional.empty();
        }

        List<String> path = new ArrayList<>(List.of(root));
        if (!findPathToRoot(root, root, edges, path, new HashSet<>(List.of(root)), new HashSet<>())) {
            return Optional.empty();
        }

        return Optional.of(path.stream().map(uid -> displayNames.getOrDefault(uid, uid)).collect(Collectors.joining(" → ")));
    }

    /**
     * Rejects the save with a 422 when {@link #checkCyclicDependency} finds a cross-flow execution cycle.
     */
    private void throwOnCyclicDependency(FlowWithSource flow) {
        checkCyclicDependency(flow).ifPresent(cycle ->
        {
            throw new ConstraintViolationException(
                Collections.singleton(
                    ManualConstraintViolation.of(
                        "Cyclic flow dependency detected: %s. Saving this flow would create an execution loop.".formatted(cycle),
                        flow,
                        FlowWithSource.class,
                        "flow",
                        cycle
                    )
                )
            );
        });
    }

    /**
     * Adds every unconditional Subflow-task and Flow-trigger {@code dependsOn} edge {@code flow}
     * contributes to the cross-flow graph, keyed by {@link FlowInterface#uidWithoutRevision()}, and
     * records a {@code namespace.id} display name for each node touched.
     */
    private static void addUnconditionalEdges(Flow flow, Map<String, List<String>> edges, Map<String, String> displayNames) {
        String from = flow.uidWithoutRevision();
        displayNames.put(from, flow.getNamespace() + "." + flow.getId());

        flow.allTasksWithChilds().stream()
            .filter(task -> task instanceof ExecutableTask<?>)
            .filter(FlowService::isUnconditionalSubflowTask)
            .map(task -> ((ExecutableTask<?>) task).subflowId())
            .filter(subflowId -> subflowId.namespace() != null && subflowId.flowId() != null)
            .filter(subflowId -> !PebbleUtil.containsOpeningBlockDelimiter(subflowId.namespace()) && !PebbleUtil.containsOpeningBlockDelimiter(subflowId.flowId()))
            .forEach(subflowId ->
            {
                String to = FlowId.uidWithoutRevision(flow.getTenantId(), subflowId.namespace(), subflowId.flowId());
                edges.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
                displayNames.putIfAbsent(to, subflowId.namespace() + "." + subflowId.flowId());
            });

        ListUtils.emptyOnNull(flow.getTriggers()).stream()
            .filter(org.flow.plugin.core.trigger.Flow.class::isInstance)
            .map(org.flow.plugin.core.trigger.Flow.class::cast)
            .filter(FlowService::isUnconditionalFlowTrigger)
            .flatMap(trigger -> ListUtils.emptyOnNull(trigger.getDependsOn()).stream())
            .filter(FlowService::isUnconditionalDependency)
            .filter(dependency -> dependency.getNamespace() != null && dependency.getFlowId() != null)
            .forEach(dependency ->
            {
                String depFrom = FlowId.uidWithoutRevision(flow.getTenantId(), dependency.getNamespace(), dependency.getFlowId());
                edges.computeIfAbsent(depFrom, k -> new ArrayList<>()).add(from);
                displayNames.putIfAbsent(depFrom, dependency.getNamespace() + "." + dependency.getFlowId());
            });
    }

    private static boolean isUnconditionalSubflowTask(Task task) {
        return !Boolean.TRUE.equals(task.getDisabled()) && (task.getRunIf() == null || "true".equals(task.getRunIf()));
    }

    private static boolean isUnconditionalFlowTrigger(org.flow.plugin.core.trigger.Flow trigger) {
        return !trigger.isDisabled() && (trigger.getWhen() == null || "true".equals(trigger.getWhen()));
    }

    private static boolean isUnconditionalDependency(org.flow.plugin.core.trigger.Flow.Dependency dependency) {
        return dependency.getWhen() == null || Property.ofValue("true").equals(dependency.getWhen());
    }

    /**
     * Depth-first search for a path from {@code current} back to {@code root} along {@code edges}, so
     * that {@code checkCyclicDependency} rejects only a save that closes a cycle through the flow being
     * saved — not a save that happens to reach into an unrelated cycle among other flows.
     * <p>
     * {@code visiting} is the current DFS path (a back-edge into it is a cycle not involving root, and
     * must be skipped rather than re-entered, or it recurses forever); {@code deadEnds} is nodes fully
     * explored with no path to root, which is true regardless of which path reached them, so they are
     * never re-explored.
     */
    private static boolean findPathToRoot(String current, String root, Map<String, List<String>> edges, List<String> path, Set<String> visiting, Set<String> deadEnds) {
        for (String next : ListUtils.emptyOnNull(edges.get(current))) {
            if (next.equals(root)) {
                path.add(next);
                return true;
            }
            if (visiting.contains(next) || deadEnds.contains(next)) {
                continue;
            }
            visiting.add(next);
            path.add(next);
            if (findPathToRoot(next, root, edges, path, visiting, deadEnds)) {
                return true;
            }
            path.removeLast();
            visiting.remove(next);
            deadEnds.add(next);
        }

        return false;
    }

    public record Relocation(String from, String to) {
    }

    public record TaskDeprecation(String taskId, String taskType, @Nullable String replacement) {
    }

    public List<TaskDeprecation> findDeprecatedTasks(Flow flow) {
        return flow.allTasksWithChilds().stream()
            .flatMap(task ->
            {
                String taskType = task.getType();
                return pluginRegistry.findMetadataByIdentifier(taskType)
                    .flatMap(metadata ->
                    {
                        // Case 1: task uses a deprecated alias name
                        if (metadata.alias() != null) {
                            boolean replacementDeprecated = Plugin.isDeprecated(metadata.type());
                            String replacement = replacementDeprecated ? null : metadata.type().getName();
                            return Optional.of(new TaskDeprecation(task.getId(), taskType, replacement));
                        }
                        // Case 2: task class itself is annotated @Deprecated
                        if (Plugin.isDeprecated(metadata.type())) {
                            return Optional.of(new TaskDeprecation(task.getId(), taskType, null));
                        }
                        return Optional.empty();
                    })
                    .stream();
            })
            .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Relocation> relocations(Map<String, Class<?>> aliases, Map<String, Object> stringObjectMap) {
        List<Relocation> relocations = new ArrayList<>();
        for (Map.Entry<String, Object> entry : stringObjectMap.entrySet()) {
            if (entry.getValue() instanceof String value && aliases.containsKey(value)) {
                relocations.add(new Relocation(value, aliases.get(value).getName()));
            }

            if (entry.getValue() instanceof Map<?, ?> value) {
                relocations.addAll(relocations(aliases, (Map<String, Object>) value));
            }

            if (entry.getValue() instanceof List<?> value) {
                List<Relocation> listAliases = value.stream().flatMap(item ->
                {
                    if (item instanceof Map<?, ?> map) {
                        return relocations(aliases, (Map<String, Object>) map).stream();
                    }
                    return Stream.empty();
                }).toList();
                relocations.addAll(listAliases);
            }
        }

        return relocations;
    }

    private Stream<String> deprecationTraversal(String prefix, Object object) {
        if (object == null || ClassUtils.isPrimitiveOrWrapper(object.getClass()) || String.class.equals(object.getClass())) {
            return Stream.empty();
        }

        return Stream.concat(
            object.getClass().isAnnotationPresent(Deprecated.class) ? Stream.of(prefix) : Stream.empty(),
            allGetters(object.getClass())
                .flatMap(method ->
                {
                    try {
                        Object fieldValue = method.invoke(object);

                        if (fieldValue instanceof Iterable<?> iterableValue) {
                            fieldValue = StreamSupport.stream(iterableValue.spliterator(), false).toArray(Object[]::new);
                        }

                        String fieldName = method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4);
                        Stream<String> additionalDeprecationPaths = Stream.empty();
                        if (fieldValue instanceof Object[] arrayValue) {
                            additionalDeprecationPaths = IntStream.range(0, arrayValue.length).boxed().flatMap(i -> deprecationTraversal(fieldName + "[%d]".formatted(i), arrayValue[i]));
                        }

                        return Stream.concat(
                            method.isAnnotationPresent(Deprecated.class) && fieldValue != null ? Stream.of(prefix.isEmpty() ? fieldName : prefix + "." + fieldName) : Stream.empty(),
                            additionalDeprecationPaths
                        );
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        // silent failure (we don't compromise the app / response for warnings)
                    }

                    return Stream.empty();
                })
        );
    }

    private Stream<Method> allGetters(Class<?> clazz) {
        return Arrays.stream(clazz.getMethods())
            .filter(m -> !m.getDeclaringClass().equals(Object.class))
            .filter(method -> method.getName().startsWith("get") && method.getName().length() > 3 && method.getParameterCount() == 0)
            .filter(method -> !method.getReturnType().equals(Void.TYPE))
            .filter(method -> !Modifier.isStatic(method.getModifiers()));
    }

    public Collection<FlowInterface> keepLastVersion(List<FlowInterface> flows) {
        return keepLastVersionCollector(flows.stream()).toList();
    }

    public Stream<FlowInterface> keepLastVersionCollector(Stream<FlowInterface> stream) {
        // Use a Map to track the latest version of each flow
        Map<String, FlowInterface> latestFlows = new HashMap<>();

        stream.forEach(flow ->
        {
            String uid = flow.uidWithoutRevision();
            FlowInterface existing = latestFlows.get(uid);

            // Update only if the current flow has a higher revision
            if (existing == null || flow.getRevision() > existing.getRevision()) {
                latestFlows.put(uid, flow);
            } else if (flow.getRevision().equals(existing.getRevision()) && flow.isDeleted()) {
                // Edge case: prefer deleted flow with the same revision
                latestFlows.put(uid, flow);
            }
        });

        // Return the non-deleted flows
        return latestFlows.values().stream().filter(flow -> !flow.isDeleted());
    }

    public boolean removeUnwanted(Flow f, Execution execution) {
        // we don't allow recursive
        return !f.uidWithoutRevision().equals(FlowId.uidWithoutRevision(execution));
    }

    private static List<AbstractTrigger> findRemovedTrigger(Flow flow, Flow previous) {
        return ListUtils.emptyOnNull(previous.getTriggers())
            .stream()
            .filter(
                p -> ListUtils.emptyOnNull(flow.getTriggers())
                    .stream()
                    .noneMatch(c -> c.getId().equals(p.getId()))
            )
            .toList();
    }

    private static List<AbstractTrigger> findUpdatedTrigger(Flow flow, Flow previous) {
        return ListUtils.emptyOnNull(flow.getTriggers())
            .stream()
            .filter(
                oldTrigger -> ListUtils.emptyOnNull(previous.getTriggers())
                    .stream()
                    .anyMatch(trigger -> trigger.getId().equals(oldTrigger.getId()) && !EqualsBuilder.reflectionEquals(trigger, oldTrigger))
            )
            .toList();
    }

    private static List<AbstractTrigger> findNewTrigger(Flow flow, Flow previous) {
        return ListUtils.emptyOnNull(flow.getTriggers())
            .stream()
            .filter(
                oldTrigger -> ListUtils.emptyOnNull(previous.getTriggers())
                    .stream()
                    .noneMatch(trigger -> trigger.getId().equals(oldTrigger.getId()))
            )
            .toList();
    }

    @VisibleForTesting
    static List<AbstractTrigger> findUnchangedTrigger(Flow flow, Flow previous) {
        return ListUtils.emptyOnNull(flow.getTriggers())
            .stream()
            .filter(
                current -> ListUtils.emptyOnNull(previous.getTriggers())
                    .stream()
                    .anyMatch(prev -> prev.getId().equals(current.getId()) && EqualsBuilder.reflectionEquals(prev, current))
            )
            .toList();
    }

    public static String cleanupSource(String source) {
        return source.replaceFirst("(?m)^revision: \\d+\n?", "");
    }

    public static String injectDisabled(String source, Boolean disabled) {
        String regex = disabled ? "^disabled\\s*:\\s*false\\s*" : "^disabled\\s*:\\s*true\\s*";

        Pattern p = Pattern.compile(regex, Pattern.MULTILINE);
        if (p.matcher(source).find()) {
            return p.matcher(source).replaceAll(String.format("disabled: %s\n", disabled));
        }

        return source + String.format("\ndisabled: %s", disabled);
    }

    // Used in Git plugin
    /**
     * 按会话租户查询流程，其他租户的数据不在返回范围内。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param namespacePrefix 查询选择条件，不能为 null
     * @return 匹配的流程；无匹配时为空结果
     */
    public <S extends Session<U>, U extends SessionUser> List<Flow> findByNamespacePrefix(S session, String namespacePrefix) {
        SessionUtil.user(session);
        return flowRepository.findByNamespacePrefix(SessionUtil.company(session), namespacePrefix);
    }

    /**
     * 在会话租户中选择可执行流程；未指定版本时排除草稿。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param namespace 流程命名空间，不能为 null
     * @param id 流程标识，不能为 null
     * @param revision 指定版本，empty 表示最新可执行版本，不能为 null
     * @return 选中的可执行流程
     * @throws NoSuchElementException 流程不存在或已删除
     * @throws IllegalStateException 流程禁用或定义无效
     */
    public <S extends Session<U>, U extends SessionUser> Flow getFlowIfExecutableOrThrow(S session, String namespace, String id, Optional<Integer> revision) {
        SessionUtil.user(session);
        String tenant = SessionUtil.company(session);
        if (revision.isPresent()) {
            // For a specific revision, check if the flow has not been deleted: the deletion will only occur on the last revision.
            Optional<Flow> latest = flowRepository.findByIdWithoutAcl(tenant, namespace, id, Optional.empty());
            if (latest.isEmpty() || latest.get().isDeleted()) {
                throw new NoSuchElementException("Requested Flow is not found.");
            }
        }

        // When no revision is specified, we resolve to the latest non-draft revision: drafts are
        // only executable when the caller passes the revision explicitly.
        Optional<Flow> optional = revision.isPresent()
            ? flowRepository.findByIdWithoutAcl(tenant, namespace, id, revision)
            : flowRepository.findByIdForExecutionWithoutAcl(tenant, namespace, id);
        // A delete appends a revision flagged deleted, so the tombstone is reachable only through
        // an explicit revision; the no-revision lookup above already filters it out.
        if (optional.isEmpty() || optional.get().isDeleted()) {
            throw new NoSuchElementException("Requested Flow is not found.");
        }

        Flow flow = optional.get();
        if (flow.isDisabled()) {
            throw new IllegalStateException("Requested Flow is disabled.");
        }

        if (flow instanceof FlowWithException fwe) {
            throw new IllegalStateException("Requested Flow is not valid. Error: " + fwe.getException());
        }
        return flow;
    }

    /**
     * Validates a flow that is about to be executed. Drafts can be saved with constraint violations
     * (missing required fields, invalid patterns, ...) so we re-validate at execution time. The
     * caller decides what to do with the violations - typically: emit a FAILED execution rather than
     * letting the executor blow up later in an opaque way.
     *
     * @param flow The flow to validate.
     * @return The {@link ConstraintViolationException} carrying the violations, or {@link Optional#empty()} if valid.
     */
    public Optional<ConstraintViolationException> validateForExecution(Flow flow) {
        try {
            return modelValidator.isValid(flowParsingService.parseForValidation(flow));
        } catch (FlowProcessingException e) {
            // The flow could not be processed (e.g., unknown plugin). Surface this as a violation
            // so the execution fails with the same error path as other invalid flows.
            return Optional.of(new ConstraintViolationException(e.getMessage(), Set.of()));
        }
    }

    /**
     * 查询当前会话租户的流程依赖图，递归展开时保持同一租户条件。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param namespace 流程命名空间，不能为 null
     * @param id 流程标识，不能为 null
     * @param destinationOnly true 只查询入向依赖，false 查询两个方向
     * @param expandAll true 递归展开，false 只查询一层
     * @return 匹配的依赖边流；无匹配时为空
     */
    public <S extends Session<U>, U extends SessionUser> Stream<FlowTopology> findDependencies(S session, String namespace, String id, boolean destinationOnly, boolean expandAll) {
        SessionUtil.user(session);
        String tenant = SessionUtil.company(session);
        return expandAll ? recursiveFlowTopology(new ArrayList<>(), tenant, namespace, id, destinationOnly)
            : flowTopologyRepository.findByFlow(tenant, namespace, id, destinationOnly).stream();
    }

    /**
     * 在固定租户中递归收集依赖边，并通过已访问列表避免重复展开。
     * @param visitedTopologies 已访问边标识，将追加本次遇到的边
     * @param tenantId 原始会话租户，递归过程中不从关系节点覆盖
     * @param namespace 当前节点命名空间，不能为 null
     * @param id 当前流程标识，不能为 null
     * @param destinationOnly true 只查入向边，false 查两个方向
     * @return 去重后的依赖边流
     */
    private Stream<FlowTopology> recursiveFlowTopology(List<String> visitedTopologies, String tenantId, String namespace, String id, boolean destinationOnly) {
        var flowTopologies = flowTopologyRepository.findByFlow(tenantId, namespace, id, destinationOnly);

        var visitedNodes = new ArrayList<String>();
        visitedNodes.add(id);
        return flowTopologies.stream()
            // ignore already visited topologies
            .filter(x -> !visitedTopologies.contains(x.uid()))
            .flatMap(topology ->
            {
                visitedTopologies.add(topology.uid());
                Stream<FlowTopology> subTopologies = Stream
                    .of(topology.getDestination(), topology.getSource())
                    // ignore already visited nodes
                    .filter(x -> !visitedNodes.contains(x.getId()))
                    // recursively visit children and parents nodes
                    .flatMap(relationNode ->
                    {
                        visitedNodes.add(relationNode.getId());
                        return recursiveFlowTopology(visitedTopologies, tenantId, relationNode.getNamespace(), relationNode.getId(), destinationOnly);
                    });
                return Stream.concat(Stream.of(topology), subTopologies);
            });
    }

    private IllegalStateException noRepositoryException() {
        return new IllegalStateException("No repository found. Make sure the `flow.repository.type` property is set.");
    }
}
