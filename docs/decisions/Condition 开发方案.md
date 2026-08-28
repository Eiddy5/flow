# Condition 开发方案

## 文档定位

本文整理 Condition 基础领域能力的已确认设计和开发顺序，作为编码、测试和迁移工作的
执行依据。该方案已于 2026-08-28 完成落地；当前生效的结构决策见
[`ADR 0074`](0074-separate-condition-and-structural-task-capabilities.md)，完整验证矩阵见
[`Condition 完成度验证`](../harness/condition-completeness.md)。下文保留分阶段计划，便于
追溯实现顺序和验收范围。

Condition 的首要目标是把条件的定义、解析、类型比较和运行时求值集中到独立领域，供
Route、Loop Until 等流程 Task 复用。Condition 不是脚本引擎，不负责选择下一 Task，
也不拥有 Flow、Execution 或 TaskRun 的运行状态。

## 已确认的设计

- `Task` 只保留所有 Task 都具有的公共字段，不重新加入 `route`、`dependOn` 或
  `tasks`。
- `tasks` 属于 Branch 抽象基类；Route、Parallel、Sequence、Loop 和 Loop Until
  等结构型 Task 继承 Branch。
- `dependOn` 属于 DAG 能力，不属于 Condition，也不属于公共 Task。
- Route 持有必填 `route` 原始字符串，只在正式发布校验或实际匹配时解析为
  Condition；Condition 可以是一条直接比较，也可以通过 AND、OR 递归组合成树。
- 条件字符串是外部定义形式，解析后的 Condition Tree 是校验和执行模型。
- 运算优先级由 Condition Tree 的嵌套结构表达，不增加数字 `priority` 字段。
- 左操作数只接受完整 `{{ scope.path }}` 形式的运行时引用，右操作数只接受定义时确定的
  常量；未包裹内容不会隐式解析为引用。
- 第一期只完成基础比较，不实现部门“属于/不属于”等业务 Comparison，也不实现
  Comparison 插件发现和动态注册。

## 一期范围

### 支持范围

- 变量根：`variables`、`inputs`、`outputs`。
- 逻辑操作：`&&`、`||`。
- 分组：圆括号 `(...)`。
- 常量：String、Boolean、Number。
- String、Character 和 Boolean：`==`、`!=`。
- Number：`==`、`!=`、`>`、`>=`、`<`、`<=`。
- 从左到右的 AND、OR 短路求值。
- 条件定义的字符串序列化、恢复和错误定位。

### 明确不包含

- 部门、组织、人员等业务类型 Comparison。
- Comparison Plugin、ServiceLoader 或 Micronaut Bean 自动发现。
- 右操作数引用变量。
- `NOT`、异或或自定义逻辑操作。
- 真正的 `null` 值字面量，或对缺值执行相等、不等判断；未包裹的 `null` 只是 String
  常量。
- 集合、正则、字符串包含、数组下标、函数、方法调用和算术表达式。
- JavaScript、SpEL、MVEL、Aviator 等任意代码执行能力。
- Route 的多分支选择策略、DAG 依赖和 Branch 子 Task 调度。

## 目标代码位置

Condition 作为独立领域模块放在：

```text
core/src/main/java/org/cses/flow/core/domains/conditions/
```

建议的基础类型如下：

```text
conditions/
├── Condition.java
├── ConditionParser.java
├── Logical.java
├── Operand.java
├── OperandScope.java
├── Comparison.java
├── BasicComparison.java
└── ConditionContext.java
```

`ConditionParser` 保持包内可见，负责词法分析和递归下降解析；调用方统一通过
`Condition.parser(source)` 使用，不把解析中间状态扩散给 Route、Loop Until 或
Executor。路径读取和规范化等其他实现同样尽量保持包内可见。

`TemplateExpression` 继续留在 `core.domains.expressions`。布尔条件统一由
`core.domains.conditions` 中的 Condition 负责，不能把模板渲染和 boolean 条件合并。

## 领域模型

### Condition

Condition 是无技术身份、不可变、可递归的领域值对象。已确认的数据结构为：

```java
public class Condition {
    private Logical operator;

    private Operand left;

    private Comparison comparison;

    private Operand right;

    private List<Condition> conditions;
}
```

一个 Condition 只能处于以下两种完整形态之一。

#### 比较节点

```text
left + comparison + right
```

