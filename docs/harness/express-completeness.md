# Express 完成度验证

## 目的

本验证矩阵用于确认 Express 对当前已经接受的受限 outputs 条件语言具有完整行为，
并确认 Task Route 与 Loop Until 复用同一解析和求值规则。它不把 Express 当作
通用脚本引擎，也不以支持任意表达式作为完成标准。

当前权威模型见
[`ADR 0037`](../decisions/0037-centralize-condition-expressions-in-express-domain.md)。

## 完成标准

- ADR 0037 的 `EXP-*`、`RTE-*` 和 `LUC-*` 不变量至少有一条接口级测试保护。
- Route 与 Loop Until 必须通过公开定义绑定和运行决策接口消费 Express，不测试
  私有正则或解析中间状态。
- YAML/Map 定义绑定、插件 Schema 和 FlowTaskEntry 字符串往返保持兼容。
- 尚未确认的运算符、类型和脚本能力必须被拒绝或明确列为范围外，不能静默接受。

## 场景矩阵

| 层次 | 已覆盖场景 |
| --- | --- |
| Express 合法语法 | 一段及多段 outputPath、首尾与运算符空白、数字/下划线/连字符 key、空字符串和含空格字符串 |
| Express 求值 | 区分大小写匹配、嵌套 Map、缺少叶子、中间值非 Map、最终值非 String、null outputs |
| Express 并发 | 同一不可变定义并发读取不同 outputs，不保存或串扰运行上下文 |
| Express 安全拒绝 | 空来源、DIRECT、错误根、非法 key、错误运算符、未引用字符串、方法调用、数组下标、逻辑组合和尾随执行文本 |
| Task Route | DIRECT、单 output key 条件、父 outputs 求值、拒绝多段 Loop 路径 |
| Loop Until | taskKey + outputKey 两段路径、循环体和 STRING Output 校验、SUCCESS/CONTINUE/FAILURE 决策 |
| 定义与恢复 | 同一 Flow 中绑定 Route 和 Loop Until、condition 以 string 暴露 Schema、Route 列与 LoopUntil properties 字符串往返 |

`ExpressCompletenessTest` 当前提供 27 个具名动态场景：11 个已支持行为与 16 个必须
拒绝的范围外或不安全输入。消费方和持久化场景由相邻领域测试继续覆盖。

## 执行命令

在有效的 Java 21 环境中执行：

```bash
./gradlew :server:test \
  --tests org.cses.flow.core.domains.expressions.ExpressCompletenessTest \
  --tests org.cses.flow.core.domains.expressions.ExpressTest \
  --tests org.cses.flow.core.domains.expressions.TemplateExpressionTest \
  --tests org.cses.flow.core.domains.tasks.TaskRouteTest \
  --tests org.cses.flow.extensions.flow.LoopUntilTest \
  --tests org.cses.flow.core.domains.flows.FlowMaterializationTest \
  --tests org.cses.flow.core.plugins.PluginSchemaGeneratorTest \
  --tests org.cses.flow.infrastructure.repositories.flows.postgres.entries.TaskDataPersistenceMappingTest
```

完成实现后仍需运行 `./gradlew test`，避免表达式类型迁移破坏 Executor、Worker 或
其他 Task 定义调用方。

## 当前范围外

- 字符串转义和内嵌双引号。
- `!=`、大小关系、数字或布尔比较。
- `and/or/not` 或括号逻辑组合。
- 方法、函数、数组下标、算术和任意脚本。
- Express 条件语言不包含 TemplateExpression 的模板语法；后者由同一 expressions
  领域独立提供，返回字符串且拥有不同的缺值语义。

这些能力需要先确认业务语法、类型与失败规则，再扩展 ADR 和完成度矩阵。
