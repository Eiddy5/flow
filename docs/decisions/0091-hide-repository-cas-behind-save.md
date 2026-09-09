# ADR 0091：在普通 save 内复用仓储 CAS

## 状态

Accepted（2026-09-09）。修订 ADR 0086 的公开会话接口及元数据生命周期，保留
ADR 0084 的无业务长事务边界和 ADR 0087 的新旧 Execution 单 SQL 交接。

## 背景

用户确认 CAS 应成为通用基础设施能力，业务仍使用普通 `find/save`，不调用
`ExecutionRepository.inScope`。技术字段继续叫 `lock`，不进入领域对象；冲突
提示使用“数据已发生变化，请刷新后重试。”

## 选项

- 保留 Repository 公开会话：生命周期明确，但每个调用方都要理解 CAS。
- 按实体 ID 或 ThreadLocal 保存版本：不能区分重复查询副本，且增加线程清理风险。
- 复用现有数据库执行入口，内部管理对象身份与版本：不改变业务 save 用法，采用此项。

## 决策

- `FlowDatabase.execute` 在 CommandExecutor 和两个 Execution 消息入口内部管理
  元数据；不启动数据库事务。业务 Handler 仅调用 Repository 的读取和保存。
  脱离这些入口的基础设施集成代码应使用该数据库入口，不自行建立长生命周期缓存。
- `CasSupport` 位于 `infrastructure/repositories`，统一加载版本、保存前失效标记、
  保存成功后的版本推进、CAS 更新条件和冲突异常。不增加仓储基类或新依赖。
- 版本按“根表 + 弱引用对象身份”隔离，不调用领域 `equals/hashCode`，不强引用
  领域对象。相同 ID 的多次查询保留各自版本；不同操作使用独立派生 DSL，不能
  跨线程共享一个操作上下文。线程及进程之间仍由数据库 CAS 仲裁。
- Repository 在读取快照的同一 SQL 中取得 `lock`；save 只用加载版本，不补查
  最新版本。未登记对象只能尝试 INSERT，已有身份冲突；已登记对象执行完整租户、
  主键及 `lock` 条件 UPDATE，并递增版本。具体 Repository 仍负责完整身份条件。
- Execution 根与 TaskRun、退回时源根与派生根继续由单 SQL 原子保存；没有改变
  JSON、Schema 或领域模型。Flow 的追加版本保存不改为可变行 CAS。
- 操作正常或异常结束都清理元数据。成功保存支持同操作连续写；失败对象必须重读，
  不得借用新版本。若使用显式 jOOQ 事务，最外层提交即失效；嵌套提交保留版本，
  任意回滚（含 savepoint）使整个操作失效，需新建操作并重新加载。
  手动 JDBC 事务须在数据库操作返回前提交或回滚。自动提交 SELECT 不提前清理
  加载版本，数据库中的 `lock` 不会被清零。
- 冲突继续使用 `DataChangedException`，保持现有重试识别；用户文案统一为
  “数据已发生变化，请刷新后重试。” 缺少有效数据库操作属于接线错误，直接拒绝写入。

## 理由与后果

复用现有执行入口即可隐藏生命周期，同时保留加载快照的正确版本。这里只提供已经
需要的 CAS 支持，不把所有 Repository 改成同一种保存协议，也不保证跨多个 save
或外部调用的原子性。领域对象跨操作传递时不携带写凭据，修改前必须重新加载。

最小验证由 `CasSupportTest`、`ExecutionCasIntegrationTest`、Repository 往返测试及
入口接线测试覆盖；真实 PostgreSQL 并发与事务清理不能由 SQL Mock 结果代替。