- `left` 必须是变量引用 Operand。
- `right` 必须是常量 Operand。
- `comparison` 必填。
- `operator` 不存在。
- `conditions` 为空。

#### 逻辑节点

```text
operator + conditions
```

- `operator` 只能是 AND 或 OR。
- `conditions` 至少包含两个 Condition。
- `left`、`comparison`、`right` 均不存在。

Condition 不允许同时成为比较节点和逻辑节点，也不允许处于字段只填充一部分的中间
状态。构造方法应隐藏，通过解析入口或受控静态工厂一次建立完整不变量。

### Logical

Logical 是封闭值域：

```text
AND
OR
```

一期不为 Logical 建立扩展机制。逻辑关系与具体的数据比较是两个不同维度，不能把
`AND`、`OR` 混入 Comparison。

### Operand

Operand 统一描述条件两侧的数据来源，但左右位置具有不同约束。

```text
Reference Operand
├── scope
└── path

Constant Operand
└── value
```

Operand 不保存额外的 kind 字段。完整 `{{ ... }}` 语法与比较式左侧位置确定 Reference，
右侧语法与位置确定 Constant；对象内部由 `scope + path` 或 `value` 的互斥结构保持合法
状态，不再重复保存可推导的分类枚举。

#### REFERENCE

- 只允许出现在 `left`。
- `scope` 必须是 VARIABLES、INPUTS 或 OUTPUTS。
- `path` 是至少包含一个片段的有序路径。
- 外部定义必须用完整 `{{ scope.path }}` 标记引用；花括号表示整个 Operand 是引用，
  不是模板插值。
- Operand 只保存引用，不保存本次 Execution 解析出来的运行值。

示例：

```text
{{ variables.level }}
{{ inputs.region }}
{{ outputs.approval.status }}
```

#### CONSTANT

- 只允许出现在 `right`。
- 精确小写 `true`、`false` 形成 Boolean。
- 严格整数或小数形式形成 Number。
- 其余未包裹内容形成 String，点分文本也不例外；输入什么，常量值就是什么。
- 空字符串、形似 Boolean/Number 或包含条件语法保留字符的 String 使用双引号和受控
  转义，保证规范化 source 再解析后仍为相同 String。
- 所有数字字面量统一规范为 `BigDecimal`，避免 Integer、Long、Double 等 Java
  表现类型影响比较结果。
- Character 沿用单字符 String 的外部表示，由消费方的数据定义校验其长度。

示例：

```text
APPROVED
inputs.region
true
100
100.50
"true"
""
```

### Comparison

Comparison 表示一条比较规则，不表示逻辑组合。为保留将来的实现空间，Condition
字段依赖 Comparison Interface；一期只提供固定的 BasicComparison：

```text
Comparison
└── BasicComparison
    ├── EQUALS                  ==
    ├── NOT_EQUALS              !=
    ├── GREATER_THAN            >
    ├── GREATER_THAN_OR_EQUALS  >=
    ├── LESS_THAN               <
    └── LESS_THAN_OR_EQUALS     <=
```

Comparison 至少负责：

- 提供稳定的表达式符号或 key。
- 判断左右值类型是否受支持。
- 对已经解析的左值和常量右值执行只读比较。

一期使用固定的基础 Comparison 表，不增加 Registry、外部注册入口或业务实现。以后出现
第一个真实的业务 Comparison 时，再根据实际依赖补充注册机制，不在一期提前设计部门、
组织架构或外部查询协议。

### ConditionContext

ConditionContext 是一次求值使用的临时只读上下文：

```text
ConditionContext
├── variables
├── inputs
└── outputs
```

- `variables` 是精确 Flow Reversion 定义的只读变量。
- `inputs` 是 Execution 启动时确认并持久保持的输入快照。
- `outputs` 是当前 Condition 消费位置允许读取的输出快照。

Condition 不自行加载 Flow、Execution、TaskRun、Repository 或组织架构。Route 与
Loop Until 分别构造符合自身范围的 ConditionContext，因此同一个 Condition 领域可以
复用语法和求值规则，同时保持不同编排位置的数据可见范围。

## Condition 的最小接口

调用方应主要通过以下行为使用 Condition：

```java
Condition.parser(String source)

Condition.compare(
    Operand left,
    Comparison comparison,
    Operand right
)

Condition.combine(
    Logical operator,
    List<Condition> conditions
)

boolean matches(ConditionContext context)

List<Operand> references()

String source()
```

