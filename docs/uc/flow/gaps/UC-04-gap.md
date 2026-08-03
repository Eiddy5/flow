# UC-04 项目能力缺口

## 对应 UC

- UC：`UC-04 用户处理外派任务并恢复流程`
- 文档：[UC-04 用户处理外派任务并恢复流程.md](../UC-04%20用户处理外派任务并恢复流程.md)
- 涉及场景：S8 恢复阶段异常，以及 complete/cancel 竞争

## UC 目的

保护 ExternalTask、PAUSE TaskRun 和 Execution 的一对一恢复链路，确保外部完成
与运行恢复原子提交，且重复、越权或竞争操作不会重复推进。

## 目标业务与安全场景

ExternalTask 已完成后如果恢复 Execution 或后续 Worker 失败，调用方不能看到
“触发器已完成但原 TaskRun 仍在等待”的部分状态；complete 与 cancel 同时发生时
也只能形成一个一致终态。

## 项目现有能力

- ExternalTask 只保存 companyId、executionId、taskRunId、状态、结果和
  lockVersion，不复制 PauseTask outputs 契约。
- `CompleteExternalTaskHandler` 通过 ExternalTask 的服务端关联加载 Execution
  绑定的确定 Flow Reversion，校验 WAITING PAUSE TaskRun、结果对象存在且字段
  均已声明；外部调用方不能指定下一 Task。
- 完成 Handler 在同一命令事务中完成 ExternalTask、原 PAUSE TaskRun 并委托
  Executor 继续推进。
- PAUSE Worker 和取消 Worker 能分别创建及取消触发器。

## 原不满足项与证据（已修复）

- `CompleteExternalTaskHandler` 先调用 `externalTaskRepository.save`，随后才调用
  `ExecutionService.resume`。
- 内存 ExternalTask 和 Execution Repository 使用独立 Map，不参与 JOOQ 事务
  回滚；resume 或后续 Worker 抛出异常时，先前保存不会自动撤销。
- 两个 Repository 都没有基于 lockVersion 的 compare-and-set。
- `Uc04ExternalTaskResumeTest` 已迁移到公开 Service 对应包并覆盖 S1～S7；S8
  的恢复异常原子回滚仍缺少可执行保障。

## 风险

- ExternalTask 变为 COMPLETED，但 PAUSE TaskRun 和 Execution 仍为 WAITING。
- 重试会因 ExternalTask 已终态而被拒绝，导致 Execution 永久无法恢复。
- complete/cancel 竞争可能产生跨聚合不一致或重复后续 TaskRun。

## 已满足的最小能力

- ExternalTask 完成、Execution 修改、TaskRun 完成和后续调度共享真实原子事务。
- Repository 使用 lockVersion 或等价条件保存拒绝陈旧写。
- 恢复异常后能够重新读取到完整的恢复前状态。
- 在现有 UC-04 主测试类中补齐 S3～S8 和全部 PASS 映射。

## 当前状态与后续角色

- 状态：`RESOLVED`
- 处理结论：ExternalTask 完成、PAUSE TaskRun 完成、Execution 推进和后续 Worker
  位于同一个可回滚 PostgreSQL 命令事务；complete/cancel 由 lockVersion 和事务
  写入隔离。
- 复验证据：
  `Uc04ExternalTaskResumeTest#s8ResumeFailureRollsBackExternalTaskAndExecution`
  以及 UC-02 S7 竞争测试。
- UC Agent：保留原子性和竞争场景。
- 开发角色：已实现跨聚合事务与并发协议。
- Test Agent：已执行成功、异常和竞争场景，复验结果见
  [UC-04-2026-07-27-1948.md](../../../test-reports/flow/UC-04-2026-07-27-1948.md)。
