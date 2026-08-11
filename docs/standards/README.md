# 开发规范索引

## 文档边界

`docs/standards/` 只保存需要被多类开发任务重复遵循的方法、边界和实现约束。

一份规范应满足：

- 适用于多个业务对象或开发场景，而不是只描述某个具体类。
- 回答“同类工作统一遵循什么规则”，而不是“Flow 当前选择了什么对象结构”。
- 能够独立用于新代码开发和代码审查。
- 不记录一次架构选择的备选方案、演进历史或当前迁移状态。

具体领域对象、状态机、模块形态、运行协议和持久化选择属于架构决策，统一放在
`docs/decisions/`。操作步骤、环境配置和联调手册放在 `docs/harness/`；用户可观察
场景放在 `docs/uc/`。

## 当前通用规范

| 规范 | 适用内容 |
| --- | --- |
| [`project-development.md`](project-development.md) | 技术 ID、Java 类型、timestamp、复用与重构 |
| [`domain-object-modeling.md`](domain-object-modeling.md) | 领域术语、对象角色、聚合、状态机、不变量和语义所有权的通用建模方法 |
| [`command-executor.md`](command-executor.md) | 项目写操作、Session、Command、Handler 和事务约束 |
| [`postgresql-schema.md`](postgresql-schema.md) | PostgreSQL 基线入口、按表文件隔离和表命名规则 |
| [`jooq.md`](jooq.md) | JOOQ 生成类、Entry、查询映射和持久化边界 |
| [`json.md`](json.md) | JSON 公共能力和分层使用规则 |
| [`uc-testing.md`](uc-testing.md) | UC 与真实用户路径的测试、证据和报告规则 |

## 具体设计入口

Flow、Data、Task、Execution、PAUSE、State、Executor、Worker 和 Plugin 的当前
具体设计不在本目录维护。开发前通过
[`../decisions/README.md`](../decisions/README.md) 找到对应 ADR 决策链。
