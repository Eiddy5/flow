package org.cses.flow.controller.flow;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import lombok.Getter;
import lombok.Setter;
import org.cses.flow.core.domains.executions.Execution;
import org.cses.flow.core.domains.executions.Generation;
import org.cses.flow.core.domains.executions.TaskRun;
import org.cses.flow.core.domains.ActorRef;
import org.cses.flow.core.domains.flows.Data;
import org.cses.flow.core.domains.flows.DataType;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.domains.flows.Input;

import org.cses.flow.core.domains.flows.State;
import org.cses.flow.core.domains.flows.inputs.IntegerInput;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.services.executions.RewindResult;
import org.cses.flow.extensions.flow.Branch;
import org.cses.flow.extensions.flow.Pause;
import org.cses.flow.extensions.flow.Route;
import org.paas.json.SerializableObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP protocol models for the Flow management API.
 */
public class FlowModels {

    private FlowModels() {
    }

    @Getter
    @Setter
    public static class DraftView extends SerializableObject {

        private String id;
        private String flowKey;
        private long version;
        private String raw;
        private long createdAt;
        private long updatedAt;
        private String createdBy;
        private String updatedBy;
        private FlowView deployedFlow;

        /**
         * Creates one HTTP draft view from persisted Flow facts.
         *
         * @param id non-blank database row identifier
         * @param flowKey non-blank stable Flow key
         * @param version positive Repository-assigned draft version
         * @param raw non-blank raw Flow source retained as supplied
         * @param createdAt non-negative creation timestamp in milliseconds
         * @param updatedAt latest update timestamp in milliseconds
         * @param createdBy non-null display name of the creator
         * @param updatedBy non-null display name of the latest updater
         * @param deployedFlow independently mapped latest deployed Flow view,
         *        or {@code null} when none is active
         */
        private DraftView(
            String id,
            String flowKey,
            long version,
            String raw,
            long createdAt,
            long updatedAt,
            String createdBy,
            String updatedBy,
            FlowView deployedFlow
        ) {
            this.id = id;
            this.flowKey = flowKey;
            this.version = version;
            this.raw = raw;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.createdBy = createdBy;
            this.updatedBy = updatedBy;
            this.deployedFlow = deployedFlow;
        }

