# SubFlow 首次调用验证

前置环境沿用 [PostgreSQL](postgresql-repositories.md) 和 [Pulsar](pulsar-queues.md)。
新基线增加 `executions.parent_task_run_id`，旧库必须重建；应用不会自动升级。

先发布子流程，记录返回的实际版本（草稿也占用版本，不假设为 1）：

```yaml
key: review-child
inputs:
  - key: request
    type: STRING
    required: true
tasks:
  - key: wait
    type: org.cses.flow.extensions.flow.Pause
    onPause:
      key: notify
      type: org.cses.flow.extensions.log.Log
      message: 'review {{ inputs.request }}'
    onResume:
      - key: approved
        type: BOOLEAN
        required: true
```

发布父流程，将下面 flowVersion 替换为子流程的实际发布版本：

```yaml
key: review-parent
inputs:
  - key: request
    type: STRING
    required: true
tasks:
  - key: call
    type: org.cses.flow.extensions.flow.SubFlow
    flowKey: review-child
    flowVersion: 2
    inputs:
      - key: request
        type: STRING
        required: true
  - key: after
    type: org.cses.flow.extensions.log.Log
    message: 'child {{ outputs.call.executionId }} approved {{ outputs.call.outputs.wait.approved }}'
```

通过 ExecutionService 启动父流程并提交 `request`。查询父运行的 lineage，子运行
具有新的 ID，origin.parentId 指向父运行，parentTaskRunId 指向 call 的 TaskRun。
子 Pause 等待时父 call 保持 RUNNING；向子运行实际 Pause 提交 `approved: true`，
父后续 Log 会自动消费返回值，父子最终均 SUCCESS。多层 SubFlow 沿用同一个 originId。

引用字段直接配置在 SubFlow 上；旧的嵌套 `flow.key/version` 定义须调整并重新发布。

当前 inputs 从父 Execution.inputs 按同名声明绑定，支持 Input 既有默认值；未提供
从前置输出到参数的表达式映射。返回 outputs 是有效节点结果，VoidOutput 节点省略。
目标缺失、删除、参数错误、子运行失败均使父调用失败。重试、重启恢复、取消传播和
Replay 替换不在本阶段范围。

设置上述手册中的真实数据库与 Broker 环境后运行：

```bash
./gradlew :core:test --tests '*SubFlowTest'
```

该筛选包含技术 SubFlowTest 和 UC13 的 5 场景（S3 两个参数分支）。业务验收证据
以 Test Agent 的 UC13 报告为准；编译通过不替代真实执行。
