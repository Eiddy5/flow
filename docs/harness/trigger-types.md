# 流程、Webhook 和指定日期触发

依赖、数据库准备见 [定时触发手册](schedule-triggers.md)。本次变更需重建隔离开发库的
Flow 完整基线（包含 trigger_schedules）；IF NOT EXISTS 不会升级既有字段。未处理业务库升级。
每种插件自身 @Plugin.examples 均包含可发布完整 Flow YAML。

## 上游流程触发

先发布 key 为 upstream 的任意流程，再发布：

```yaml
key: after-upstream
triggers:
  - key: upstream-success
    type: org.cses.flow.extensions.triggers.FlowTrigger
    flowKey: upstream
    states: [SUCCESS]
tasks:
  - key: hello
    type: org.cses.flow.extensions.log.Log
    message: "上游已完成"
```

启动 upstream 并等待成功，查询 after-upstream 新实例直到完整结束。失败/取消可分别配置
FAILED/KILLED。首期只支持单上游终态和静态触发 inputs，不支持上游输出映射、PAUSED 或依赖窗口。
同一来源重复消息不新增实例；首次处理后新增目标不会被旧消息触发。自引用与祖先循环不触发。

## Webhook

```yaml
key: webhook-flow
inputs:
  - key: taskId
    type: STRING
    required: true
triggers:
  - key: incoming
    type: org.cses.flow.extensions.triggers.WebhookTrigger
tasks:
  - key: hello
    type: org.cses.flow.extensions.log.Log
    message: "收到任务 {{ inputs.taskId }}"
```

通过部署实际的 PAAS/AuthX 认证方式取得已选择公司的会话，再调用：

```http
POST /triggers/flows/webhook-flow/incoming
Content-Type: application/json
Idempotency-Key: task-created-001

{"taskId":"task-001"}
```

示例省略环境特定的认证头，不代表匿名可访问。请求体直接是输入对象，没有额外 inputs 包装。
响应 202：`{"executionId":"..."}`。使用返回身份通过既有 Execution 查询确认运行结果。
相同操作者及请求键重试返回原 ID；改变输入被拒绝。静态 trigger.inputs 不允许请求覆盖。

## 指定日期

```yaml
key: appointments
triggers:
  - key: dates
    type: org.cses.flow.extensions.triggers.ScheduleOnDatesTrigger
    dates: [1893456000000, 1893542400000]
tasks:
  - key: hello
    type: org.cses.flow.extensions.log.Log
    message: "预约到期"
```

dates 是 UTC Unix 毫秒，请替换为实际未来时间。每个不同时间点启动一次，耗尽后停止。
初次生效跳过过去时间；重启恢复已登记的一次过期计划，再计算当前时间之后的下一项。
触发配置异步生效，首次时间需留出配置投递处理时间。

`flow.scheduler.enabled=false` 只关闭时间型调度，流程触发与 Webhook 仍可运行。
