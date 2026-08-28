# Route 与 Condition 开源框架参考

## 调研范围与基线

本文只参考开源框架的官方文档、官方源码，以及 JsonLogic 的官方文档和官方实现仓库，
用于回答以下设计问题：

- 简单条件以及 `AND`、`OR`、`NOT` 组合条件如何表达；
- `if/else`、`switch/case/default` 如何建模；
- 条件究竟属于节点、分支关系还是网关；
- first-match、互斥选择、多分支匹配和无匹配分别如何处理；
- 哪些做法适合当前 `Route extends Branch`、`Branch` 拥有 `tasks` 的方向。

资料以 2026-08-27 可访问版本为准，覆盖 Camunda 8、Flowable BPMN、Netflix
Conductor、Argo Workflows 和 JsonLogic。本文是设计参考，不是当前项目的最终 ADR，也不
定义实现类或持久化格式。

## 结论摘要

开源框架反复验证了一个关键边界：

> `AND/OR/NOT` 解决的是“一个条件如何得到一个结果”；first-match、all-match 和
> default 解决的是“多个候选分支如何被选择”。两者不是同一层能力。

Camunda 和 Flowable 把布尔条件放在网关的出向连线上，网关本身只定义“取第一条”还是
“取所有命中分支”的策略。Conductor 采用另一种等价拆法：Switch 节点计算一个 case
值，再用 `decisionCases` 把值映射到任务列表。Argo 则把 `when` 放在每个具体 Step/DAG
Task 上，每个节点独立决定是否执行。JsonLogic 只解决可序列化的递归规则计算，本身不负责
工作流分支选择。

因此，对当前项目可以直接得出两个互斥定义：

1. 如果 `Route` 只有一个返回 boolean 的 `Condition`，那么它是“条件作用域”：条件为
   true 时进入继承的 `tasks`，否则跳过整个 Route。它可以自然表达单个 `if`，但自身不是
   多路选择器。
2. 如果 `Route` 要独立覆盖完整的 `if/else` 和 `switch/case/default` 多路选择，那么仅有
   一个 boolean `Condition` 不足以说明每个子 Task 何时被选中；还必须表达“条件与目标
   Task 的对应关系”，或者让一个 selector 返回目标 case/task key。

这不是 Java 类型选择问题，而是信息是否完整的问题。

## 框架对照

| 框架 | 简单/组合条件 | 条件归属 | 分支选择 | 无匹配 |
| --- | --- | --- | --- | --- |
| Camunda 8 Exclusive Gateway | 出向流上的 FEEL boolean；支持 `and`、`or`，并有 `not()` | 条件属于出向 Sequence Flow；gateway 引用 default flow | 按 BPMN XML 顺序取第一条 true | 走 default；无 default 创建 incident |
| Camunda 8 Inclusive Gateway | 同上 | 同上 | 取所有条件为 true 的流 | 走 default；无 default 创建 incident |
| Flowable Exclusive Gateway | 出向流上的 boolean/UEL 表达式 | 条件属于出向 Sequence Flow | 按定义顺序取第一条 true | 走 default；无 default 抛异常 |
| Flowable Inclusive Gateway | 同上 | 同上 | 取所有条件为 true 的流并行执行 | 走 default；无 default 抛异常 |
| Conductor Switch | 一个 value-param 或 JavaScript evaluator 产生 case 值 | evaluator、case 映射和子任务均属于 Switch 节点 | case 值精确映射到一个任务序列 | 执行 `defaultCase`，允许为空列表 |
| Argo Workflows | 每个 Step/DAG Task 自带 `when`；表达式可组合 `&&`、`||` | 条件属于候选节点 | 每个节点独立求值；所有 true 节点都可执行 | false 节点为 Skipped；没有 gateway 级 default |
| JsonLogic | 单操作 JSON 节点递归嵌套；`var`、比较、`and/or/!` | 属于独立规则对象 | 不负责选择工作流分支 | 由调用方决定 |

