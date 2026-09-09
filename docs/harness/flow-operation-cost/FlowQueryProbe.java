import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.cses.flow.controller.flow.FlowController;
import org.cses.flow.core.domains.flows.Flow;
import org.cses.flow.core.services.flows.FlowService;
import org.cses.flow.core.services.flows.queries.FlowQueryHandler;
import org.cses.flow.infrastructure.jooq.FlowJooqTestConfiguration;
import org.cses.flow.infrastructure.repositories.flows.FlowRepositoryImpl;
import org.cses.flow.infrastructure.repositories.flows.entries.FlowEntry;
import org.cses.flow.infrastructure.repositories.flows.entries.FlowTaskEntry;
import org.cses.flow.core.services.CommandExecutor;
import org.cses.flow.core.services.CommandHandlerRegistry;
import org.cses.flow.core.services.flows.commands.PublishFlowCommand;
import org.cses.flow.core.services.flows.handlers.PublishFlowHandler;
import org.cses.flow.core.services.flows.handlers.DeleteFlowHandler;
import org.cses.flow.core.plugins.DefaultPluginRegistry;
import org.cses.flow.core.plugins.PluginModule;
import org.cses.flow.core.serializers.JacksonMapper;
import org.cses.flow.core.serializers.YamlParser;
import org.cses.flow.core.validations.ModelValidator;
import org.cses.flow.extensions.log.Log;
import org.cses.flow.infrastructure.jooq.FlowDatabase;
import org.jooq.DSLContext;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.paas.session.Session;
import org.paas.session.User;
import org.x9.jooq.JOOQ;
import org.x9.jooq.JooqDSLContext;
import org.x9.jooq.intf.JooqRunnableResult;
import static org.flow.gen.flow.Tables.FLOWS;
import static org.flow.gen.flow.Tables.FLOW_TASKS;

/** Counts production SQL generation and task-tree scans; no database is contacted. */
public class FlowQueryProbe {
    public static void main(String[] args) {
        org.paas.json.JsonFactory.instance = io.micronaut.json.JsonMapper.createDefault();
        new JacksonMapper(new PluginModule(new DefaultPluginRegistry(List.of(new Log()))));
        User user = new User();
        user.setId("probe-user");
        user.setCompanyId("probe-company");
        user.setName("Diagnostic");
        Session<User> session = new Session<>();
        session.setUser(user);
        boolean excessive = false;
        for (boolean published : new boolean[]{false, true}) {
            for (int count : new int[]{1, 2, 10, 50}) {
                Fixture fixture = new Fixture(session, count, published);
                fixture.measure("service.list drafts=" + count + " published=" + published,
                    () -> fixture.service.drafts(session));
                fixture.measure("controller.list drafts=" + count + " published=" + published,
                    () -> {
                        if (fixture.controller.drafts(session).size() != count) throw new AssertionError("List size mismatch");
                    });
                excessive |= count > 1 && fixture.statements.size() > 3;
            }
        }
        for (boolean published : new boolean[]{false, true}) {
            Fixture fixture = new Fixture(session, 1, published);
            String source = source("flow-0");
            String suffix = " published=" + published;
            fixture.measure("domain.create+revise+initializeDeployed" + suffix, () -> {
                Flow draft = Flow.create(session, "flow-0", "", Map.of(), List.of(), List.of(), source);
                draft.revise("revised", Map.of(), List.of(), List.of(), source, session, draft.createdAt() + 1);
                Flow parsed = YamlParser.parse(source, Flow.class);
                parsed.initialize(session, false, fixture.deployed.get(0), source);
            });
            fixture.measure("service.saveDraft" + suffix, () -> fixture.service.save(session, PublishFlowCommand.from("flow-0", source)));
            fixture.measure("controller.saveDraft" + suffix, () -> fixture.controller.saveDraft(session, PublishFlowCommand.from("flow-0", source)));
            fixture.measure("service.publishSavedDraft" + suffix, () -> fixture.service.save(session, PublishFlowCommand.from("flow-0", false)));
            fixture.measure("controller.publishSavedDraft" + suffix, () -> fixture.controller.deploy(session, "flow-0"));
            fixture.measure("service.publishSource" + suffix, () -> fixture.service.save(session, PublishFlowCommand.from("flow-0", source, false)));
        }
        for (int count : new int[]{1, 10, 100, 1000}) measureSnapshotWrite(session, count);
        for (int count : new int[]{1, 10, 100, 1000}) measureTreeRestore(session, count);
        if (args.length > 0 && excessive) {
            throw new AssertionError("Flow list makes per-item database lookups; query count grows with list size");
        }
    }

