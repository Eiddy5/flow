package org.cses.flow.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.cses.flow.definition.model.Flow;
import org.cses.flow.definition.model.Node;
import org.cses.flow.definition.model.NodeType;
import org.cses.flow.runtime.engine.FlowEngine;
import org.cses.flow.runtime.execution.CommandExecutor;
import org.cses.flow.runtime.execution.ExecutionRunner;
import org.cses.flow.runtime.model.Executor;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.model.TaskCompletedSignal;
import org.cses.flow.task.command.CompleteTaskRequest;
import org.cses.flow.task.service.TaskService;
import org.junit.jupiter.api.Test;

final class WorkflowArchitectureBoundaryTest {

    @Test
    void publicEntryPointsDependOnlyOnTheNextLayer() {
        assertEquals(
                CommandExecutor.class,
                onlyField(FlowEngine.class).getType());
        assertEquals(
                FlowEngine.class,
                onlyField(TaskService.class).getType());
        assertEquals(0, ExecutionRunner.class.getDeclaredFields().length);
    }

    @Test
    void entryPointsDoNotDependOnRepositoriesOrMemoryAdapters() {
        assertFalse(hasForbiddenDependency(FlowEngine.class));
        assertFalse(hasForbiddenDependency(TaskService.class));
        assertFalse(hasForbiddenDependency(CommandExecutor.class));
    }

    @Test
    void processOwnsThePublicRootExecutorCreationBoundary() throws NoSuchMethodException {
        Method rootFactory = Process.class.getDeclaredMethod(
                "createRootExecutor",
                String.class,
                Node.class);

        assertEquals(Process.class, rootFactory.getDeclaringClass());
        assertTrue(Modifier.isPublic(rootFactory.getModifiers()));
        assertFalse(Modifier.isStatic(rootFactory.getModifiers()));
        assertEquals(Executor.class, rootFactory.getReturnType());
        assertArrayEquals(
                new Class<?>[] {String.class, Node.class},
                rootFactory.getParameterTypes());

        Node startNode = new Node("start", "start", NodeType.START, Map.of());
        Flow flow = Flow.draft("flow", "flow-key", "flow", List.of(startNode), List.of());
        flow.markDeployed(1);
        Process process = new Process("process", flow, Map.of());
        Executor rootExecutor = process.createRootExecutor("executor", startNode);

        assertSame(rootExecutor, process.rootExecutor());
        assertEquals(List.of(rootExecutor), process.executors());
        assertEquals(process.id(), rootExecutor.processId());
        assertNull(rootExecutor.parentId());

        assertTrue(
                Arrays.stream(Executor.class.getDeclaredConstructors())
                        .map(Constructor::getModifiers)
                        .noneMatch(modifiers -> Modifier.isPublic(modifiers)
                                || Modifier.isProtected(modifiers)),
                "Executor must not expose public or protected constructors");
        assertFalse(
                hasApiDependency(FlowEngine.class, Executor.class),
                "FlowEngine must not create or hold Executor through its API");
    }

    @Test
    void taskCompletionContractsExposeExactlyTheExternalCompletionFields() {
        String[] componentNames = {"taskId", "result", "operatorId", "idempotencyKey"};
        Class<?>[] componentTypes = {String.class, Map.class, String.class, String.class};

        assertRecordComponents(CompleteTaskRequest.class, componentNames, componentTypes);
        assertRecordComponents(TaskCompletedSignal.class, componentNames, componentTypes);
    }

    private Field onlyField(Class<?> type) {
        assertEquals(1, type.getDeclaredFields().length);
        return type.getDeclaredFields()[0];
    }

    private boolean hasForbiddenDependency(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getName)
                .anyMatch(name -> name.contains("Repository") || name.contains("infrastructure.memory"));
    }

    private boolean hasApiDependency(Class<?> owner, Class<?> dependency) {
        Stream<Type> fieldTypes = Arrays.stream(owner.getDeclaredFields())
                .map(Field::getGenericType);
        Stream<Type> constructorParameterTypes = Arrays.stream(owner.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getGenericParameterTypes()));
        Stream<Type> methodTypes = Arrays.stream(owner.getDeclaredMethods())
                .flatMap(method -> Stream.concat(
                        Stream.of(method.getGenericReturnType()),
                        Arrays.stream(method.getGenericParameterTypes())));

        return Stream.of(fieldTypes, constructorParameterTypes, methodTypes)
                .flatMap(types -> types)
                .map(Type::getTypeName)
                .anyMatch(typeName -> typeName.contains(dependency.getName()));
    }

    private void assertRecordComponents(
            Class<?> recordType,
            String[] expectedNames,
            Class<?>[] expectedTypes) {
        assertTrue(recordType.isRecord(), () -> recordType.getName() + " must remain a record");
        RecordComponent[] components = recordType.getRecordComponents();
        assertArrayEquals(
                expectedNames,
                Arrays.stream(components).map(RecordComponent::getName).toArray(String[]::new));
        assertArrayEquals(
                expectedTypes,
                Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
    }
}
