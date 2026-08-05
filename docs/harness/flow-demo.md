# Flow Studio 用户 Demo 运行手册

## 目的

启动一个与 Flow Micronaut 服务同进程运行的用户级编排页面，演示以下真实链路：

- 创建、查询、编辑和删除 Flow 草稿。
- 使用服务端 `YamlParser` 预览定义。
- 发布不可变 Flow Reversion。
- 启动 Execution，并查看持久化的 TaskRun 与 State History。
- 为 PAUSE TaskRun 提交符合 `resume` Input 定义的回调数据，通过
  `ExecutionService.resume(...)` 恢复并继续流程。

页面不会在浏览器内模拟流程状态。草稿、发布版本、Execution 和 TaskRun 均通过
现有 Core Service、CommandExecutor 与 PostgreSQL Repository 读写。

## 数据库准备

Demo 使用当前 Flow PostgreSQL Schema。空数据库按日期顺序执行
`gen/sql/production-release/flow/` 下的全部迁移；已有数据库只执行尚未应用的
增量迁移。详细顺序与字段说明见
[`postgresql-repositories.md`](postgresql-repositories.md)。

默认连接为：

```text
jdbc:postgresql://localhost:5432/flow
user: flow
password: flow
```

应通过环境变量覆盖实际环境的连接信息，不要把凭证写入仓库。

## 随项目服务启动 Studio

项目服务已经通过正式环境连接 PostgreSQL 时，只激活 `studio` 环境。该环境注册
Flow Studio 静态资源和页面 Controller，并提供仅供本地 Studio 使用的固定会话；
它不会替换正式环境的 JOOQ/DataPilot 连接。

在 IntelliJ IDEA 的 Application Run Configuration 中，把 `studio` 追加到现有
环境变量。例如原来没有显式环境：

```text
MICRONAUT_ENVIRONMENTS=studio
```

原来使用 `dev` 时：

```text
MICRONAUT_ENVIRONMENTS=dev,studio
```

停止旧进程并重新点击运行按钮。日志应包含
`Established active environments: [..., studio]`。服务启动后访问：

```text
http://127.0.0.1:3434/demo/index.html
```

`studio` 页面 API 使用当前项目服务已经装配的数据库事务边界。固定本地身份默认
为 `flow-demo / studio-user / Flow Studio User`，可在 IDEA 环境变量中覆盖：

```text
FLOW_STUDIO_COMPANY_ID=my-local-company
FLOW_STUDIO_USER_ID=my-local-user
FLOW_STUDIO_USER_NAME=My Local User
```

Studio Binder 不接受浏览器伪造租户，仅应在本地开发时激活 `studio` 环境。

## 独立 Demo 启动

