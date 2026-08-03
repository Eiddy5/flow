# 工作流核心第一阶段验证手册

## 范围

本手册使用生产 PostgreSQL Repository 和 Micronaut 公开 Service 链路验证
Flow Core。现阶段包含 UC-01 Flow 草稿生命周期与多租户管理、UC-02
Execution 启动与取消、
UC-03 用户运行自动流程、UC-04 用户处理外派任务并恢复流程、UC-05 用户处理并行外派任务、
UC-06 条件路由和 UC-07 两层嵌套 Task 长流程。

## Java 版本

项目要求 Java 21。macOS 上如果当前 `JAVA_HOME` 无效或指向其他版本，可以仅为当前命令选择 Java 21：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :server:test
```

## 最小验证

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  FLOW_POSTGRES_TEST_URL=jdbc:postgresql://localhost:5432/flow \
  FLOW_POSTGRES_TEST_USER=flow \
  FLOW_POSTGRES_TEST_PASSWORD=flow \
  ./gradlew :server:test \
  --tests '*Uc01FlowLifecycleTest' \
  --tests '*Uc02ExecutionLifecycleTest' \
  --tests '*Uc03AutomaticTaskFlowTest' \
  --tests '*Uc04ExternalTaskResumeTest' \
  --tests '*Uc05ParallelTaskJoinTest' \
  --tests '*Uc06ConditionalRouteTest' \
  --tests '*Uc07NestedTaskFlowTest' \
  --tests '*TaskTest' \
  --tests '*YamlParserTest' \
  --tests '*FlowDefinitionMaterializationTest' \
  --tests '*FlowCoreWiringTest' \
  --tests '*CoreArchitectureStandardTest'
```

通过规则：

- 目标测试类全部通过。
- Task 抽象领域对象及 `AutomaticTask` 的不可变快照语义通过。
- UC-01 的 Flow 定义由 YAML 解析，默认字段、递归 Task 和类型扩展字段通过。
- 查询只接受 `FlowService.flow(session, id, version, status)` 的精确条件，
  错误 version 或 status 不会回退到其他 Flow。
- Micronaut 能真实装配写链路和读链路，并完成一次保存、发布和查询。
- Core 领域目录、业务分包、Domain 禁用 record、边界协议允许按职责选择 record，
  以及 String 技术 ID 规则通过结构测试。
- 没有编译错误或失败断言。

Execution 测试必须覆盖启动版本绑定、PAUSE 卡点、外部恢复、取消隔离、
AUTO 同命令连续执行、条件分支、并行等待、唯一汇合、两层递归 parentId、
深层 PAUSE 恢复和深层取消。

所有 PAUSE 场景还必须满足：

- 启动 Execution 的 ApplicationContext 在外派任务查询和完成前关闭。
- 新 ApplicationContext 通过
  `ExternalTaskService.waitingTasks(session)` 公开查询当前租户的 WAITING
  ExternalTask，测试不得从 Repository、数据库或 TaskRun 预取 id。
- 每个新稳定等待点都重新公开查询，再完成查询所得任务。
- 正常场景显式断言 COMPLETED；取消和失败场景保持真实终态；测试结束不得依赖
  Fixture 隐式完成遗留任务。

## 本地启动验证

内存 Repository、JOOQ 和 Session 支架只存在于 `src/test`，不作为 UC 验证
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

- 测试源码仍保留内存 Repository 供领域单元测试使用，但 UC-01～UC-07 不使用
  内存 Repository。
- Flow 尚无 HTTP Controller；当前公开用户操作边界是 Service。
- 外派对象当前按 companyId 隔离，尚未定义更细粒度的受派用户字段。
- Loop、超时、重试及第一阶段语法之外的 route 表达式尚未实现。
