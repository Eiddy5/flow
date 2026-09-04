# ADR 0004：使用 YAML 定义和精确 Flow 引用

## 状态

Accepted（Flow 来源草稿、`reversion` 与查询契约部分由 ADR 0008 修订；
YAML 解析位置与 Flow 物化边界由 ADR 0013 修订；FlowDraft 的业务身份与
唯一性由 ADR 0062、ADR 0063 和 ADR 0064 修订；草稿非空版本与追加保存由
ADR 0083 修订）

## 决策

Flow 定义通过 YAML 创建和修改，YAML 只暴露稳定业务 `key`，不暴露持久化
主键 `id`。FlowDraft 创建时必须提供 `companyId + key`；后续编辑、发布和关闭
按该业务选择器进行。数据库 `id` 仅作为技术行标记，按 id 的方法只保留在
持久化适配器内部。

YAML 是 Flow Core 的唯一正式定义格式。具体解析位置和领域转换边界以
[`ADR 0013`](0013-centralize-yaml-parsing-and-flow-materialization.md)
为准：通用 `YamlParser` 位于扁平的 `core/serializers`，Flow 聚合直接消费
解析后的通用只读映射。

读取 Flow 必须使用 `companyId + key + version + status` 组成的精确引用。Draft
没有正式 version，因此使用 `companyId + key + null + DRAFT`；已发布和关闭定义
使用正数 version。查询条件不完全匹配时返回不存在，不隐式选择最新版本。

“精确引用”描述的是查询契约，不对应一个 `FlowReference` 类型。Service
直接接收三个条件，避免建立只包装参数且与领域对象重复的模型。

发布新版本时，旧版本定义内容保持不可变，但状态转为 `CLOSE`；新版本是同一
Flow 下唯一的 `DEPLOYED` 版本。该选择避免仅按业务 key 或“当前版本”查询造成
Execution 绑定版本不明确，同时保持 YAML 与数据库身份解耦。