在仓库根目录运行：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
MICRONAUT_ENVIRONMENTS=demo \
FLOW_DEMO_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/flow \
FLOW_DEMO_POSTGRES_USER=flow \
FLOW_DEMO_POSTGRES_PASSWORD=flow \
./gradlew :server:run
```

服务启动后访问：

```text
http://127.0.0.1:3434/demo/index.html
```

Demo 环境默认监听 `0.0.0.0`，同一局域网内也可以通过本机 IP 访问：

```text
http://<本机局域网 IP>:3434/demo/index.html
```

如只允许本机访问，可在启动时设置 `FLOW_DEMO_HOST=127.0.0.1`。局域网访问还需
确保 macOS 防火墙允许 Java 接收入站连接，并且访问设备与本机处于可互通网络。

Demo 环境会关闭 Consul 配置读取和服务注册，并使用短连接 PostgreSQL JOOQ 事务
适配器，因此页面与 API 可以在本地独立启动。正常环境仍使用平台管理的 DataPilot
连接。

## Demo 会话

Demo 使用服务端配置的固定租户和用户，不接受浏览器传入的租户身份。可覆盖：

```bash
FLOW_DEMO_COMPANY_ID=my-demo-company
FLOW_DEMO_USER_ID=my-demo-user
FLOW_DEMO_USER_NAME="My Demo User"
```

默认值分别为 `flow-demo`、`demo-user` 和 `Flow Demo User`。Demo 与 Studio
都会显式启用本地 Binder，不能替代正式环境的登录和授权。

## 页面能力与当前边界

- 画布和 YAML 编辑的是同一份草稿原文。
- 页面初始化时调用 `GET /api/plugins` 读取全局插件目录；所有“添加流程 Task”、
  “添加后续 Task”和“添加子 Task”入口共用按注册插件包分组的 Task 下拉列表，
  不维护独立类型清单或内置三类型 fallback。选中 Task 后调用
  `GET /api/plugins/{canonicalType}` 按需获取定义 Schema，并在右侧生成插件专有
  字段控件；字段布局仍是 Demo 本地行为，不进入 Plugin 元信息。
- 页面可继续用 AUTO、PAUSE 和 PARALLEL 作为三个内置任务的可读标签和 Demo
  行为分支；写入 YAML 的 `type` 始终是具体任务类的完整地址，不接受这些标签作为
  类型别名。其他项目内 Task 会使用目录中的 title、description 和 canonical type。
- 点击“新建流程”先填写 Flow Key 和描述，服务端保存空草稿后才进入编排画布；
  创建流程不会再要求选择 Task 类型。
- 左侧只负责草稿的列表、搜索和切换，不再展示独立的“流程结构”区域。空画布、
  顶部工具栏和画布结构摘要都提供“添加流程 Task”，该操作始终追加到
  `Flow.tasks`，不受当前选中节点影响；选中已有节点后，节点下方会出现
  “后续 Task”、“子 Task”、“并行”和“Route”快捷操作。除 PAUSE 外的普通 Task
  可以添加这两类节点：“后续 Task”插入当前节点之后的同级位置，“子 Task”明确
  写入当前节点的 `tasks`。PAUSE 的通用 `tasks` 必须为空，页面改为编辑其专有
  `pause` 前置 Task，并通过同级 Task 表达恢复后的后续流程。
- 页面按真实领域语义提供四种编排能力：
  - 顺序任务：Flow 顶层 Task 和普通 Task 的直接子 Task 均按定义顺序依次推进。
  - 后续任务：插入当前 Task 所在序列的下一位；当前 Task 的整棵子任务树结束后
    才会进入该同级后续 Task。若当前 Task 是 `PARALLEL` 的直接子 Task，同级
    位置代表新的并行分支，页面会明确提示这一语义。
  - 子任务：写入当前 Task 的 `tasks`；在并行分支中使用它，可以表达该分支内部
    的串行后续步骤。
  - 并行流程：只有 `PARALLEL` Task 的直接子 Task 才同时进入。向导会先创建
    显式 `PARALLEL` 结构节点，再创建分支，并可添加等待该并行块结束后的 AUTO
    后续 Task。
  - Route 选择：表单选择直接父任务的 STRING output 和期望值，页面生成
    `outputs.<key> == "<value>"`，不要求用户手写表达式。
- Route 的参数路径只从直连父 Task 声明的 STRING outputs 级联选择，例如
  `outputs.approval`。审批结果以及同级分支已使用的值会继续以下拉选项呈现；
  普通 STRING output 因当前领域模型没有枚举值元数据，在没有已知选项时仍允许
  填写自定义匹配值。
- 同一父任务下所有命中 Route 的子任务都会按定义顺序进入，因此 Route 不是强制
  互斥的 `if/else`；未命中的子任务不会创建虚假的 `SKIPPED` TaskRun。
- 并行必须由完整类地址
  `org.cses.flow.extensions.flow.Parallel` 显式声明为 `type`，表示它的
  多个直接分支可以在同一个
  Execution 内同时处于可运行或等待状态。普通 Task 即使拥有多个同级子 Task，
  也只会等待前一棵子任务树结束后再推进下一棵。当前 Worker 仍逐个派发候选任务，
  不对外承诺同一线程上的物理并发。
- 画布采用递归的“父层主干 + 中间子树块”横向排版：每个节点的直接子节点紧跟
  在该节点后一列并向下展开；每棵子树先在自己的局部区域内完整排完，其他 Route
  分支的后续节点不会把它整体推到右侧。一棵子任务树结束后的同级 next Task
  会回到父层，并放在整棵子树的右侧。Route 和显式 `PARALLEL` 的直接分支位于
  同一结构层，分叉连线从父节点底部展开；各分支的后续 Task 在下一层保持对齐。
  普通子流程使用紫色，Route 使用黄色，并行使用蓝色。节点卡片只展示 key、类型、
  必要的流程标签和运行状态。当前正在执行或等待的节点使用有节奏的蓝色边框动画，
  已经完成的节点使用绿色边框。节点属性区继续使用 Route 表单和依赖多选列表，
  YAML 仍可用于高级编辑。
- Flow 和普通 Task 的 inputs/outputs、PAUSE 的 resume Inputs 都使用列表编辑器；
  Input 每一项先选择服务端
  DataType，再填写 key、displayName、required、defaultValue 等公共字段以及
  当前具体 Input 子类的特有字段，Output 每一项填写 key 和 type。各项可以独立
  新增、修改或删除，并实时同步到同一份 YAML。
- 页面初始化时调用 `GET /api/demo/data-types`，获取九种稳定代码、Java 包装
  类型、具体 Input 类和字段控件元数据。当前 `IntegerInput` 会额外显示 min/max；
  前端不允许自由输入 type，也不维护另一份类型别名表。
- 数字默认值和 PAUSE 数字恢复输入按服务端 DataType 做范围检查与包装类型归一化；
  字符串数字不会被后端静默转换。Route 仍只允许直接父 Task 的 STRING Output。
- 保存允许不完整草稿；发布使用现有 Flow 领域规则做完整校验。
- 启动总是选择当前 Core 查询到的最新可用 Reversion。
- AUTO Worker 会由当前执行链同步推进。
- 新建 PAUSE 时页面自动生成一个可替换的 AUTO `pause` 前置 Task，并默认声明
  `decision` 和 `comment` 两个 resume Inputs。运行时审批表单按这些 Input 生成；
  用户使用“同意”或“拒绝”按钮提交，不填写技术 JSON。页面仍通过确定的
  `executionId + taskRunId` 调用统一 Resume 入口，TaskRun 先恢复为 RUNNING，再由
  Executor 继续推进。
- 当前 `ExecutionService.create(...)` 不接收启动输入，因此页面不展示虚假的
  “启动参数”能力。

已有数据库按顺序执行 `003_backfill_input_definition_fields.sql` 后，只有
key/type 的旧 Input 会补为 `displayName=key`、`required=false`。页面打开尚未
保存的旧 YAML 草稿时也会应用同一兼容并提示存在未保存修改；显式非法字段和未知
type 仍需按真实业务修订。

## 自动验证

内存 Repository 仅用于快速 HTTP 技术回归：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
./gradlew :server:test \
  --tests 'org.cses.flow.controller.demo.FlowDemoControllerTest'
```

