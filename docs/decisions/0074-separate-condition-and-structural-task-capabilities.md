# ADR 0074：拆分 Condition 与结构型 Task 能力

## 状态

已接受。2026-08-28 补充 Route 使用 `route` 原始字符串并按需形成
Condition 的定义协议，以及 `{{ scope.path }}` 显式引用与常量的操作数协议。

本决策确立独立 Condition 与 Route Rule 模型，取代 ADR 0065 中 Pause 继承普通
`tasks` 的部分，并修订 ADR 0029、0036、0052 和 0055 中把 route 作为公共 Task
字段描述的部分。`TemplateExpression` 的模板语义不受影响。

## 背景

旧 Task 同时保存 `route`、`dependOn` 和 `tasks`。这使没有子 Task、没有路由或不
参与 DAG 的 Runnable Task 也被迫携带这些字段，并把流程结构、DAG 关系和功能 Task
的专有参数混在同一个抽象类中。

原有条件能力只能表示一条受限比较，并把条件路由包装为公共 Task 字段。该模型不能
直接表达 AND、OR 和括号优先级，也无法让 Route 与 Loop Until 在保留各自可见范围
的同时复用同一个递归条件模型。

## 备选方案

### 方案一：保留 Task 的 route、dependOn 和 tasks

兼容现有定义和调度代码，但继续让所有 Task 承担并不属于自己的结构能力，无法形成
清晰的流程 Task、功能 Task 和未来 DAG Task 边界。

### 方案二：只扩展原有条件能力，继续保留公共 Task route

可以增加逻辑组合，却仍把条件提升到所有 Task，并保留伪路由和额外条件包装。

### 方案三：拆分公共 Task、Branch 和独立 Condition

Task 只保存共同定义；Branch 保存有序子 Task；Route 保存 `route` 原始字符串并在
需要校验或匹配时形成 Condition，Loop Until 直接组合同一种 Condition 值对象。
DAG 依赖留给后续专有模型。

## 决策

采用方案三。

### Task 与 Branch

- 抽象 Task 只保存 `id`、`key`、`displayName`、`inputs` 和 `outputs`。
- 抽象 Branch 继承 Task，并独占 `tasks: List<Task>`。
- Route、Parallel、Sequence、Loop 和 LoopUntil 继承 Branch。
- Pause 直接继承 Task，只保存 `pause`、`resume`、`duration` 和 `behavior`；它不拥有
  普通 Branch tasks。
- `dependOn` 属于尚未确认的 DAG 能力，不进入 Task 或 Branch。本决策不设计 DAG。

### Condition

Condition 位于 `core.domains.conditions`，是无身份、无 Repository、创建后不可变的
递归值对象。它只有两种完整形态：

- 比较节点：`left + comparison + right`；left 必须是 variables、inputs 或 outputs
  的 Condition Reference，right 必须是 String、Boolean 或 Number Condition Constant。
- 逻辑节点：`operator + conditions`；operator 为 AND 或 OR，conditions 至少两个。

外部定义使用受限字符串语法，比较优先于 `&&`，`&&` 优先于 `||`，圆括号覆盖默认
优先级。Condition 解析为树后求值，不执行源字符串，不支持脚本、反射、方法、数组、
算术、null 值、NOT 或右侧变量引用。

引用必须使用完整的 `{{ scope.path }}` 形式；花括号标记整个 Operand 是运行时引用，
不是字符串模板或局部插值。未包裹内容不会隐式查询上下文：`inputs.username` 是 String
常量，不是 inputs 引用。由于比较左侧必须是引用，裸路径出现在左侧属于无效语法；
由于右侧必须是常量，`{{ ... }}` 出现在右侧同样无效。

未包裹的精确小写 `true`、`false` 形成 Boolean，严格整数或小数形式形成 BigDecimal，
其余未包裹内容形成 String，输入文本本身就是常量值。空字符串、形似 Boolean/Number
或包含条件语法保留字符的 String 使用双引号和受控转义，以保证规范化 source 再解析
后含义不变。`null` 是普通 String 常量，不表示 Java null 或缺值。

Operand 不保存独立 Kind。引用或常量已经由外部语法、比较式左右位置，以及内部
`scope + path` 或 `value` 的互斥结构完整确定；重复枚举会产生需要额外同步的派生状态。

基础 Comparison 固定为 `==`、`!=`、`>`、`>=`、`<`、`<=`。String、Character 和
Boolean 只支持相等与不等；Number 统一为 BigDecimal 比较。缺值和类型不兼容均返回
false，缺值使用 `!=` 也不例外。

### 消费边界

- Route 保存一个必填 `route: String`，在正式校验或实际匹配前始终保持为原始字符串，
  不在字段赋值时创建 Condition。
- 正式 Flow 发布校验需要检查语法、声明、类型和可见范围时，才由 Route Rule
  形成 Condition；Executor 实际判断候选 Route 时同样通过该入口求值。
- 由 `route` 形成的 Condition 成立时 Route 进入自己的 Branch 子树，不成立时
  不创建 Route TaskRun。
- Route 可读取 Flow variables、Execution inputs，以及同一串行作用域中位于它之前
  且已完成 Task 的 outputs。并行兄弟输出不是 Route 的可见输入。
- LoopUntil 组合同一个 Condition，但 outputs 只来自当前已收敛轮次的循环体；它不
  读取旧轮输出，也不把循环计数交给 Condition。
- Flow 发布负责校验声明和可见范围；Condition 只负责语法、树不变量、类型比较和
  一次只读求值。

### 定义边界

- Route 的定义字段只使用 `route` 字符串；草稿允许保留暂未完成的表达式，正式发布
  必须经过 `Condition.parser` 与 Flow 引用校验。
- LoopUntil 继续直接持有 Condition，具体解析由包内独立的 `ConditionParser` 承担。
- Branch 只拥有有序 tasks，Pause 只拥有自己的 pause 关系，二者不获得 route。
- 本阶段不决定 Task properties、PostgreSQL 列、数据库保存或恢复协议；这些 Adapter
  能力后续单独设计和验证，不能反向改变当前领域字段。
- 条件判断统一使用 Condition；TemplateExpression 独立保留。

## 理由

该结构让抽象类只表达真实共同能力：Runnable Task 不再拥有伪子树、伪路由或伪 DAG
字段；流程结构由 Branch 集中；条件语法只有一个解析与求值入口。Route 保留用户
的原始 Route Rule，使草稿编辑与有效 Condition 分离；Route 与 LoopUntil 仍能共享
Condition 深模块，同时独立保护自己的输出可见范围和编排行为。

## 后果

- 旧 YAML 中普通 Task 的 `route`、`dependOn` 或 `tasks` 会被严格绑定拒绝；条件分支
  必须显式建模为 Route，且只有具体 Route 可以使用 `route` 保存原始表达式，不能
  使用 `condition` 或把 `route` 放回抽象 Task。
- 旧的裸路径引用（如 `inputs.username == admin`）不再兼容，必须改为
  `{{ inputs.username }} == admin`；裸点分文本只具有 String 常量含义。
- `OperandKind` 与 `Operand.kind()` 不属于 Condition 公共模型；调用方使用完整条件语法，
  不根据额外分类枚举解释 Operand。
- 多个 Route 是彼此独立的条件作用域；条件重叠时所有成立的 Route 都会进入，不隐式
  形成 if/else-if/switch。单选或默认分支需未来的显式 Choice/Switch 语义。
- DAG 需要自己的抽象与依赖字段设计，不能把 dependOn 重新加回 Task。
- Condition 将来可以新增业务 Comparison 实现，但在出现真实扩展需求前不提供注册表
  或自动发现机制。