- `parser` 是对外解析入口，内部委托独立的 `ConditionParser` 完成解析，再执行结构校验和不可变构造。
- `compare` 和 `combine` 只用于受控的程序化创建，不能绕过相同不变量。
- `matches` 隐藏路径解析、类型规范化、递归求值和短路规则。
- `references` 供 Flow、Route 或 Loop Until 校验变量声明和可见范围。
- `source` 用于定义展示、字符串序列化和错误信息，不能作为运行时动态执行文本。

原始文本可以作为 Condition 的内部定义元数据保留，但它不改变已确认的五个语义字段，
实际求值始终使用解析后的树。

## 条件语法

一期使用受限的递归语法：

```text
condition  := orExpression
or         := andExpression ("||" andExpression)*
and        := primary ("&&" primary)*
primary    := comparison | "(" condition ")"
comparison := reference comparisonOperator constant
```

其中：

```text
reference          := "{{" whitespace? scope "." path whitespace? "}}"
scope              := "variables" | "inputs" | "outputs"
comparisonOperator := "==" | "!=" | ">" | ">=" | "<" | "<="
constant           := quotedString | bareConstant
```

路径片段沿用现有 Flow key 的安全字符范围：以英文字母开头，后续允许英文字母、数字、
下划线或连字符。只有完整的 `{{ ... }}` 是引用；未包裹的精确小写 `true`、`false`
形成 Boolean，严格整数或小数形成 Number，其余裸值形成 String。双引号用于空 String、
强制保留 String 类型或容纳 `&&`、`||`、括号等语法保留字符，并支持受控转义。

例如：

```text
{{ inputs.username }} == admin
{{ inputs.source }} == inputs.username
{{ inputs.literal }} == "true"
```

三条表达式的右侧依次是 String `admin`、String `inputs.username` 和 String `true`；
不会继续从 Condition Context 解析。

以下内容必须被完整拒绝：

```text
{{ outputs.status }} = DONE
outputs.status == DONE
{{ outputs.status }} == {{ inputs.expected }}
{{ outputs.amount }} + 1 > 10
Runtime.exec("command")
{{ outputs.items[0] }} == A
```

解析失败必须包含稳定错误原因和字符位置，不能只返回一个无上下文的 `false`。

## 树结构与优先级

比较操作的优先级最高，`&&` 高于 `||`，括号覆盖默认优先级。

```text
A || B && C
```

解析为：

```text
OR
├── A
└── AND
    ├── B
    └── C
```

```text
(A || B) && C
```

解析为：

```text
AND
├── OR
│   ├── A
│   └── B
└── C
```

相邻的相同逻辑操作可以规范为一个多子节点结构：

```text
A && B && C
```

规范为一个 AND 节点和三个有序子节点。不得重新排序子节点，因为定义顺序同时决定
短路执行顺序和错误诊断顺序。括号本身不需要形成独立节点，嵌套结构已经完整表达其
语义。

## 求值规则

### 比较节点

1. 根据左 Operand 的 scope 选择 ConditionContext 中的 Map。
2. 按 path 从左到右遍历 Map，得到本次求值的实际值。
3. 取得右 Operand 中已经规范化的常量值。
4. 由 Comparison 检查类型并执行比较。
5. 返回 boolean，不修改 ConditionContext 或 Condition Tree。

### 逻辑节点

- AND 从左到右求值；第一个 `false` 立即结束并返回 `false`。
- OR 从左到右求值；第一个 `true` 立即结束并返回 `true`。
- 求值过程不保存上次结果，也不在不同 Execution 之间共享运行数据。

### 缺值与类型规则

- 引用根不存在、路径不存在或中间值不是 Map 时，该比较节点返回 `false`。
- 缺值不等于 `null`，即使操作符是 `!=`，缺值也不能得到 `true`。
- String 使用区分大小写的精确相等和不等比较，不执行字典序大小比较。
- Boolean 只支持相等和不等。
- Number 统一转换为 BigDecimal 后使用 `compareTo`，因此 `1` 与 `1.0` 相等。
- 运行时值与常量值类型不兼容时返回 `false`；能够通过定义声明推导出的类型错误必须
  在 Flow 发布阶段提前拒绝。

## 校验职责

### Condition 领域负责

