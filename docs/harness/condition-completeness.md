# Condition 完成度验证

## 目的

本验证矩阵用于确认 Condition 按
[`ADR 0074`](../decisions/0074-separate-condition-and-structural-task-capabilities.md) 和
[`ADR 0076`](../decisions/0076-build-one-run-variable-tree-for-runtime-expressions.md)
形成唯一的受限布尔条件能力，并确认 Route 与 Loop Until 在各自的数据可见范围内复用
同一个解析和求值模型。Condition 不是脚本引擎，也不负责 DAG 或分支选择策略。

## 完成标准

- Condition 只能形成完整的比较节点或逻辑节点，创建后不可变且不保存运行时状态。
- 只有完整 `{{ path.to.value }}` 才形成引用；未包裹值形成常量，裸点分文本不能隐式读取
  Condition Context，右侧引用必须被拒绝。
- 字符串解析、规范化、错误位置、优先级、短路和基础 typed comparison 全部受测试保护。
- Route 从统一 RunVariables 树读取 `vars`、`inputs`、运行元数据和 Execution 中全部
  已完成 Task 的 `outputs`；Condition parser 不用固定 scope 或串行范围限制安全路径。
- Loop Until 只读取当前已收敛轮次的循环体 outputs，并保留最大轮数保护。
- Route 使用 `route` 原始字符串，正式发布或实际匹配时才形成 Condition；数据库保存
  与恢复不在当前验收范围。
- Condition 与 TemplateExpression 分属独立领域；Route 直接保存 `route` 原始字符串，
  条件判断不复用模板渲染能力。

## 场景矩阵

| 层次 | 必须覆盖的场景 | 主要测试 |
| --- | --- | --- |
| 领域结构 | 比较/逻辑形态互斥、显式引用/常量左右角色、Operand 不保存派生 kind、不可变字段与集合、值语义、无技术身份和运行状态 | `ConditionStructureTest`、`CoreArchitectureStandardTest` |
| 解析与规范化 | 任意安全根的 `{{ path.to.value }}` 显式引用、多段安全路径，裸 String/Boolean/Number 常量，需消歧义的带引号 String，六种比较、AND 优先于 OR、括号、同 Logical 扁平化、稳定 source | `ConditionParsingTest` |
| 安全拒绝 | 空文本、左侧裸路径、单等号、非法路径、未闭合括号、未知转义、右侧引用、方法、数组、算术、尾随文本及长度/深度/节点上限；未知根允许解析并在缺值时返回 false | `ConditionParsingTest`、`ConditionTest` |
| 求值 | 嵌套 Map、大小写、Boolean、Character、跨 Java Number 表现比较、缺值和类型不兼容为 false、AND/OR 顺序短路、并发读取隔离 | `ConditionTest` |
| 定义协议 | Condition 字符串序列化；Route `route` 原文绑定；YAML 物化和插件 Schema 使用 `flow-condition` | `ConditionSerializationTest`、`RouteTest`、`FlowMaterializationTest`、`PluginSchemaGeneratorTest` |
| Route | 草稿保留尚未完成的 `route`；正式发布按需解析并校验声明与类型；运行时先创建 Route TaskRun，再计算条件；不匹配时完成 Route TaskRun 且不创建子 TaskRun | `RouteTest`、`ConditionalRouteResumeIntegrationTest`、`Uc06ConditionalRouteTest`、`ExecutorServiceTest` |
| Loop Until | 循环体输出声明与类型校验；至少一轮；只用当前轮输出；满足、继续和达到上限失败 | `LoopUntilTest`、`Uc09LoopOrchestrationTest` |
| 变量对接 | RunVariables 九个规范顶层字段、Task key 输出分组、直接 parent、最近到最远 parents、深度不可变，以及 Condition/TemplateExpression 共用完整变量树 | `RunVariablesTest`、`RunContextTest`、`ExecutorServiceTest` |
| 持久化 | 当前阶段不设计或验收 Route 的数据库保存与恢复协议 | 后续单独补充 |
| 完整回归 | Task/Branch/Pause/Sequence/Parallel、Executor、Worker、API 和其他模块无回归 | `:core:test`、根项目 `test` |

## 执行命令

在 Java 21 和项目测试要求的 PostgreSQL 环境中依次执行：

```bash
./gradlew :core:test --tests '*Condition*Test'
./gradlew :core:test
./gradlew test
./gradlew build
```

本地无法访问集群 Consul 时，Micronaut AOT 会在 `build` 阶段读取生产 bootstrap 配置。
只对本次本地命令关闭配置读取、注册和 watcher，不修改生产配置：

```bash
MICRONAUT_CONFIG_CLIENT_ENABLED=false \
CONSUL_CLIENT_CONFIG_ENABLED=false \
CONSUL_CLIENT_REGISTRATION_ENABLED=false \
CONSUL_CLIENT_WATCH_SERVICE_ENABLED=false \
./gradlew build
```

数据库集成测试必须使用独立测试数据库，并按
[`PostgreSQL Repository 验证手册`](postgresql-repositories.md) 配置连接；不能因为本地
缺少数据库而把相关测试计为通过。

## 当前范围外

- 右侧变量引用、真正的 `null` 值字面量、NOT、异或、集合、正则、包含、数组、函数和
  算术；未包裹的 `null` 只是 String 常量。
- 部门“属于/不属于”等业务 Comparison，以及 Comparison 注册表或自动发现机制。
- Route 的单选、多选、默认分支策略。
- DAG、`dependOn` 和依赖输出域。
- JavaScript、SpEL、MVEL、Aviator 或其他任意代码执行能力。

新增能力前必须先更新领域决策和本矩阵，再增加对应的接受、拒绝、消费和恢复测试。
