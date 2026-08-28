# ADR 0077：移除无语义的 AutomaticTask，使用 Log 作为内置可执行步骤

## 状态

Accepted（2026-08-28）

## 背景

`AutomaticTask` 只返回空成功结果，不包含业务动作、输入、输出或可观察行为。
它仅被用作流程中的占位推进步骤，与已有的 `Log` RunnableTask 产生了重复的
执行能力。当前 Flow 不需要保留一个没有业务语义的通用自动任务类型。

## 决策

- 删除生产插件 `org.cses.flow.extensions.tasks.AutomaticTask`。
- 删除其 canonical type 的生产注册、示例、页面默认定义和当前 UC 归属。
- 对原本只承担无业务副作用推进作用的步骤，改用
  `org.cses.flow.extensions.log.Log`，通过固定消息提供可观察行为并正常完成步骤。
- `Log` 仍然只负责渲染并写入日志，不产生通用业务输出，也不变成通用计算任务。
- 不为旧 `AutomaticTask` canonical type 提供兼容别名或迁移入口；旧定义需要改为
  合法的 `Log` 定义后重新保存/发布。
- 需要真实业务计算或业务输出时，由宿主提供有明确业务语义的 RunnableTask 插件，
  不复活一个无语义的通用自动任务。

## 理由

- 减少没有用户可观察价值的内置类型，避免流程设计器暴露重复能力。
- 保留 Log 作为轻量、可观察且已经存在的无副作用流程步骤。
- 让“流程推进”和“业务计算”保持清晰边界；后者需要具体业务插件承担。

## 后果

- 内置生产 Task 类型从 8 种减少为 7 种。
- AutomaticTask 专属 UC-03 不再属于当前有效 UC；Log 的 UC-08 继续验证日志步骤。
- 依赖 AutomaticTask 的 YAML、示例、测试和页面默认模板必须迁移到 Log，且补充
  必填的 `message` 字段。
- 已保存但尚未迁移的 AutomaticTask 定义不能继续通过当前插件注册表物化。