        /**
         * Maps a persisted draft and optional deployed Flow to an HTTP view.
         *
         * @param draft non-null persisted draft read without modification
         * @param deployedFlow latest deployed Flow read without modification,
         *        or {@code null}
         * @return a new draft view containing copied scalar facts and an
         *         independently mapped deployed view
         * @throws NullPointerException when {@code draft} is {@code null}
         */
        public static DraftView from(
            Flow draft,
            Flow deployedFlow
        ) {
            return new DraftView(
                draft.id(),
                draft.key(),
                draft.version(),
                draft.source(),
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

        public String getFlowKey() {
            return flowKey;
        }

        /**
         * Returns the Repository-assigned version of this draft row.
         *
         * @return positive draft version
         */
        public long getVersion() {
            return version;
        }

        public String getRaw() {
            return raw;
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

    @Getter
    @Setter
    public static final class DefinitionView extends SerializableObject {

        private final Map<String, Object> definition;

        public DefinitionView(Map<String, Object> definition) {
            this.definition = immutableMap(definition);
        }

        public Map<String, Object> getDefinition() {
            return definition;
        }
    }

    @Getter
    @Setter
    public static final class InputTypeView extends SerializableObject {

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

    @Getter
    @Setter
    public static final class InputFieldView extends SerializableObject {

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

    @Getter
    @Setter
    public static final class FlowView extends SerializableObject {

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

    @Getter
    @Setter
    public static final class DataView extends SerializableObject {

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

    @Getter
    @Setter
    public static final class TaskView extends SerializableObject {

        private final String id;
        private final String key;
        private final String type;
        private final String route;
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
            Branch branch = task instanceof Branch candidate
                ? candidate
                : null;
            Route route = task instanceof Route candidate
                ? candidate
                : null;
            return new TaskView(
                task.id(),
                task.key(),
                task.getType(),
                route == null ? null : route.route(),
                task.inputs().stream().map(DataView::from).toList(),
                task.outputs().stream().map(DataView::from).toList(),
                branch == null
                    ? List.of()
                    : branch.tasks().stream().map(TaskView::from).toList(),
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

    @Getter
    @Setter
    public static final class ExecutionView extends SerializableObject {

        private final String id;
        private final String flowKey;
        private final long flowVersion;
        private final String state;
        private final long createdAt;
        private final long updatedAt;
        private final GenerationView generation;
        private final List<HistoryView> history;
        private final List<TaskRunView> taskRuns;

        private ExecutionView(
            String id,
            String flowKey,
            long flowVersion,
            String state,
            long createdAt,
            long updatedAt,
            GenerationView generation,
            List<HistoryView> history,
            List<TaskRunView> taskRuns
        ) {
            this.id = id;
            this.flowKey = flowKey;
            this.flowVersion = flowVersion;
            this.state = state;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.generation = generation;
            this.history = List.copyOf(history);
            this.taskRuns = List.copyOf(taskRuns);
        }

        public static ExecutionView from(Execution execution) {
            List<State.History> stateHistory =
                execution.state().history();
            return new ExecutionView(
                execution.id(),
                execution.flowKey(),
                execution.flowVersion(),
                execution.state().current().name(),
                stateHistory.getFirst().date(),
                stateHistory.getLast().date(),
                GenerationView.from(execution.generation()),
                stateHistory.stream().map(HistoryView::from).toList(),
                execution.taskRuns().stream()
                    .map(TaskRunView::from)
                    .toList()
            );
        }

        public String getId() {
            return id;
        }

        public String getFlowKey() {
            return flowKey;
        }

        public long getFlowVersion() {
            return flowVersion;
        }

        public String getState() {
            return state;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }

        public GenerationView getGeneration() {
            return generation;
        }

        public List<HistoryView> getHistory() {
            return history;
        }

        public List<TaskRunView> getTaskRuns() {
            return taskRuns;
        }
    }

    @Getter
    @Setter
    public static class RewindView extends SerializableObject {

        ExecutionView execution;
        List<String> affectedTaskRunIds;

        private RewindView(
                ExecutionView execution,
                List<String> affectedTaskRunIds
        ) {
            this.execution = execution;
            this.affectedTaskRunIds = List.copyOf(affectedTaskRunIds);
        }

        public static RewindView from(RewindResult result) {
            return new RewindView(
                    ExecutionView.from(result.execution()),
                    result.affectedTaskRunIds()
            );
        }
    }

    @Getter
    @Setter
    public static final class TaskRunView extends SerializableObject {

        private final String id;
        private final String taskId;
        private final String parentTaskRunId;
        private final Integer iteration;
        private final Integer executionGenerationVersion;
        private final GenerationView generation;
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
            String parentTaskRunId,
            Integer iteration,
            Integer executionGenerationVersion,
            GenerationView generation,
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
            this.parentTaskRunId = parentTaskRunId;
            this.iteration = iteration;
            this.executionGenerationVersion = executionGenerationVersion;
            this.generation = generation;
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
                taskRun.iteration().isPresent()
                    ? taskRun.iteration().getAsInt()
                    : null,
                taskRun.executionGenerationVersion().isPresent()
                    ? taskRun.executionGenerationVersion().getAsInt()
                    : null,
                GenerationView.from(taskRun.generation()),
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
            return parentTaskRunId;
        }

        public Integer getIteration() {
            return iteration;
        }

        public Integer getExecutionGenerationVersion() {
            return executionGenerationVersion;
        }

        public GenerationView getGeneration() {
            return generation;
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

    @Getter
    @Setter
    public static final class GenerationView extends SerializableObject {

        private final GenerationCurrentView current;
        private final List<GenerationCurrentView> history;

        private GenerationView(
            GenerationCurrentView current,
            List<GenerationCurrentView> history
        ) {
            this.current = current;
            this.history = List.copyOf(history);
        }

        private static GenerationView from(Generation generation) {
            return new GenerationView(
                generation.current()
                    .map(GenerationCurrentView::from)
                    .orElse(null),
                generation.history().currents().stream()
                    .map(GenerationCurrentView::from)
                    .toList()
            );
        }

        public GenerationCurrentView getCurrent() {
            return current;
        }

        public List<GenerationCurrentView> getHistory() {
            return history;
        }
    }

    @Getter
    @Setter
    public static final class GenerationCurrentView
        extends SerializableObject {

        private final int version;
        private final String sourceTaskRunId;
        private final String targetTaskRunId;
        private final String reason;
        private final long date;

        private GenerationCurrentView(
            int version,
            String sourceTaskRunId,
            String targetTaskRunId,
            String reason,
            long date
        ) {
            this.version = version;
            this.sourceTaskRunId = sourceTaskRunId;
            this.targetTaskRunId = targetTaskRunId;
            this.reason = reason;
            this.date = date;
        }

        private static GenerationCurrentView from(
            Generation.Current current
        ) {
            return new GenerationCurrentView(
                current.version(),
                current.sourceTaskRunId().orElse(null),
                current.targetTaskRunId().orElse(null),
                current.reason(),
                current.date()
            );
        }

        public int getVersion() {
            return version;
        }

        public String getSourceTaskRunId() {
            return sourceTaskRunId;
        }

        public String getTargetTaskRunId() {
            return targetTaskRunId;
        }

        public String getReason() {
            return reason;
        }

        public long getDate() {
            return date;
        }
    }

    @Getter
    @Setter
    public static final class HistoryView extends SerializableObject {

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

    private static String actorName(ActorRef actor) {
        return actor.name().orElse(actor.id());
    }

    private static Map<String, Object> immutableMap(
        Map<String, Object> source
    ) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
