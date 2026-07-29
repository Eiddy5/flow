# UC 场景契约

UC Agent 在调用 `$generate-uc` 前提供结构化契约。已确认用户需求来源只用于内部
审查，不会写入最终 UC。

```yaml
uc:
  id: UC-04
  domain: flow
  kind: TASK_TYPE
  targetTaskType: PAUSE
  targetFile: docs/uc/flow/UC-04 用户处理外派任务.md
  title: 用户处理外派任务
  goal: 用户可以查询并完成自己的外派任务，使对应流程继续运行
  baseline: 用户确认的外派任务需求@2026-07-29
  dependencies: [UC-02]

internalReview:
  confirmedRequirements:
    - 用户只能处理自己租户中的外派任务
    - 完成外派任务后只恢复对应流程
  unresolvedQuestions:
    - 外派任务是否支持认领和转派
  coverageAudit:
    covered:
      - 正常完成
      - 非法输入
      - 多租户隔离
    notApplicable:
      - 自动重试

standardFlow:
  description: 开始后准备输入，等待用户处理目标外派任务，完成后由后置步骤消费结果并结束
  targetTask: 外派任务
  targetTaskCount: 1
  multipleTargetReason: null

scenarios:
  - candidateId: C01
    name: 用户完成自己的外派任务
    users: [流程发起用户, 外派任务处理用户]
    startsWith:
      - 用户已定义并发布标准验证流程
    userActions:
      - 流程发起用户启动流程
      - 外派任务处理用户查询并识别自己的具体任务
      - 外派任务处理用户完成查询所得任务
      - 两名用户重新查询任务和流程
    expectedResults:
      - 目标任务完成
      - 只有对应流程继续运行
      - 后置步骤收到目标任务结果
    endsWith:
      - 流程完成
      - 不遗留等待任务

  - candidateId: C02
    name: 其他租户完成外派任务
    users: [流程发起用户, 其他租户用户]
    startsWith:
      - 目标流程正在等待外派任务
    userActions:
      - 其他租户用户查询并尝试完成目标任务
      - 流程发起用户重新查询流程
      - 有权用户完成目标任务
    expectedResults:
      - 其他租户不能查询或完成目标任务
      - 目标流程没有被错误推进
    endsWith:
      - 有权用户完成任务后流程完成
```

## 必填约束

- 一个契约只描述一个业务主题。
- 领域、编号和目标文件由 UC Agent 确认。
- `kind` 只能是 `ORCHESTRATION`、`EXECUTION` 或 `TASK_TYPE`。
- `TASK_TYPE` 必须提供 `targetTaskType`、`standardFlow` 和覆盖审计。
- 每个场景都有已确认用户需求支持，且不存在会改变预期的未确认问题。
- 每个场景包含参与用户、开始状态、用户操作、预期结果和结束状态。
- 拒绝场景包含拒绝结果、无副作用观察和资源收尾。
- Flow 运行场景从用户启动或查询开始，并由同一场景推进到业务终态。
- PAUSE 场景包含“查询外派任务—完成查询所得任务—重新查询—最终终态”。
- “补齐”任务先比较已有场景，只提交新增或需要修订的契约内容。

## 不进入最终 UC 的内容

- 需求来源编号。
- 技术架构、ADR、架构不变量和安全边界列表。
- Positive、Negative、Mutation 分类。
- PASS 编号和规则追踪表。
- 当前代码、测试、数据库或实现能力信息。
