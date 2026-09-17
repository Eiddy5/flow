# 飞书 CLI 的 Agent 能力发现设计调研

## 范围与版本

- 调研日期：2026-09-17。
- 一手来源：官方 [larksuite/cli](https://github.com/larksuite/cli)，检视提交 `cb5a3d704379552dc61e37898e5f74798783190d`。下列源码链接固定到该提交。
- 本文区分上游现有事实与 Flow 建议；不构成已接受 ADR，也不表示 Flow 已实现这些命令。
- 验证方式：阅读官方源码、Skill、规范和测试；没有执行飞书真实认证或业务调用。

## 1. 上游事实：知识有明确所有者

| 层 | 保存的知识 | 不承担的职责 |
| --- | --- | --- |
| 命令元数据 / Schema | 命令、字段及结构，即 WHAT | 不重复手写同一份参数说明 |
| `affordance/<domain>.md` | 何时选、何时不用、前置条件、示例及相关 Skill，即 WHEN | 不复制 Schema 和命令描述 |
| `SKILL.md` | 领域路由、概念、安全及跨命令工作流 | 不将所有条件性细节常驻上下文 |
| `references/` | 当前任务需要时才读取的详细 HOW | 不作为全量预载文档 |

这是上游明确规定的内容所有权。[AGENTS.md](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/AGENTS.md#affordance-and-skills)

Affordance 会进入命令 `--help` 和 `schema`；它允许通过命令引用表达“改用哪个命令”和“输入从哪里获取”。Skill 路径会检查是否实际存在，错误路径不会出现在帮助中。因此它同时是可导航的能力连接，而不仅是附加说明。[affordance/README.md](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/affordance/README.md)

## 2. 上游事实：渐进发现是明确执行协议

Task Skill 要求先精确匹配已有 Shortcut；不确定时逐级读取领域、资源、方法帮助，最后查看方法 Schema。遇到 `unknown_subcommand` 应重新发现能力，不能继续猜近似命令。它还解释“任务搜索”和“列举我的任务”的语义区别；字段类型本身不能解决这种选择问题。[lark-task/SKILL.md](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/skills/lark-task/SKILL.md)

CLI 自身提供可读取的知识入口：

```bash
lark-cli skills list
lark-cli skills read lark-task
lark-cli skills list lark-task/references
lark-cli skills read lark-task references/lark-task-get-my-tasks.md
lark-cli task --help
lark-cli schema task.tasks.list
```

`skills list` 输出 JSON 元信息；`skills read` 默认输出 Markdown，也接受 `--json`。这些只读命令不要求认证。目录查询每次只返回一层，支持先选能力、再读细节。[cmd/skill/skill.go](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/cmd/skill/skill.go)、[internal/skillcontent/reader.go](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/internal/skillcontent/reader.go)

Skill 与 reference 文本，以及 affordance，通过 `go:embed` 随二进制交付；机器资源 `assets/`、`scripts/` 不在此嵌入白名单。这样，Agent 可以直接读取当前 CLI 版本携带的规则。[content_embed.go](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/content_embed.go)

## 3. 上游事实：跨命令工作流与贡献规范

`lark-workflow-standup-report` 已描述“读取日程 + 查询未完成任务 + AI 汇总”。它明确身份、权限、日期格式、未完成筛选、分页和汇总规则。该文件指导 Agent 当次调用多个命令，并未定义可交给持久化调度器运行的工作流 DSL；不能把这个例子视为已经实现用户要求的每日定时执行。[standup-report Skill](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/skills/lark-workflow-standup-report/SKILL.md)

贡献规范要求先确定契约所有者，再追踪入口至输出的调用链；新增 Shortcut 必须提供单个 API 之外的流程或交互价值。命令语义、帮助/Schema、输出、错误和身份都作为兼容契约。新增 Shortcut 要有 dry-run 与真实 E2E 证据；无法安全完成真实验证时说明阻塞及替代证据。[AGENTS.md](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/AGENTS.md)

一致性不是只靠文档约定：仓库有嵌入后 affordance 的测试，检查帮助信息与实现描述的分工、引用和提示；也有 Skill frontmatter 检查与旧错误协议词汇检查。它们能发现部分漂移，但不能证明每个自然语言示例均可正确执行。[嵌入内容测试](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/content_embed_affordance_test.go)、[格式检查](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/scripts/skill-format-check/index.js)、[协议词汇检查](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/scripts/check-skill-wire-vocab.sh)

## 4. 上游事实：命令、Schema 与执行契约

命令分为友好的 Shortcut、由 API 元数据注册的一对一命令，以及通用 API 兜底。规范要求 Shortcut 提供组合、默认值或交互上的额外价值，避免为每个 API 再写一层同义封装。[AGENTS.md](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/AGENTS.md)

`schema` 从 APICatalog 逐级查询，按服务懒加载，并与命令可见性和补全共用过滤规则；当前目录来源是仓库提交的 Catalog Snapshot，并非每次实时向平台拉取定义。详情包含 `name`、`description`、`inputSchema`、`outputSchema` 和 `_meta`；元信息还包含 scopes、身份、风险和 affordance。[cmd/schema/schema.go](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/cmd/schema/schema.go)、[internal/schema/types.go](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/internal/schema/types.go)

Shortcut 统一声明 `Risk`、`AuthTypes`、`Scopes`、`Flags`、`Validate`、`DryRun`、`Execute`，运行器先处理 dry-run 分支，再进入真实执行。没有提供 DryRun hook 的命令会报告不支持，不能据此宣称所有命令均有预览能力。[types.go](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/shortcuts/common/types.go)、[runner.go](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/shortcuts/common/runner.go)

JSON 成功结果使用 stdout 的 `ok/data/meta` 信封；一般失败通过 stderr 返回类型化错误，使用 `type/subtype/message/hint` 及可选的参数、建议、可重试标记等。Agent 可据稳定分类决定下一步，无须解析人类错误文案；但不能把错误恢复理解为无条件重试写操作。[错误契约](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/errs/ERROR_CONTRACT.md)

安装向导会检测并通过 `npx skills add` 安装配套 Skills。安装二进制本身不意味着 Agent 已读取规则，仍需 Agent 支持 Skill 加载或主动调用内置知识入口。[scripts/install-wizard.js](https://github.com/larksuite/cli/blob/cb5a3d704379552dc61e37898e5f74798783190d/scripts/install-wizard.js)

## 5. 对 Flow 的建议（尚未实施）

沿用知识分工，不需要先为每个节点创建一份独立 Skill：

| Flow 所需知识 | 建议所有者 |
| --- | --- |
| 当前服务实际有哪些 Task / Trigger | 服务端插件目录，CLI 按需查询 |
| 节点字段、约束和输出类型 | 插件模型及生成的 Schema |
| 节点何时使用、前置条件、常见组合 | 随插件提供的语义说明及引用 |
| Flow 根结构、表达式、作用域和编排语义 | 一份 Flow 编排 Skill，细节放 references |
| 校验、发布、启用及执行查询步骤 | CLI 操作说明及结构化结果 |

用户的场景对应“发现定时触发器、任务查询及消息发送 → 读取输入输出与组合规则 → 编写 DSL → 校验修正 → 发布启用”。AI 只承担编排；启用后的周期运行归 Flow。若每次运行需要 AI 汇总，DSL 中还必须使用实际存在的 AI Task 或其他明确的运行时能力，不能依赖配置阶段的 Agent 永远在线。

日期范围应在每次触发时计算，不能把创建工作流当天的“昨天”固化为日期。身份、时区以及“昨天未完成”的业务口径属于需要明确表达的语义；这些是针对本场景的设计建议，不是飞书 CLI 自动解决的保证。

CLI 内置指南可跟随客户端版本交付，但插件能力随服务端部署变化，节点详情应以当前连接服务为准。这与飞书将 API 目录快照嵌入 CLI 的交付方式有边界差异。

初版优先完成“能力查询、DSL 规则、校验、发布启用、结果查询”闭环；无需同时复制全部三层命令和扩展框架。节点契约必须同时说明输入与输出，Flow 校验也必须验证编排规则；HTTP 请求 dry-run 不能替代 DSL 的语义校验。