- 表达式词法和语法合法性。
- 比较节点与逻辑节点的结构互斥和字段完整性。
- Operand 的显式引用、常量互斥结构和左右位置约束，不增加可推导的 kind 字段。
- 基础 Comparison 与常量类型的兼容性。
- 不可变集合和无空子节点。
- 条件树深度、节点数量和输入字符串长度的安全限制。
- 引用路径的结构合法性。

首版建议通过命名常量限制最大表达式长度、树深度和节点数；具体阈值应在实现提交中
以测试固定，不能散落魔法数字。

### Condition 消费方负责

- `variables` 顶层 key 是否由 Flow 定义。
- `inputs` 是否由 Flow Input 声明，以及 DataType 是否兼容 Comparison。
- `outputs` 是否由当前可见范围内的 Task 声明。
- Route 允许读取哪一跳 outputs。
- Loop Until 允许读取哪一轮、哪一个循环体 Task 的 outputs。
- 条件未匹配后的具体编排行为。

Condition 不能为了完成上述校验而反向依赖 Route、Loop Until、Flow 聚合或 Executor。

## 定义绑定边界

- Route 的外部定义字段使用 `route` 保留原始字符串，绑定和草稿状态不解析
  Condition。Loop Until 的 `condition` 仍由 Jackson 创建入口 `Condition.parser(...)`
  委托包内独立的 `ConditionParser.parser(...)`。
- Condition 对外仍序列化为规范化字符串，不把内部树结构泄漏为持久化协议。
- 解析后的 Condition Tree 随所属 Flow Reversion 保持不可变。
- Condition 没有独立 Repository、数据库表、技术 ID、版本或审计字段。
- Condition 的变化属于 Flow 定义变化，只能通过新的 Flow Reversion 生效。
- 本阶段不设计 Route 的 Task properties、PostgreSQL 列或数据库恢复协议；持久化
  Adapter 后续必须遵守相同领域不变量，但不属于当前验收范围。

## 与流程 Task 的集成边界

### Route

目标结构：

```text
Route extends Branch
├── route: String
├── Condition  // 发布校验或运行匹配时按需形成
└── tasks: List<Task>  // 继承自 Branch
```

Route 负责确定 Condition 可见的数据范围和条件成立后的分支编排。Condition 只返回
boolean，不返回目标 Task、不创建 TaskRun，也不决定单选或多选策略。

新 Route 不能把 `route` 字段重新放回抽象 Task。旧 TaskRoute 只作为迁移对象处理；
确认没有调用方后删除，不把新 Condition 再包装回公共 Task。

### Loop Until

Loop Until 持有 Condition，并在每轮完整子树收敛后创建只包含当前轮可见 outputs 的
ConditionContext。Condition 成立时 Loop Until 完成；不成立且未达到上限时继续；达到
上限仍不成立时失败。

Condition 不读取旧轮输出，不保存循环计数，也不拥有 Loop Until 的继续或失败规则。

### 其他流程 Task

Parallel、Sequence 和普通 Loop 不因为 Condition 领域的建立自动获得 condition 字段。
某个具体流程 Task 只有在自身语义需要条件时才组合 Condition，不能再次把条件提升为
Branch 或 Task 的公共字段。

## 分阶段开发计划

### 阶段一：建立独立 Condition 领域

- 新增 `core.domains.conditions` 包和基础类型。
- 完成递归 Condition Tree、Operand、Logical 和 BasicComparison。
- 完成字符串解析、规范化、引用提取和求值。
- 完成 Condition 领域单元测试和安全拒绝矩阵。
- Condition 领域保持独立编译，不依赖 Route、Loop Until 或 Executor。

阶段一完成标准是 Condition 领域能够脱离 Route、Loop Until 和 Executor 独立解析并
求值全部一期语法。

### 阶段二：Route 接入 Condition

- 将 Route 的定义字段固定为必填 `String route`，不在字段绑定时形成 Condition。
- 正式发布时才解析 Route Rule，并完成非空、语法、声明、类型和引用范围校验。
- 在 Executor 的 Route 决策位置构造 ConditionContext 并调用 Condition。
- 增加单条件、AND、OR、括号和无匹配等 Route 集成测试。
- 不恢复 Task.route，不把 Condition 放入抽象 Task。

### 阶段三：Loop Until 接入 Condition

