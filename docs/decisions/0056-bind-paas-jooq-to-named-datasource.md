# ADR 0056：按数据源名称绑定 PAAS JOOQ Configuration

## 状态

Accepted（修订 ADR 0040 中关于直接复用 PAAS JOOQ Factory 的实现条款）

## 背景

Flow 使用 Micronaut 的具名 `datasources.flow` 数据源，并在 Core 中通过
`@Named("flow")` 注入 `org.x9.jooq.JOOQ`。PAAS 提供的 `JooqFactory` 对
`org.jooq.Configuration` 使用了无限定名注入。在 Flow 嵌入 CSES 等宿主时，宿主
可能另外提供无限定名的 JOOQ Configuration，导致启动时出现多个
`Configuration` 候选，进而无法创建 Flow Controller 依赖的 `JOOQ`。

## 备选方案

### 方案一：要求宿主删除或限定自己的 Configuration

可以避免冲突，但把 Flow 与宿主的实现细节耦合在一起，且会破坏宿主已有的 JOOQ
配置边界。

### 方案二：在 Flow 的 JOOQ 消费方继续绕过 PAAS Factory

可以局部规避冲突，但会复制 JOOQ Facade、DataSource unwrap 和生命周期逻辑，无法
保证独立启动与嵌入启动行为一致。

### 方案三：替换 PAAS Factory，并按 EachBean 数据源名称选择 Configuration

保留 PAAS `JOOQ` Facade 和 Micronaut 数据源生命周期，但用同一个具名 qualifier
选择对应的 `Configuration`。

## 决策

采用方案三。

- `core` 中的 `NamedJooqFactory` 替换 PAAS `JooqFactory`。
- Factory 对每个 `DataSource` 使用 `@EachBean` 提供的名称，按同名 qualifier 读取
  JOOQ `Configuration`，再创建同名 `JOOQ` Facade。
- Flow 的 `flow` 数据源因此只绑定 `flow` Configuration；宿主的 `default` 或其
  他数据源仍绑定各自的 Configuration。
- 宿主可以继续提供额外的无限定名 JOOQ Configuration；这些 Bean 不参与 Flow
  DataSource 到 JOOQ 的选择。

## 理由

数据源名称是 Micronaut JDBC、JOOQ Configuration 和 PAAS JOOQ Facade 之间已经存在
的稳定边界。沿用该边界可以同时支持独立 Flow Server 和嵌入 CSES，并避免依赖注入
候选数量影响 Flow 启动。

## 后果

- Flow 不改变 `datasources.flow.*` 配置契约，也不回退到宿主默认数据库。
- `FlowNamedDataSourceTest` 覆盖宿主存在多个无限定名 Configuration 时的回归场景。
- 若宿主没有为某个 DataSource 创建同名 JOOQ Configuration，启动会明确失败；宿主
  仍需按 Micronaut JOOQ 约定提供对应配置。
