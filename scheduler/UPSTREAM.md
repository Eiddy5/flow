# PostgreSQL 调度机制来源

- 上游：https://github.com/kagkarlsson/db-scheduler
- 标签：v16.11.0
- 提交：8c3e465d5651f9fcad673ab54863932259b5de64
- 版权：Copyright (C) Gustav Karlsson
- 许可证：Apache-2.0，见 LICENSE-db-scheduler；来源与许可随 JAR 分发。

2026-09-20 已移除原 src/upstream/java 的146个上游 Java 文件。项目不再编译上游
包，也不依赖其发布制品。

ScheduleRepository 的到期行排他领取依据上游
`com.github.kagkarlsson.scheduler.jdbc.PostgreSqlJdbcCustomization` 的
`FOR UPDATE SKIP LOCKED` 机制改写。原 JdbcTaskRepository / ExecutionUpdate
使用持久化 picked、heartbeat、version 管理事务外通用回调；Flow 将仅涉及同库写入
的命中回调和计划推进并入同一短事务，以 PostgreSQL 锁和回滚负责恢复，不再复制
这些通用组件。当前计划通过生成的 JOOQ 类型映射，并以租户、触发器、event_id
保护回写；时间为 bigint 毫秒。

保留的职责：计划登记与取消、并发排他、稳定命中身份、失败重试、进程重启恢复。
移除的组件：通用任务与回调 API、方言切换、任务序列化器、线程池/心跳/失活扫描、
插件统计与监听器。时间规则使用 Core 已有 Trigger 实现。

实现边界及故障语义见 docs/decisions/0107-integrate-postgresql-trigger-scheduling.md。