上表的 Camunda 语义来自官方 [Exclusive Gateway](https://docs.camunda.io/docs/components/modeler/bpmn/exclusive-gateways/)、
[Inclusive Gateway](https://docs.camunda.io/docs/components/modeler/bpmn/inclusive-gateways/) 和
[Boolean functions](https://docs.camunda.io/docs/components/modeler/feel/builtin-functions/feel-built-in-functions-boolean/)；
Flowable 语义来自官方 [BPMN 2.0 Constructs](https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs/)；
Conductor 语义来自官方 [Switch Task](https://docs.conductor-oss.org/documentation/configuration/workflowdef/operators/switch-task.html)；
Argo 语义来自官方 [Conditionals](https://argo-workflows.readthedocs.io/en/latest/walk-through/conditionals/)
和 [Field Reference](https://argo-workflows.readthedocs.io/en/latest/fields/)；JsonLogic 结构来自
[官方实现 README](https://github.com/jwadhams/json-logic-js/) 和
[Supported Operations](https://jsonlogic.com/operations)。

## 1. Camunda 8：条件在分支关系上，Gateway 定义选择策略

Camunda Exclusive Gateway 的每一条非默认出向 Sequence Flow 都有自己的
`conditionExpression`。Gateway 按 BPMN XML 声明顺序求值，选择第一条返回 true 的流；没有
命中时走明确指定的 default flow，没有 default 则创建 incident。也就是说，条件属于“从
gateway 到目标节点的分支关系”，first-match 属于 gateway。
[Camunda Exclusive Gateway](https://docs.camunda.io/docs/components/modeler/bpmn/exclusive-gateways/)

Inclusive Gateway 复用相同的条件表达方式，但会选择所有条件为 true 的出向流；没有命中时
仍可走 default，否则产生 incident。因此 boolean 条件模型相同，Exclusive/Inclusive 的差异
只在“选择第一条”与“选择全部”这项策略上。
[Camunda Inclusive Gateway](https://docs.camunda.io/docs/components/modeler/bpmn/inclusive-gateways/)

一条 Camunda 条件是返回 boolean 的 FEEL 表达式，官方示例直接使用比较、`and` 和 `or`；
逻辑取反由 `not(boolean)` 提供。组合逻辑仍然只决定这一条出向流是否匹配，不决定其他出向流
是否继续参与求值。
[Exclusive Gateway Conditions](https://docs.camunda.io/docs/components/modeler/bpmn/exclusive-gateways/#conditions)
[Camunda Boolean functions](https://docs.camunda.io/docs/components/modeler/feel/builtin-functions/feel-built-in-functions-boolean/)

`if/else` 在这里是两个候选流：`if` 流带条件，`else` 流标记为 default；`switch/case` 是多个
带条件的出向流加一个可选 default。两者的统一点不是“把所有 case 塞进一个 boolean 条件”，
而是共用一组“有序候选分支 + 默认分支”的选择协议。

### 可借鉴

- Condition 内部组合与 Route 跨分支选择分开设计。
- default 是显式分支元数据，不需要写成所有 case 的反条件。
- first-match 顺序必须成为稳定、可校验的定义，而不能依赖无序集合。
- Exclusive 与 Inclusive 可以复用同一种 Condition，但使用不同匹配策略。

### 不建议照搬

- 当前项目使用嵌套 Task 定义，而不是 BPMN 图，不必为了借鉴条件归属而完整引入
  `SequenceFlow`、token 和 gateway join 模型。
- FEEL 是字符串表达式语言；当前设计采用受限 Condition 语法，不应只替换解析器后
  继续保留通用表达式引擎的边界问题。

## 2. Flowable：进一步证明匹配策略独立于条件形态

Flowable Exclusive Gateway 同样按定义顺序检查出向 Sequence Flow，只选择第一条 true；
未命中时使用 default。官方文档和实现都把 `conditionExpression` 放在 `SequenceFlow` 上，
而不是 Gateway 上。
[Flowable BPMN Exclusive Gateway](https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs/#exclusive-gateway)
[ExclusiveGatewayActivityBehavior 源码](https://github.com/flowable/flowable-engine/blob/main/modules/flowable-engine/src/main/java/org/flowable/engine/impl/bpmn/behavior/ExclusiveGatewayActivityBehavior.java)
[SequenceFlow 源码](https://github.com/flowable/flowable-engine/blob/main/modules/flowable-bpmn-model/src/main/java/org/flowable/bpmn/model/SequenceFlow.java)

Flowable Inclusive Gateway 会评估所有出向条件，并为所有 true 分支创建并发执行；无命中且没有
default 时抛异常。官方文档还指出，普通活动离开时本来就可能选择所有条件为 true 的出向流，
Exclusive Gateway 覆盖这一通用行为后才变成 first-match。这说明“Condition 如何求值”和
“求值后取几个结果”是两个可独立变化的维度。
[Flowable Conditional Sequence Flow](https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs/#conditional-sequence-flow)
[Flowable Inclusive Gateway](https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs/#inclusive-gateway)
[InclusiveGatewayActivityBehavior 源码](https://github.com/flowable/flowable-engine/blob/main/modules/flowable-engine/src/main/java/org/flowable/engine/impl/bpmn/behavior/InclusiveGatewayActivityBehavior.java)

Flowable 的条件内容交给表达式语言处理；官方入门示例使用 `${approved}` 与
`${!approved}` 表达两个互斥分支。它证明 NOT 可以存在于单条条件内部，但手工为 else 重复写
反条件仍容易随 case 增加而失配。
[Flowable Getting Started](https://www.flowable.com/open-source/docs/bpmn/ch02-GettingStarted/)

### 可借鉴

- Route 校验应确保条件引用的目标是自己的直接子 Task。
- first-match 的声明顺序、default 唯一性以及无匹配策略都是 Route 不变量。
- 如果未来需要 all-match，可以复用 Condition 并增加匹配策略，而不是复制一套表达式模型。

### 不建议照搬

- 不把 UEL、Spring Bean 或方法调用暴露给流程定义；这会扩大可执行面，并削弱静态校验能力。
- 不要求设计者用 `NOT(caseA OR caseB ...)` 手工维护 default；default 应是协议，而不是派生表达式。

## 3. Conductor：一个 selector 加 case 映射覆盖 if/else 与 switch/case

Conductor 的 `SWITCH` 是一个拥有子任务的结构节点。它先用 `value-param` 或 JavaScript
evaluator 计算一个结果，再用 `decisionCases: Map<String, List<Task>>` 找到匹配分支；无匹配时
执行 `defaultCase`。官方明确把它同时定义为 `if...then...else` 和 `switch...case` 的表达方式。
[Conductor Switch Task](https://docs.conductor-oss.org/documentation/configuration/workflowdef/operators/switch-task.html)

这个模型没有“按顺序测试多个 boolean 条件”的歧义：evaluator 只产生一个 case 值，Map 只
选择一个分支。复杂 `AND/OR/NOT` 可以写进 JavaScript evaluator，但它们没有形成可验证的
结构化 Condition 树。
[Conductor evaluator 与字段定义](https://docs.conductor-oss.org/documentation/configuration/workflowdef/operators/switch-task.html#task-parameters)

`if/else` 可以让 evaluator 返回两个 case 值；`switch/case` 直接返回业务 case 值。默认分支
可以是空任务列表，因此无匹配也可以明确表示“什么都不做”。
[Conductor Switch 示例](https://docs.conductor-oss.org/documentation/configuration/workflowdef/operators/switch-task.html#examples)

### 可借鉴

- `Route extends Branch` 与 Conductor Switch 的结构很接近：选择器和候选子任务由同一个结构
  Task 管理。
- 如果坚持 Route 只持有一个决策对象，该对象应返回 case/task key，而不是只返回 boolean。
- default 使用独立分支，不与普通条件混在一起。

### 不建议照搬

- 不直接引入 JavaScript/GraalJS 作为条件运行时。
- 不照搬 `Map<String, List<Task>>` 造成 Task 定义只能嵌在 Map 中；当前项目已经决定由
  `Branch.tasks` 统一管理子 Task，更合适的是让路由规则引用这些直接子 Task。
- 不把“返回 boolean 的 Condition”和“返回 case key 的 selector”命名为同一个类型；它们的
  输出契约不同。

## 4. Argo Workflows：每个候选节点独立带 when

Argo 在每个 Workflow Step 或 DAG Task 上提供 `when` 字段。条件为 false 的节点标记为
Skipped；同一并行步骤组中的多个节点分别求值，因此所有条件为 true 的节点都可能执行，并
没有网关级 first-match 或 default。
[Argo Field Reference](https://argo-workflows.readthedocs.io/en/latest/fields/)
[Argo Conditionals](https://argo-workflows.readthedocs.io/en/latest/walk-through/conditionals/)

官方 coin-flip 示例把 `heads` 和 `tails` 条件直接放在两个候选 Step 上，并展示使用 `&&`、
`||` 和括号形成复合条件。Argo 的 `depends` 还支持 `&&`、`||`、`!`，但那是 DAG 节点状态依赖，
不是普通数据路由条件，不能与 Route Condition 合并。
[Argo Conditionals](https://argo-workflows.readthedocs.io/en/latest/walk-through/conditionals/)
[Argo Enhanced Depends Logic](https://argo-workflows.readthedocs.io/en/latest/enhanced-depends-logic/)

### 可借鉴

- `Route(condition, tasks)` 若定义为“条件作用域”，与 Argo 的 guarded Step 很接近：条件只决定
  这个结构节点是否进入。
- 条件不成立必须有明确运行语义，例如 Skip/Omit，而不能表现为未解释的调度缺失。
- 数据条件与 DAG 依赖条件必须分离，符合当前 `dependOn` 只属于 DAG 的方向。

### 不建议照搬

- 不把条件重新放回所有 `Task`；当前目标正是只让特定结构类型拥有特定字段。
- 不用一组独立 `when` 假装成 first-match。条件重叠时 Argo 会运行多个分支，这与互斥 Route
  不同。
- 不把 Argo 的字符串表达式语法当作新的领域模型。

## 5. JsonLogic：适合借鉴 Condition 树，不负责 Route 选择

JsonLogic 的每个规则对象只有一个操作符 key；操作数通常是数组，操作数本身又可以是规则，
因此天然形成递归树。简单比较可以写成 `{"==": [{"var": "status"}, "APPROVED"]}`，组合条件
可以用 `and`、`or` 和 `!` 继续嵌套。`var` 明确区分数据引用与字面量。
[json-logic-js README](https://github.com/jwadhams/json-logic-js/)
[JsonLogic Supported Operations](https://jsonlogic.com/operations)

JsonLogic 还提供一个有序 `if` 操作：参数可按 `if/then, else-if/then, else` 成对排列。这是
first-match 的值计算，而 `and/or` 是单个规则内部的逻辑组合；官方把它们定义为不同操作。
[JsonLogic if](https://jsonlogic.com/operations.html#if-logic)
[JsonLogic and/or/not](https://jsonlogic.com/operations.html#and-logic)

### 可借鉴

- Condition 可以是递归代数：叶子比较、数据引用、逻辑组合。
- 每个节点只表达一个操作，避免一个对象同时出现 `operator`、`conditions`、`left`、`right`
  等互斥字段。
- `AND`、`OR` 使用子 Condition 列表；`NOT` 使用一个子 Condition。
- 数据引用应是明确值对象，而不是靠字符串前缀解析 `inputs.`、`outputs.`、`variables.`。
- 结构化规则便于序列化、Schema 生成、类型校验和前端规则编辑。

### 不建议照搬

- 不直接使用任意 JSON Map 作为核心 Domain；Java 领域层仍需要受控类型和不变量。
- 不照搬 JsonLogic 的弱类型、truthy/falsy 和宽松类型转换；工作流路由应要求明确 boolean，
  比较两侧类型必须兼容。
- 不把 JsonLogic `if` 的返回任意值能力塞进 boolean `Condition`；需要选择 case 时应定义单独的
  decision/selector 契约。
- 不把 JsonLogic 的 `and/or` 误解成 Route 的“匹配多个分支”。前者只产生一个规则结果。

## 6. 对当前模型的适配判断

### 6.1 可以直接确认的部分

```text
Branch extends Task
└── tasks

Route extends Branch
└── Route 自有的路由定义
```

`tasks` 放在 `Branch` 是合理的；Conductor Switch 也把候选任务序列包含在选择节点中，BPMN
则由 Gateway 的出向关系指向候选节点。两种框架都没有把子任务集合放回所有 Task。

新的 Condition 可以借鉴 JsonLogic 的递归结构，但只负责返回 boolean：

```text
Condition
├── ComparisonCondition
│   ├── left: ValueRef | Literal
│   ├── operator
│   └── right: ValueRef | Literal
└── LogicalCondition
    ├── operator: AND | OR | NOT
    └── conditions
```

这里的 `AND/OR/NOT` 是 Condition 内部能力，与 Route 选择多少个 Task 无关。

### 6.2 `Route` 只有一个 boolean Route Rule 时的准确语义

```text
Route
├── route: String
├── Condition  // 校验或匹配时按需形成
└── tasks: List<Task>  // inherited
```

这套字段完整表达的是：

> 需要判断 Route 时才把 route 解析为 Condition 并求值；true 时进入整个 Route
> 作用域并执行其 tasks，false 时跳过整个作用域。

它与 Argo `when`/条件 Step 的模型最接近。它能直接覆盖 `if`，也可以用多个同级 Route 模拟
case，但有三个限制：

- 两个条件同时为 true 时，除非父结构另有规则，否则两个 Route 都会执行；
- `else/default` 只能手工写成前面所有条件的反条件；新增 case 时还必须同步修改 default；
- 一个 Route Rule 形成的单个 boolean 结果无法说明它的 N 个直接子 Task
  中应选择哪一个。

所以这时应把它称为“条件分支/条件作用域”，而不是“多路选择器”。

### 6.3 `Route` 要成为多路选择器时缺少的信息

若目标语义是 `if/else`、`switch/case/default` 的统一多路选择，模型至少必须保留以下两种方案
之一。

**方案 A：有序条件分支。**

```text
Route
├── tasks
├── branches
│   └── condition + targetTaskKey
├── defaultTaskKey
└── matchPolicy: FIRST | ALL
```

这借鉴 BPMN，但可以只建轻量的分支关系，不引入完整 Sequence Flow。`FIRST` 覆盖普通
if/else-if/else 和 switch/case/default；`ALL` 覆盖包容路由。

**方案 B：单 selector。**

```text
Route
├── tasks
├── selector
├── caseValue -> targetTaskKey
└── defaultTaskKey
```

这借鉴 Conductor。if/else 是 selector 产生 true/false 或两个业务 case，switch/case 是
selector 产生普通 case 值。

两种方案都能保持 `tasks` 只归 `Branch` 所有。它们也都说明：Route 可以只有一个“决策入口”，
但这个入口若要选择 N 个分支，就不能只是一个返回 boolean 的 Condition。

## 7. 推荐借鉴边界

当前讨论阶段最稳妥的结论是：

1. 采用 JsonLogic 的思想设计结构化、递归、强类型的 Condition；不采用它的任意 Map 和弱类型
   求值。
2. Condition 只返回 boolean，内部支持 Comparison、AND、OR，并建议保留一元 NOT，避免为了
   else/default 展开复杂反条件。
3. 明确把 `Condition` 的逻辑组合与 `Route` 的分支匹配策略分开。
4. 若 Route 只保留一个 boolean `route`，就把它定义为条件作用域，不声称它自身提供完整
   switch/default 语义。
5. 若 Route 必须是多路选择器，则在 BPMN 的“条件 + 目标”与 Conductor 的“selector + case
   映射”之间选一种；不要用一个 boolean Condition 隐式承担二者。
6. default 应是一等协议，不建议通过 `NOT(caseA OR caseB...)` 推导。
7. 无匹配策略必须显式确定为正常空完成、使用 default，或定义错误；不能让 Executor 自行猜测。

## 最终判断

`Route extends Branch`、`Branch` 统一拥有 `tasks` 是有成熟框架依据的方向。最值得借鉴的组合是：

- 从 JsonLogic 借鉴 Condition 的递归结构；
- 从 Camunda/Flowable 借鉴“单分支条件”和“跨分支选择策略”的分层；
- 从 Conductor 借鉴 Route 作为包含候选任务的结构节点，以及显式 default；
- 从 Argo 吸取反例边界：节点级独立 condition 天然是 all-match，不会自动得到 first-match。

因此，当前首先需要确认的不是 Condition 还要增加哪些比较符，而是 `Route` 的领域定义究竟是
“一个条件作用域”还是“多个候选中的选择器”。当前已确认前者，由“一个 boolean
`route` + 按需形成的 Condition + 继承的 tasks”完整表达；后者还需要一层明确的分支映射或
selector 结果契约。
