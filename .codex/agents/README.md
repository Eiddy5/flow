# Flow 项目 Codex Agent

本目录同时保存项目级 Custom Agent 的 Codex 入口和配套文档。Codex 直接加载本目录
顶层的独立 TOML；同名目录中的 Markdown 是该 Agent 的职责、流程和边界来源，由
TOML 和目录内 `README.md` 显式引导读取。

## 当前 Agent

| Agent | Codex 入口 | 文档入口 | 职责 |
| --- | --- | --- | --- |
| `uc` | [`uc.toml`](uc.toml) | [`uc/README.md`](uc/README.md) | 从已确认需求创建、更新或审计 UC |
| `test` | [`test.toml`](test.toml) | [`test/README.md`](test/README.md) | 按 UC 建立测试、执行验证并生成报告 |

## 目录约定

```text
.codex/agents/
├── <name>.toml       # Codex 直接加载的 Agent 入口
└── <name>/
    ├── README.md     # 角色契约、文档索引和主流程
    └── <topic>.md    # 由 README 按任务分支引导读取的专题规则
```

- Agent 名称使用小写英文和连字符，不添加 `-agent` 后缀。
- `<name>.toml` 只保留名称、使用时机和文档加载指令。
- `<name>/README.md` 必须说明目标、触发条件、必读内容、主流程、修改范围、输出和
  完成标准。
- 专题文件按独立任务分支拆分；`README.md` 必须写明触发读取该文件的条件。
- 同一规则只保留一个权威位置。跨 Agent 的开发、测试和建模规范继续位于
  `docs/standards/`，Agent 文档只引用，不复制。
- Agent 文档使用仓库相对路径指向项目文件，确保本地和工作树环境都能读取。

新增 Agent 时，同时创建顶层 `<name>.toml`、同名资源目录及其 `README.md`，并在
本文件登记。迁移或拆分专题文件时，必须同步修复 TOML、目录索引、`AGENTS.md` 和
`docs/project-structure.md` 中的入口。
