package org.cses.flow.controller.demo;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import io.micronaut.serde.annotation.Serdeable;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.flows.ActorRef;
import org.cses.flow.core.domains.flows.Data;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.FlowDraft;
import org.cses.flow.core.domains.flows.Input;

import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.flows.inputs.IntegerInput;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.extensions.flow.Pause;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP protocol models for the standalone Flow demo.
 */
public final class FlowDemoModels {

    private FlowDemoModels() {
    }

    @Serdeable
    public static final class SaveDraftRequest {

        private String raw;
        private Long expectedLockVersion;

        public SaveDraftRequest() {
        }

        public String getRaw() {
            return raw;
        }

        public void setRaw(String raw) {
            this.raw = raw;
        }

        public Long getExpectedLockVersion() {
            return expectedLockVersion;
        }

        public void setExpectedLockVersion(Long expectedLockVersion) {
            this.expectedLockVersion = expectedLockVersion;
        }
    }

    @Serdeable
    public static final class ResumeRequest {

        private Map<String, Object> outputs = new LinkedHashMap<>();

        public ResumeRequest() {
        }

        public Map<String, Object> getOutputs() {
            return outputs;
        }

        public void setOutputs(Map<String, Object> outputs) {
            this.outputs = outputs;
        }
    }

    @Serdeable
    public static final class SessionView {

        private final String companyId;
        private final String userId;
        private final String userName;

        private SessionView(
            String companyId,
            String userId,
            String userName
        ) {
            this.companyId = companyId;
            this.userId = userId;
            this.userName = userName;
        }

        public static SessionView from(Session<User> session) {
            return new SessionView(
                session.getCompanyId(),
                session.getUserId(),
                session.getName()
            );
        }

        public String getCompanyId() {
            return companyId;
        }

        public String getUserId() {
            return userId;
        }

        public String getUserName() {
            return userName;
        }
    }

    @Serdeable
    public static final class DraftView {

        private final String id;
        private final String raw;
        private final long lockVersion;
        private final long createdAt;
        private final long updatedAt;
        private final String createdBy;
        private final String updatedBy;
        private final FlowView deployedFlow;

        private DraftView(
            String id,
            String raw,
            long lockVersion,
            long createdAt,
            long updatedAt,
            String createdBy,
            String updatedBy,
            FlowView deployedFlow
        ) {
            this.id = id;
            this.raw = raw;
            this.lockVersion = lockVersion;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.createdBy = createdBy;
            this.updatedBy = updatedBy;
            this.deployedFlow = deployedFlow;
        }

        public static DraftView from(
            FlowDraft draft,
            Flow deployedFlow
        ) {
            return new DraftView(
                draft.id(),
                draft.raw(),
                draft.lockVersion(),
                draft.createdAt(),
                draft.updatedAt(),
                actorName(draft.creator()),
                actorName(draft.updater()),
                deployedFlow == null ? null : FlowView.from(deployedFlow)
            );
        }

        public String getId() {
            return id;
        }

        public String getRaw() {
            return raw;
        }

        public long getLockVersion() {
            return lockVersion;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }

        public String getCreatedBy() {
            return createdBy;
        }

        public String getUpdatedBy() {
            return updatedBy;
        }

        public FlowView getDeployedFlow() {
            return deployedFlow;
        }
    }

    @Serdeable
    public static final class DefinitionView {

        private final Map<String, Object> definition;

        public DefinitionView(Map<String, Object> definition) {
            this.definition = immutableMap(definition);
        }

        public Map<String, Object> getDefinition() {
            return definition;
        }
    }

    @Serdeable
    public static final class InputTypeView {

        private final String code;
        private final String valueClass;
        private final String inputClass;
        private final List<InputFieldView> fields;

        private InputTypeView(DataType type) {
            this.code = type.name();
            this.valueClass = type.getValueClass().getSimpleName();
            this.inputClass = inputClass(type);
            this.fields = fields(type);
        }

        public static List<InputTypeView> catalog() {
            return java.util.Arrays.stream(DataType.values())
                .map(InputTypeView::new)
                .toList();
        }

        public String getCode() {
            return code;
        }

