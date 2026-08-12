# 工作流核心第一阶段验证手册

## 范围

本手册使用生产 PostgreSQL Repository 和 Micronaut 公开 Service 链路验证
Flow Core。验证分为两类：UC 主测试只记录能够通过真实用户公开能力取得的验收
证据；PAUSE、resume、并行汇合、条件路由和嵌套调度的 Core 行为由普通技术集成
测试保护。技术测试通过不等于外派用户场景通过。

## Java 版本

项目要求 Java 21。macOS 上如果当前 `JAVA_HOME` 无效或指向其他版本，可以仅为当前命令选择 Java 21：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :core:test
```

## 当前 UC 证据

当前 Core 没有外部业务的待办、受派、权限和审计查询入口。按 UC 测试规范，不能
用遍历 `Execution.pausedTaskRuns()` 代替“被外派用户查询自己的待办”。当前场景
证据如下：

| UC | 可执行 UC 主测试证据 | 当前结论 |
| --- | --- | --- |
| UC-02 | S8、S9 | S1～S7 `NOT_COVERED`，UC 整体不能标记 PASS |
| UC-04 | 无 | S1～S9 `NOT_COVERED`，UC 整体不能标记 PASS |
| UC-05 | 无 | S1～S6 `NOT_COVERED`，UC 整体不能标记 PASS |
| UC-06 | S4 | S1～S3、S5～S7 `NOT_COVERED`，UC 整体不能标记 PASS |
| UC-07 | S3 | S1、S2、S4～S7 `NOT_COVERED`，UC 整体不能标记 PASS |

这里的 `NOT_COVERED` 表示缺少 UC 要求的公开用户观察路径，不是数据库环境导致的
`BLOCKED`，也不能由技术测试的绿色结果替代。

## UC 主测试与基础约束验证

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  FLOW_POSTGRES_TEST_URL=jdbc:postgresql://localhost:5432/flow \
  FLOW_POSTGRES_TEST_USER=flow \
  FLOW_POSTGRES_TEST_PASSWORD=flow \
  ./gradlew :core:test \
  --tests '*Uc01FlowLifecycleTest' \
  --tests '*Uc02ExecutionLifecycleTest' \
  --tests '*Uc03AutomaticTaskFlowTest' \
  --tests '*Uc06ConditionalRouteTest' \
  --tests '*Uc07NestedTaskFlowTest' \
  --tests '*TaskTest' \
  --tests '*YamlParserTest' \
  --tests '*FlowDefinitionMaterializationTest' \
  --tests '*FlowCoreWiringTest' \
  --tests '*CoreArchitectureStandardTest'
```

结果解释：

- 目标测试类全部通过，只证明表中列出的场景证据和基础约束通过。
- 不得从统一 Gradle 退出状态推导 UC-02、UC-04、UC-05、UC-06 或 UC-07
  整体 PASS；必须逐场景使用上表结论。
- Task 抽象领域对象及 `AutomaticTask` 的不可变快照语义通过。
- UC-01 的 Flow 定义由 YAML 解析，默认字段、递归 Task 和类型扩展字段通过。
- 查询只接受 `FlowService.flow(session, id, version, status)` 的精确条件，
  错误 version 或 status 不会回退到其他 Flow。
- Micronaut 能真实装配写链路和读链路，并完成一次保存、发布和查询。
- Core 领域目录、业务分包、Domain 禁用 record、边界协议允许按职责选择 record，
  以及 String 技术 ID 规则通过结构测试。
- 没有编译错误或失败断言。

## Core PAUSE 与 resume 技术回归

以下测试使用 Core 可见的 WAITING/PAUSED TaskRun 身份验证运行生命周期，不承载
UC 场景编号，也不验证被外派用户的待办查询、受派或权限：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  FLOW_POSTGRES_TEST_URL=jdbc:postgresql://localhost:5432/flow \
  FLOW_POSTGRES_TEST_USER=flow \
  FLOW_POSTGRES_TEST_PASSWORD=flow \
  ./gradlew :core:test \
  --tests '*ExecutionPauseLifecycleIntegrationTest' \
  --tests '*ExecutionResumeIntegrationTest' \
  --tests '*ParallelPauseResumeIntegrationTest' \
  --tests '*ConditionalRouteResumeIntegrationTest' \
  --tests '*NestedPauseResumeIntegrationTest'
```

这组测试覆盖启动版本绑定、PAUSE 卡点、resume、取消隔离、并发竞争、条件分支、
并行等待与唯一汇合、嵌套 parentId、深层恢复和取消。全部通过时只能记录为
`Core PAUSE/resume technical regression PASS`。

未来宿主业务补齐 PAUSE UC 主测试时必须满足：

- 被外派用户通过外部业务的公开查询取得自己的具体待办，不能通过 Core 扫描
  Execution 来猜测目标。
- 外部能力只保留查询返回的 `executionId + taskRunId`，新 ApplicationContext
  通过 `ExecutionService.resume(...)` 恢复；测试不得持有原进程的 Service、
  Repository 或领域对象跨阶段调用。
- 每个新稳定暂停点都必须使用其精确 Pause TaskRun 身份恢复，不能按 Execution
  中“第一个暂停点”猜测目标。
- 正常场景显式断言 COMPLETED；取消和失败场景保持真实终态；测试结束不得依赖
  Fixture 隐式完成遗留任务。
- 收尾分别记录 Core 的运行中 Execution 数、WAITING/PAUSED TaskRun 数，以及
  外部业务待办数；当前 Core 无法观察最后一项时必须写 `NOT_OBSERVABLE`，不能
  把 PAUSED TaskRun 数等同为外部待办数。

## 本地启动验证

内存 Repository、JOOQ 和无连接基础设施支架位于 `core/src/testFixtures`，不作为 UC 验证
模式。UC 使用生产 PostgreSQL Repository；数据库准备和迁移步骤见
[`postgresql-repositories.md`](postgresql-repositories.md)。当前尚无 Flow HTTP
Controller，因此本阶段通过公开 Service 验证，不把 HTTP 调用作为通过条件。

## 完整回归

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
FLOW_POSTGRES_TEST_URL=jdbc:postgresql://localhost:5432/flow \
FLOW_POSTGRES_TEST_USER=flow \
FLOW_POSTGRES_TEST_PASSWORD=flow \
./gradlew test

JAVA_HOME=$(/usr/libexec/java_home -v 21) \
FLOW_POSTGRES_TEST_URL=jdbc:postgresql://localhost:5432/flow \
FLOW_POSTGRES_TEST_USER=flow \
FLOW_POSTGRES_TEST_PASSWORD=flow \
./gradlew build
```

完整回归还会编译其他模块并运行仓库中的全部测试。

## 当前限制

- 测试源码仍保留内存 Repository 供领域单元测试使用，但 UC-01～UC-08 不使用
  内存 Repository。
- Flow 尚无 HTTP Controller；当前公开用户操作边界是 Service。
- 外部业务的待办、受派、权限与审计不属于 Flow Core；补齐宿主公开查询与完成
  路径前，涉及外派用户操作的场景保持 `NOT_COVERED`。当前 Resume 仍需由宿主
  协议补充身份认证、幂等和可靠回调。
- Loop、超时、重试及第一阶段语法之外的 route 表达式尚未实现。