    private static String source(String key) {
        return "key: " + key + "\ntasks:\n  - key: step\n    type: org.cses.flow.extensions.log.Log\n    message: diagnostic\n";
    }

    private static class Fixture {
        List<String> statements = new ArrayList<>();
        List<Flow> drafts = new ArrayList<>();
        List<Flow> deployed = new ArrayList<>();
        DSLContext records = FlowJooqTestConfiguration.configure(DSL.using(SQLDialect.POSTGRES));
        FlowService service;
        FlowController controller;

        Fixture(Session<User> session, int count, boolean published) {
            for (int index = 0; index < count; index++) {
                String key = "flow-" + index;
                Flow draft = Flow.create(session, key, "", Map.of(), List.of(), List.of(), source(key));
                drafts.add(FlowEntry.from(draft, "draft-" + index, 1).to());
                Flow formal = null;
                if (published) {
                    formal = YamlParser.parse(source(key), Flow.class);
                    formal.initialize(session, false, null, source(key));
                    formal = FlowEntry.from(formal, "formal-" + index, 2).to(formal.tasks());
                }
                deployed.add(formal);
            }
            MockConnection connection = new MockConnection(context -> {
                String sql = context.sql();
                statements.add(sql);
                if (sql.startsWith("with ")) {
                    Result stored = records.newResult(FLOWS.ID);
                    stored.add(records.newRecord(FLOWS.ID).value1("stored-id"));
                    return new MockResult[]{new MockResult(1, stored)};
                }
                if (sql.matches("(?s)select [^,]+\\.\"version\" from .*")) {
                    Result version = records.newResult(FLOWS.VERSION);
                    version.add(records.newRecord(FLOWS.VERSION).value1(published ? 2L : 1L));
                    return new MockResult[]{new MockResult(1, version)};
                }
                String key = Arrays.stream(context.bindings()).filter(String.class::isInstance).map(String.class::cast)
                    .filter(value -> value.startsWith("flow-")).findFirst().orElse("flow-0");
                int index = Integer.parseInt(key.substring(5));
                if (sql.contains("\"flow_tasks\"")) {
                    Result tasks = records.newResult(FLOW_TASKS.fields());
                    Flow formal = deployed.get(index);
                    if (formal != null) tasks.add(FlowTaskEntry.from(formal.companyId(), key, 2, formal.tasks().get(0), null, 0).toRecord());
                    return new MockResult[]{new MockResult(tasks.size(), tasks)};
                }
                Result flows = records.newResult(FLOWS.fields());
                if (sql.contains("not exists")) {
                    for (Flow draft : drafts) flows.add(FlowEntry.from(draft, draft.id(), 1).toRecord());
                } else {
                    Flow selected = Arrays.asList(context.bindings()).contains(Boolean.TRUE) ? drafts.get(index) : deployed.get(index);
                    if (selected != null) flows.add(FlowEntry.from(selected, selected.id(), selected.version()).toRecord());
                }
                return new MockResult[]{new MockResult(flows.size(), flows)};
            });
            JooqDSLContext dsl = new JooqDSLContext(
                FlowJooqTestConfiguration.configure(DSL.using(connection, SQLDialect.POSTGRES)).configuration(), null);
            JOOQ jooq = new JOOQ(dsl.configuration(), null) {
                @Override public <T> T get(JooqRunnableResult<T> callback) { return FlowQueryProbe.run(callback, dsl); }
                @Override public <T> List<T> read(JooqRunnableResult<List<T>> callback) { return FlowQueryProbe.run(callback, dsl); }
                @Override public JooqDSLContext createDSLContext() { return dsl; }
            };
            FlowRepositoryImpl repository = new FlowRepositoryImpl();
            ModelValidator validator = new ModelValidator(io.micronaut.validation.validator.Validator.getInstance());
            CommandExecutor executor = new CommandExecutor(jooq, new CommandHandlerRegistry(List.of(
                new PublishFlowHandler(repository, validator), new DeleteFlowHandler(repository))));
            service = new FlowService(executor, new FlowQueryHandler(jooq, repository));
            controller = new FlowController(service);
        }