- LoopUntil.condition 直接使用 Condition。
- 保留“至少执行一轮”“每轮收敛后判断”和 `maxIterations` 规则。
- 校验所有 outputs 引用均属于当前循环体允许的范围。
- 增加逻辑组合和 typed constant 的循环终止测试。

该阶段应在 Branch.tasks 的结构迁移稳定后进行，避免同时处理继承调整、子 Task 字段
迁移和条件语言替换。

### 阶段四：收敛条件调用方

- 统一所有 boolean 条件调用方使用 Condition。
- 删除只服务旧条件包装的代码。
- 保留 TemplateExpression 及其现有模板语义。
- 更新领域结构测试、插件 Schema 和相关文档引用。
- 删除不再具有独立业务职责的中间路由包装。

调用方必须在同一链路完成收敛，不能长期保留两套条件语义。

## 测试计划

### 领域结构测试

- 比较节点和逻辑节点只能形成合法互斥状态。
- 构造后集合不可修改，调用方集合变化不影响 Condition。
- Condition、Operand 没有技术 ID、Repository 或运行状态。
- Condition 领域不依赖 Route、Loop Until、Executor 或基础设施包。

### 解析测试

- 三个变量根及多段路径。
- String、Boolean、整数、负数和小数。
- 六种基础 Comparison。
- AND、OR 默认优先级。
- 单层及多层括号。
- 相邻相同 Logical 的多子节点规范化。
- 空白、字符串转义和规范化 source。
- 拒绝空文本、单等号、未知根、非法路径和未闭合括号。
- 拒绝方法、函数、数组、算术、赋值和尾随代码。
- 拒绝超过长度、深度或节点数限制的输入。

### 求值测试

- 从 variables、inputs 和 outputs 解析实际值。
- 嵌套 Map 路径读取。
- String 区分大小写的相等和不等。
- Boolean 相等和不等。
- 不同 Java Number 表现类型之间的规范化比较。
- 缺少根、缺少叶子和中间值非 Map 均返回 false。
- 缺值使用 `!=` 仍返回 false。
- 类型不兼容返回 false。
- AND 和 OR 保持定义顺序并执行短路。
- 同一个不可变 Condition 并发读取不同 Context 时互不污染。

### 定义与集成测试

- YAML 字符串能够物化为 Condition。
- Condition 规范化为字符串后重新解析，语义保持一致。
- Flow 发布能够拒绝未声明或不可见的引用。
- Route 能够按单条件和组合条件选择路径。
- Loop Until 只读取当前轮输出并正确继续、完成或失败。
- Plugin Schema 继续把条件暴露为受限字符串格式，而不是任意脚本。

### 验证命令

实现阶段先运行 Condition 领域目标测试，再运行完整回归：

```bash
./gradlew :core:test --tests '*Condition*Test'
./gradlew :core:test
./gradlew test
```

当前工作区同时存在 Task、Branch、Route 等未完成重构。开始编码前必须记录基线失败，
区分既有问题与 Condition 引入的回归，并保护所有无关修改。

## 验收标准

Condition 基础能力只有同时满足以下条件才算完成：

- 外部条件字符串能够稳定解析为已确认的递归树。
- `&&`、`||` 和括号的优先级符合本文规则。
- 左侧显式变量引用和右侧 typed constant 的角色无法被构造反转。
- 所有基础 Comparison 按支持类型返回确定结果。
- 不存在脚本、反射或任意代码执行入口。
- 缺值、类型不匹配和非法语法具有统一结果或错误。
- Condition 不依赖具体流程 Task，也不保存运行时值。
- Route Rule 按需形成 Condition，Loop Until 直接持有 Condition；两者使用同一条件
  语言和求值模型，但分别保护自己的数据范围。
- 所有 boolean 条件调用方统一使用 Condition，TemplateExpression 不受影响。
- 目标测试和完整 Gradle 回归通过；若存在基线失败，报告必须明确列出并证明不是本次
  修改引入。

## 建议提交链路

按照同一修改链路分开提交：

1. `docs: 增加 Condition 开发方案`
2. `feat: 增加 Condition 基础领域能力`
3. `refactor: 将 Route 接入 Condition`
4. `refactor: 将 LoopUntil 迁移至 Condition`
5. `refactor: 移除旧条件表达式实现`

测试应与对应生产修改放在同一个提交中，不单独形成与实现脱节的补测提交。
