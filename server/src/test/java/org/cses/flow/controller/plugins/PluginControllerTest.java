package org.cses.flow.controller.plugins;

import org.cses.flow.controller.plugins.PluginModels.PluginDetailsView;
import org.cses.flow.controller.flow.InputViewTest;
import org.cses.flow.core.domains.flows.inputs.StringInput;
import java.util.Set;
import java.util.stream.Collectors;
import org.cses.flow.controller.plugins.PluginModels.RegisteredPluginView;
import org.cses.flow.core.plugins.DefaultPluginRegistry;
import org.cses.flow.core.plugins.PluginModule;
import org.cses.flow.core.serializers.JacksonMapper;
import org.cses.flow.core.serializers.PluginSchemaGenerator;
import org.cses.flow.core.services.plugins.PluginService;
import org.cses.flow.extensions.log.Log;
import org.cses.flow.extensions.flow.Parallel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginControllerTest {

    /** 查询包含自动发现 Input 的真实包目录，同时保留 Task 能力、示例与包路径。 */
    @Test
    void exposesRealPackagePathsInCatalogGroupsAndTaskMetadata() {
        DefaultPluginRegistry registry = new DefaultPluginRegistry(
            List.of(
                new Parallel(),
                new Log()
            )
        );
        JacksonMapper mapper = new JacksonMapper(
            new PluginModule(registry)
        );
        PluginController controller = new PluginController(
            new PluginService(
                registry,
                new PluginSchemaGenerator(mapper, registry)
            )
        );

        List<RegisteredPluginView> plugins = controller.plugins();

        assertEquals(
            List.of(
                InputViewTest.BusinessInput.class.getPackageName(),
                StringInput.class.getPackageName(),
                Parallel.class.getPackageName(),
                Log.class.getPackageName()
            ),
            plugins.stream()
                .map(RegisteredPluginView::packageName)
                .toList()
        );
        RegisteredPluginView builtIns = plugins.stream()
            .filter(group -> group.packageName().equals(StringInput.class.getPackageName())).findFirst().orElseThrow();
        assertEquals(Set.of("STRING", "BOOLEAN", "BYTE", "SHORT", "INTEGER", "LONG", "FLOAT", "DOUBLE", "CHARACTER"),
            builtIns.getInputs().stream().map(PluginModels.PluginMetadataView::type).collect(Collectors.toSet()));
        assertEquals(List.of(), builtIns.tasks());
        assertEquals("BUSINESS_VIEW", plugins.getFirst().getInputs().getFirst().type());
        assertEquals("STRING", controller.plugin("STRING").metadata().type());
        assertEquals(
            Parallel.class.getPackageName(),
            plugins.stream().filter(group -> group.packageName().equals(Parallel.class.getPackageName()))
                .findFirst().orElseThrow().tasks().getFirst().packageName()
        );
        assertEquals(
            Log.class.getPackageName(),
            plugins.getLast().tasks().getFirst().packageName()
        );
        PluginDetailsView parallel = controller.plugin(
            Parallel.class.getCanonicalName()
        );
        assertEquals(
            List.of("PARALLEL_CHILDREN"),
            parallel.metadata().capabilities()
        );

        PluginDetailsView details = controller.plugin(
            Log.class.getCanonicalName()
        );
        assertEquals(
            Log.class.getPackageName(),
            details.metadata().packageName()
        );
        assertEquals(1, details.examples().size());
        assertEquals(
            "记录流程消息",
            details.examples().getFirst().title()
        );
        assertEquals(
            List.of("""
                key: log-flow
                tasks:
                  - key: write-log
                    type: org.cses.flow.extensions.log.Log
                    message: "流程已进入自动处理阶段"
                """),
            details.examples().getFirst().code()
        );
        assertEquals("yaml", details.examples().getFirst().lang());
        assertEquals(true, details.examples().getFirst().full());
    }

    /** 插件详情公布代码推导的只读输出，配置 Schema 不接受 outputs 字段。 */
    @Test
    void exposesTypedOutputsSeparatelyFromEditableSchema() {
        DefaultPluginRegistry registry = new DefaultPluginRegistry(List.of(new ResultTask()));
        JacksonMapper mapper = new JacksonMapper(new PluginModule(registry));
        PluginController controller = new PluginController(new PluginService(registry,
            new PluginSchemaGenerator(mapper, registry)));
        PluginDetailsView details = controller.plugin(ResultTask.class.getCanonicalName());
        assertEquals(List.of(java.util.Map.of("key", "result", "type", "STRING")), details.getOutputs());
        java.util.Map<?, ?> properties = (java.util.Map<?, ?>) details.schema().get("properties");
        org.junit.jupiter.api.Assertions.assertFalse(properties.containsKey("outputs"));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
            () -> details.getOutputs().add(java.util.Map.of("key", "extra", "type", "STRING")));
    }

    /** 仅用于验证 HTTP 插件字段投影的具体输出任务。 */
    @org.cses.flow.core.plugins.annotations.Plugin
    public static class ResultTask extends org.cses.flow.core.domains.tasks.Task
        implements org.cses.flow.core.domains.tasks.RunnableTask<ResultOutput> {
        /**
         * 返回确定的结果字段。
         * @param context 本次上下文；此元数据样本不读取它
         * @return 包含固定字符串的新输出
         */
        @Override
        public ResultOutput run(org.cses.flow.core.runner.RunContext context) {
            return ResultOutput.from("ready");
        }
    }

    /** HTTP 详情中应识别的明确业务字段。 */
    public record ResultOutput(String result) implements org.cses.flow.core.domains.tasks.Output {
        /**
         * 创建只读输出。
         * @param result 结果字符串
         * @return 包含传入值的新输出
         */
        public static ResultOutput from(String result) {
            return new ResultOutput(result);
        }
    }
}