        public String getValueClass() {
            return valueClass;
        }

        public String getInputClass() {
            return inputClass;
        }

        public List<InputFieldView> getFields() {
            return fields;
        }

        private static String inputClass(DataType type) {
            JsonSubTypes metadata = Input.class.getAnnotation(
                JsonSubTypes.class
            );
            return java.util.Arrays.stream(metadata.value())
                .filter(subtype -> subtype.name().equals(type.name()))
                .map(subtype -> subtype.value().getSimpleName())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "Missing Input JSON subtype for " + type.name()
                ));
        }

        private static List<InputFieldView> fields(DataType type) {
            List<InputFieldView> result = new java.util.ArrayList<>();
            result.add(new InputFieldView(
                "key",
                "字段 key",
                "text",
                true,
                "同一输入列表内唯一的业务标识"
            ));
            result.add(new InputFieldView(
                "displayName",
                "显示名称",
                "text",
                true,
                "页面向使用者展示的字段名称"
            ));
            result.add(new InputFieldView(
                "required",
                "必填",
                "boolean",
                true,
                "运行时是否必须提供该输入"
            ));
            result.add(new InputFieldView(
                "defaultValue",
                "默认值",
                control(type),
                false,
                "未提供输入时使用，并由具体 Input 校验"
            ));
            if (type == DataType.INTEGER) {
                result.add(new InputFieldView(
                    "min",
                    "最小值",
                    "number",
                    false,
                    "IntegerInput 允许的包含下界"
                ));
                result.add(new InputFieldView(
                    "max",
                    "最大值",
                    "number",
                    false,
                    "IntegerInput 允许的包含上界"
                ));
            }
            return List.copyOf(result);
        }

        private static String control(DataType type) {
            return switch (type) {
                case BOOLEAN -> "boolean";
                case BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE -> "number";
                case STRING, CHARACTER -> "text";
            };
        }
    }

    @Serdeable
    public static final class InputFieldView {

        private final String key;
        private final String displayName;
        private final String control;
        private final boolean required;
        private final String description;

        private InputFieldView(
            String key,
            String displayName,
            String control,
            boolean required,
            String description
        ) {
            this.key = key;
            this.displayName = displayName;
            this.control = control;
            this.required = required;
            this.description = description;
        }

        public String getKey() {
            return key;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getControl() {
            return control;
        }

        public boolean isRequired() {
            return required;
        }

        public String getDescription() {
            return description;
        }
    }

    @Serdeable
    public static final class FlowView {

        private final String id;
        private final String key;
        private final long reversion;
        private final String description;
        private final List<DataView> inputs;
        private final List<DataView> outputs;
        private final List<TaskView> tasks;
        private final long createdAt;
        private final long updatedAt;

        private FlowView(
            String id,
            String key,
            long reversion,
            String description,
            List<DataView> inputs,
            List<DataView> outputs,
            List<TaskView> tasks,
            long createdAt,
            long updatedAt
        ) {
            this.id = id;
            this.key = key;
            this.reversion = reversion;
            this.description = description;
            this.inputs = List.copyOf(inputs);
            this.outputs = List.copyOf(outputs);
            this.tasks = List.copyOf(tasks);
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public static FlowView from(Flow flow) {
            return new FlowView(
                flow.id(),
                flow.key(),
                flow.reversion(),
                flow.description(),
                flow.inputs().stream().map(DataView::from).toList(),
                flow.outputs().stream().map(DataView::from).toList(),
                flow.tasks().stream().map(TaskView::from).toList(),
                flow.createdAt(),
                flow.updatedAt()
            );
        }

        public String getId() {
            return id;
        }

        public String getKey() {
            return key;
        }

        public long getReversion() {
            return reversion;
        }

        public String getDescription() {
            return description;
        }

        public List<DataView> getInputs() {
            return inputs;
        }

        public List<DataView> getOutputs() {
            return outputs;
        }

        public List<TaskView> getTasks() {
            return tasks;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }
    }

    @Serdeable
    public static final class DataView {

        private final String key;
        private final String type;
        private final String displayName;
        private final Boolean required;
        private final Object defaultValue;
        private final Map<String, Object> constraints;

        private DataView(
            String key,
            String type,
            String displayName,
            Boolean required,
            Object defaultValue,
            Map<String, Object> constraints
        ) {
            this.key = key;
            this.type = type;
            this.displayName = displayName;
            this.required = required;
            this.defaultValue = defaultValue;
            this.constraints = Map.copyOf(constraints);
        }

        private static DataView from(Data data) {
            if (data instanceof Input<?> input) {
                Map<String, Object> constraints = new LinkedHashMap<>();
                if (input instanceof IntegerInput integerInput) {
                    if (integerInput.getMin() != null) {
                        constraints.put("min", integerInput.getMin());
                    }
                    if (integerInput.getMax() != null) {
                        constraints.put("max", integerInput.getMax());
                    }
                }
                Object defaultValue = input.getDefaultValue();
                if (defaultValue instanceof Character character) {
                    defaultValue = character.toString();
                }
                return new DataView(
                    input.getKey(),
                    input.getType().name(),
                    input.getDisplayName(),
                    input.isRequired(),
                    defaultValue,
                    constraints
                );
            }
            return new DataView(
                data.getKey(),
                data.getType().name(),
                null,
                null,
                null,
                Map.of()
            );
        }

        public String getKey() {
            return key;
        }

        public String getType() {
            return type;
        }

        public String getDisplayName() {
            return displayName;
        }

        public Boolean getRequired() {
            return required;
        }

        public Object getDefaultValue() {
            return defaultValue;
        }

        public Map<String, Object> getConstraints() {
            return constraints;
        }
    }

    @Serdeable
    public static final class TaskView {

        private final String id;
        private final String key;
        private final String type;
        private final String route;
        private final List<String> dependOn;
        private final List<DataView> inputs;
        private final List<DataView> outputs;
        private final List<TaskView> tasks;
        private final TaskView pause;
        private final List<DataView> resume;
        private final String duration;
        private final String behavior;

        private TaskView(
            String id,
            String key,
            String type,
            String route,
            List<String> dependOn,
            List<DataView> inputs,
            List<DataView> outputs,
            List<TaskView> tasks,
            TaskView pause,
            List<DataView> resume,
            String duration,
            String behavior
        ) {
            this.id = id;
            this.key = key;
            this.type = type;
            this.route = route;
            this.dependOn = List.copyOf(dependOn);
            this.inputs = List.copyOf(inputs);
            this.outputs = List.copyOf(outputs);
            this.tasks = List.copyOf(tasks);
            this.pause = pause;
            this.resume = List.copyOf(resume);
            this.duration = duration;
            this.behavior = behavior;
        }

        private static TaskView from(Task task) {
            Pause pauseTask = task instanceof Pause candidate
                ? candidate
                : null;
            return new TaskView(
                task.id(),
                task.key(),
                task.getType(),
                task.route().source(),
                task.dependOn(),
                task.inputs().stream().map(DataView::from).toList(),
                task.outputs().stream().map(DataView::from).toList(),
                task.tasks().stream().map(TaskView::from).toList(),
                pauseTask == null ? null : from(pauseTask.pause()),
                pauseTask == null
                    ? List.of()
                    : pauseTask.resume().stream().map(DataView::from).toList(),
                pauseTask == null
                    ? null
                    : pauseTask.duration().orElse(null),
                pauseTask == null
                    ? null
                    : pauseTask.behavior().map(Enum::name).orElse(null)
            );
        }

        public String getId() {
            return id;
        }

        public String getKey() {
            return key;
        }

        public String getType() {
            return type;
        }

        public String getRoute() {
            return route;
        }

        public List<String> getDependOn() {
            return dependOn;
        }

        public List<DataView> getInputs() {
            return inputs;
        }

        public List<DataView> getOutputs() {
            return outputs;
        }

        public List<TaskView> getTasks() {
            return tasks;
        }

        public TaskView getPause() {
            return pause;
        }

        public List<DataView> getResume() {
            return resume;
        }

        public String getDuration() {
            return duration;
        }

        public String getBehavior() {
            return behavior;
        }
    }

    @Serdeable
    public static final class ExecutionView {

        private final String id;
        private final String flowId;
        private final long flowReversion;
        private final String state;
        private final long lockVersion;
        private final long createdAt;
        private final long updatedAt;
        private final List<HistoryView> history;
        private final List<TaskRunView> taskRuns;

        private ExecutionView(
            String id,
            String flowId,
            long flowReversion,
            String state,
            long lockVersion,
            long createdAt,
            long updatedAt,
            List<HistoryView> history,
            List<TaskRunView> taskRuns
        ) {
            this.id = id;
            this.flowId = flowId;
            this.flowReversion = flowReversion;
            this.state = state;
            this.lockVersion = lockVersion;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.history = List.copyOf(history);
            this.taskRuns = List.copyOf(taskRuns);
        }

        public static ExecutionView from(Execution execution) {
            List<State.History> stateHistory =
                execution.state().history();
            return new ExecutionView(
                execution.id(),
                execution.flowId(),
                execution.flowReversion(),
                execution.state().current().name(),
                execution.lockVersion(),
                stateHistory.getFirst().date(),
                stateHistory.getLast().date(),
                stateHistory.stream().map(HistoryView::from).toList(),
                execution.taskRuns().stream()
                    .map(TaskRunView::from)
                    .toList()
            );
        }

        public String getId() {
            return id;
        }

        public String getFlowId() {
            return flowId;
        }

        public long getFlowReversion() {
            return flowReversion;
        }

        public String getState() {
            return state;
        }

        public long getLockVersion() {
            return lockVersion;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }

        public List<HistoryView> getHistory() {
            return history;
        }

        public List<TaskRunView> getTaskRuns() {
            return taskRuns;
        }
    }

    @Serdeable
    public static final class TaskRunView {

        private final String id;
        private final String taskId;
        private final String parentId;
        private final String state;
        private final Map<String, Object> inputs;
        private final Map<String, Object> outputs;
        private final String error;
        private final long createdAt;
        private final long updatedAt;
        private final List<HistoryView> history;

        private TaskRunView(
            String id,
            String taskId,
            String parentId,
            String state,
            Map<String, Object> inputs,
            Map<String, Object> outputs,
            String error,
            long createdAt,
            long updatedAt,
            List<HistoryView> history
        ) {
            this.id = id;
            this.taskId = taskId;
            this.parentId = parentId;
            this.state = state;
            this.inputs = immutableMap(inputs);
            this.outputs = immutableMap(outputs);
            this.error = error;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.history = List.copyOf(history);
        }

        private static TaskRunView from(TaskRun taskRun) {
            List<State.History> stateHistory = taskRun.state().history();
            return new TaskRunView(
                taskRun.id(),
                taskRun.taskId(),
                taskRun.parentId().orElse(null),
                taskRun.state().current().name(),
                taskRun.inputs(),
                taskRun.outputs(),
                taskRun.error().orElse(null),
                stateHistory.getFirst().date(),
                stateHistory.getLast().date(),
                stateHistory.stream().map(HistoryView::from).toList()
            );
        }

        public String getId() {
            return id;
        }

        public String getTaskId() {
            return taskId;
        }

        public String getParentId() {
            return parentId;
        }

        public String getState() {
            return state;
        }

        public Map<String, Object> getInputs() {
            return inputs;
        }

        public Map<String, Object> getOutputs() {
            return outputs;
        }

        public String getError() {
            return error;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }

        public List<HistoryView> getHistory() {
            return history;
        }
    }

    @Serdeable
    public static final class HistoryView {

        private final String state;
        private final long date;

        private HistoryView(String state, long date) {
            this.state = state;
            this.date = date;
        }

        private static HistoryView from(State.History history) {
            return new HistoryView(
                history.state().name(),
                history.date()
            );
        }

        public String getState() {
            return state;
        }

        public long getDate() {
            return date;
        }
    }

    @Serdeable
    public static final class ErrorView {

        private final String message;

        public ErrorView(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    private static String actorName(ActorRef actor) {
        return actor.name().orElse(actor.id());
    }

    private static Map<String, Object> immutableMap(
        Map<String, Object> source
    ) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