该测试覆盖静态资源、固定 Demo Session、插件目录与定义 Schema、DataType 元数据、
IntegerInput 定义往返、草稿创建/列表/编辑、发布、自动运行、PAUSE 恢复、版本查询和删除。
真实 PostgreSQL 联调仍应按本手册启动服务并从页面完成一次用户路径。

布局核心可以不启动浏览器直接执行固定结构回归：

```bash
node docs/harness/flow-layout-structure.test.cjs
```

该样例覆盖 Route 两条路径分别拥有后续 Task、其中一条继续进入显式
`PARALLEL` 的嵌套结构；它验证每棵子树紧跟自己的父节点，同时顶层 next Task
必须位于完整中间结构之后。

服务在 `3434` 端口运行时，可以额外执行真实浏览器布局回归。该检查会拦截 API
并注入固定流程定义，不会创建、修改或删除数据库草稿：

```bash
NODE_PATH=<包含 playwright 的 node_modules> \
node docs/harness/flow-canvas-layout.e2e.cjs
```

该样例验证主干 `auto-task-1/auto-task-6` 同行、审批节点独占下一层、
Route 的 `auto-task-2/auto-task-3` 同行，以及各分支后续
`auto-task-4/auto-task-5` 同行；同时验证主干后续位于完整中间结构右侧。
