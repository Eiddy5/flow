# ADR 0040：使用标准具名数据源绑定 Flow 数据库

## 状态

Accepted

本决策修订 ADR 0035 和 ADR 0039 中要求 Flow 自定义数据源配置模型与 Factory 的
条款。

## 背景

完整 Flow Server 嵌入 CSES 后，CSES 应只负责提供数据库连接值，不应复制
DataSource、JOOQ 或 DataPilot Bean 装配。Flow 在独立运行与嵌入运行时需要使用
同一份配置契约，并固定使用具名 `flow` 数据库边界。

项目已经依赖 Micronaut JDBC、Micronaut JOOQ 和 PAAS JOOQ。它们会针对
`datasources` 下的每个具名数据源创建同名 DataSource、JOOQ Configuration 和
`org.x9.jooq.JOOQ`。再由 Flow 定义一套 `flow.datasource` 配置并创建相同 Bean，
会重复框架已有的配置模型和生命周期管理。

## 备选方案

### 方案一：使用 Micronaut 标准具名数据源

宿主配置 `datasources.flow`。现有 Micronaut 与 PAAS Factory 根据具名 DataSource
完成 JOOQ 装配，Flow 只在注入点固定使用 `@Named("flow")`。

### 方案二：Flow 定义独立 YAML 前缀与 Factory

Flow 可以绑定 `flow.datasource` 并自行创建连接池，但会复制 Hikari 配置模型、
关闭生命周期和 JOOQ 装配逻辑，还会让同一宿主出现两套数据源配置方式。

### 方案三：宿主实现 Java Configuration 回调

可以注册数据源 Adapter，但数据库连接值仍要进入外部配置，并让每个宿主增加
不必要的 Java 接线。

## 决策

采用方案一。

- Flow 数据库的唯一配置前缀是 `datasources.flow.*`，YAML 层级是
  `datasources -> flow`。
- Micronaut Hikari 根据该节点创建和关闭 `@Named("flow")` DataSource；Flow 不
  定义 DataSource Configuration 或 Factory。
- Micronaut JOOQ 和 PAAS JOOQ 根据具名 DataSource 自动创建同名 JOOQ Bean；Flow
  不创建或替换 JOOQ Factory。
- `jooq.datasources.flow` 不是必填接入配置。只有需要显式覆盖 SQL 方言或 JOOQ
  Settings 时才声明；正常运行由 Micronaut 从实际 DataSource 判断方言。
- `flow` 数据源名、`postgresql.flow` DataPilot 键和 `public` Schema 是 Flow 的
  固定内部边界。Repository、事务和 DataPilot 适配不得回退到宿主 `default`、
  `cses`、`okr` 等数据源。
- 不使用 Flow 数据库时不声明 `datasources.flow` 节点；一旦声明，该节点必须提供
  非空 JDBC URL，不能用空 URL 作为关闭开关。
- URL、账号、密码和连接池参数由 CSES YAML、Consul 或环境变量提供，不进入 Flow
  代码。
- Flow 继续不迁移 Schema，启动前仍由部署人员手工执行完整 SQL 基线。

## 理由

- CSES 与 Flow 使用项目已有的同一套 Micronaut 多数据源约定。
- 标准配置直接支持 YAML、Consul、环境变量和全部 Hikari 属性，无需维护转换层。
- 连接池、JOOQ Bean 和关闭生命周期都由现有框架管理，Flow 只保留稳定的具名注入
  边界。
- 固定 `flow` qualifier，继续防止 Flow Repository 误用宿主默认数据库。

## 后果

- CSES 必须在有效配置中提供非空 `datasources.flow.url` 及所需凭证。
- CSES 不需要实现 Java Configuration，也不需要为 Flow 编写 DataSource/JOOQ
  Factory。
- 未声明 `datasources.flow` 时不会创建 Flow 数据库 Bean；声明错误或数据库不可用
  时按标准 Micronaut/Hikari 行为启动失败。
- Flow Core 仅保留 `FlowDatabase.DATA_SOURCE_NAME` 作为 `flow` qualifier 常量；独立
  运行和嵌入运行的配置方式一致。
