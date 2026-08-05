# ADR 0028：新增 Log 扩展并支持 Task 模板表达式

## 状态

Accepted

## 背景

Flow 需要新增一个只负责输出日志的项目内扩展。该扩展的消息既可以是固定文本，
也需要从本次 Task 运行输入中提取值并插入文本。现有 `RouteExpression` 只判断
`outputs.<key> == "<value>"`，它的结果是 boolean，不能表达字符串模板，也不应让
日志扩展自行维护一套只能由自己使用的取值语法。

同时，Log 这种独立扩展能力需要用能力名称作为类型名称，并拥有自己的扩展目录，
而不是继续把全部具体实现平铺在 `extensions/tasks` 下或统一添加 `Task` 后缀。

## 备选方案

### 方案一：Log 在 run 方法中直接用正则替换 message

实现最少，但表达式语法、校验和错误语义会被日志能力私有化，其他 Task 字段无法
复用，定义绑定时也不能提前拒绝错误语法。

### 方案二：引入能够执行任意脚本的表达式引擎

能够支持方法调用、运算和条件，但显著扩大运行权限、配置复杂度和兼容边界；当前
需求只要求从已提供的运行值中安全提取并插入文本。

### 方案三：建立受限的 TemplateExpression 值对象并由 RunContext 渲染

模板在定义绑定时解析为有效值对象，运行时只按点分路径读取 `RunContext` 已提供的
只读输入。具体扩展只声明模板字段并请求渲染，不拥有表达式语法。

## 决策

采用方案三。

### Log 扩展

- 新增 `org.cses.flow.extensions.log.Log`，文件位于
  `extensions/log/Log.java`。类型名称使用能力名 `Log`，不使用 `LogTask`。
- `@Plugin` 标题为“日志”，描述只说明“解析消息表达式并将结果写入应用日志”，
  不使用“某某任务”作为行为描述。
- `Log` 继承 `Task` 并且只实现 `RunnableTask`。它只有一个必填定义字段
  `message`，字段对 YAML、API 和 properties JSONB 仍表现为字符串。
- `Log.run(...)` 渲染 `message` 后输出一条 INFO 日志。成功时返回空 outputs 的
  COMPLETED 结果，不推进 Execution 或 TaskRun，也不产生独立 Repository。
- 找不到表达式路径时，Log 返回明确失败结果，不输出未解析模板或空字符串；后续
  Task 不运行，已有运行事实按统一状态规则进入失败终态。

### 独立扩展目录和名称

- 新增的独立项目内 Task 扩展使用
  `extensions/<extension-name>/<ExtensionName>.java`，包名使用小写能力名，具体类
  使用能力名，不强制增加 `Task` 后缀。
- 该规则不适用于 Flow 自身解释的编排 Task。Pause、Parallel 以及后续 Loop、
  Loop Until、Subflow 按语义所有权统一归入 `extensions/flow`，不按具体 Task 名称
  各自创建目录。
- 插件发现仍以 `@Plugin` 和 canonical class name 为准，目录层级不进入注册协议。
- 本 ADR 只建立 Log 等独立扩展的目录规则，不决定已有 Flow 自有 Task 的目录。
  这些类型的归属与破坏性迁移由后续显式决策处理，不能增加别名或双轨类型。

### TemplateExpression

- `TemplateExpression` 是 Task 定义可复用的不可变值对象，保存用户提交的原始模板
  并参与定义值相等性；它不是运行结果，也没有 Repository 或生命周期。
- 模板支持固定文本、一个或多个 `{{ path.to.value }}` 占位表达式。路径段必须以
  英文字母开头，后续只允许英文字母、数字、下划线或连字符。
- 表达式只读取运行时已经提供的只读 Map，不能调用 Java 方法、访问 Class、执行
  脚本、进行运算或改变运行上下文。
- 当前可用根来自现有运行输入：`outputs.<key>` 读取直接父 Task 的输出，
  `dependOnOutputs.<taskKey>.<key>` 读取依赖 Task 的输出。没有提供的根或路径不会
  被猜测、补默认值或转换为空字符串。
- YAML 和 properties JSONB 始终保存原始字符串；定义绑定和持久化重建都通过同一
  `TemplateExpression.parse(...)` 恢复并校验。
- `RunContext.render(TemplateExpression)` 是 RunnableTask 的统一渲染入口，保证
  具体扩展不重复解释输入结构。

## 理由

- Log 的类型、字段和行为保持在同一个扩展类中，符合当前具体 Task 直接作为 Plugin
  的边界。
- 模板值对象在定义进入 Flow 前拒绝错误语法，同时保持 YAML 和 Schema 的字符串
  体验。
- 受限路径读取满足消息提取需求，且不会把任意脚本执行能力带入 Worker。
- RunContext 继续只暴露本次 RunnableTask 调用所需的能力，不泄漏 Execution、
  TaskRun 或 WorkerTask。
- 独立扩展目录让 Log 等独立能力后续增加相邻实现文件时不再挤入统一 tasks 目录，
  同时不会把 Flow 自有编排 Task 错拆成多个顶层能力目录。

## 后果

- Log 的稳定类型是 `org.cses.flow.extensions.log.Log`；改名或换包属于定义兼容性
  变更。
- 插件定义 Schema 中 `message` 必填且保持 string；表达式语法说明通过 Schema
  描述和接入手册提供。
- 表达式缺少运行值会形成可查询的失败 Execution，而不是回滚并抹去已经完成的前置
  TaskRun。
- 更复杂的函数、条件、算术、转义、默认值、全局变量和 Flow 输入绑定不在本次范围
  内；增加这些能力必须明确语法、失败语义和兼容规则。