        void measure(String label, Runnable operation) {
            statements.clear();
            operation.run();
            System.out.println("PROBE " + label + " sql=" + statements.size());
        }
    }

    /**
     * 在同一仓储会话中统计首次保存与 CAS 更新的 SQL 规模。
     * @param session 测试身份
     * @param count 子记录数量
     */
    private static void measureSnapshotWrite(Session<User> session, int count) {
        var execution = org.cses.flow.core.domains.executions.Execution.create("snapshot-probe", session, "flow-0", 2, Map.of());
        List<org.cses.flow.core.domains.executions.TaskRun> runs = new ArrayList<>();
        for (int index = 0; index < count; index++) runs.add(org.cses.flow.core.domains.executions.TaskRun.create("task-" + index, null, Map.of()));
        execution.startWithTaskRuns(runs);
        DSLContext records = FlowJooqTestConfiguration.configure(DSL.using(SQLDialect.POSTGRES));
        var id = org.flow.gen.flow.Tables.EXECUTIONS.ID;
        var lock = org.flow.gen.flow.Tables.EXECUTIONS.LOCK;
        long[] savedLock = {-1L};
        MockConnection connection = new MockConnection(context -> {
            System.out.println("PROBE execution.snapshot taskRuns=" + count + " sql=1 sqlChars=" + context.sql().length()
                + " bindings=" + context.bindings().length);
            var result = records.newResult(id, lock);
            result.add(records.newRecord(id, lock).values(execution.id(), ++savedLock[0]));
            return new MockResult[]{new MockResult(1, result)};
        });
        var dsl = FlowJooqTestConfiguration.configure(DSL.using(connection, SQLDialect.POSTGRES));
        var repository = new org.cses.flow.infrastructure.repositories.executions.ExecutionRepositoryImpl();
        FlowDatabase.execute(dsl, scoped -> {
            repository.save(scoped, execution);
            execution.startTaskRun(runs.get(0).id());
            repository.save(scoped, execution);
            return null;
        });
    }

    private static <T> T run(JooqRunnableResult<T> callback, JooqDSLContext dsl) {
        try { return callback.run(dsl); }
        catch (java.sql.SQLException error) { throw new IllegalStateException(error); }
    }

    private static void measureTreeRestore(Session<User> session, int count) {
        StringBuilder source = new StringBuilder("key: tree-probe\ntasks:\n");
        for (int index = 0; index < count; index++) {
            source.append("  - key: task-").append(index)
                .append("\n    type: org.cses.flow.extensions.log.Log\n    message: diagnostic\n");
        }
        Flow flow = YamlParser.parse(source.toString(), Flow.class);
        flow.initialize(session, false, null, source.toString());
        List<FlowTaskEntry> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            rows.add(FlowTaskEntry.from(flow.companyId(), flow.key(), 1, flow.tasks().get(index), null, index));
        }
        long[] reads = {0};
        List<FlowTaskEntry> counted = new java.util.AbstractList<>() {
            @Override public FlowTaskEntry get(int index) { reads[0]++; return rows.get(index); }
            @Override public int size() { return rows.size(); }
        };
        try {
            var method = FlowRepositoryImpl.class.getDeclaredMethod("readTasks", List.class, String.class, java.util.Set.class);
            method.setAccessible(true);
            var restored = (List<?>) method.invoke(new FlowRepositoryImpl(), counted, null, new java.util.HashSet<String>());
            if (restored.size() != count) throw new AssertionError("Restored task count mismatch");
            System.out.println("PROBE flow.restore tasks=" + count + " parentChecks=" + reads[0]);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }
}
