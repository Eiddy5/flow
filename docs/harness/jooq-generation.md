# Flow 数据库对象生成

依据 [ADR 0127](../decisions/0127-use-generated-database-objects.md)，gen 使用 CSES
同款内部 `ExtJavaGenerator`、`ExtJavaGeneratorStrategy` 和 `JooqPojo`。生成代码
统一位于 `gen/src/main/java/org/flow/gen/flow/`，包括 13 张运行表的 Table、Field、
Record、POJO，以及枚举、主键和索引。数据库底层归 jdbc，各能力在自身模块直接使用
生成表和字段。建表基线见 [ADR 0137](../decisions/0137-provision-postgres-schema-explicitly.md)。

## CSES 参照方式

本次检查的是 CSES `pre-paas2.5` 分支 `b72cd7b2b6`：

- `settings.gradle` 注册独立 `gen` 模块，`gen/build.gradle` 引用内部
  `org.jooq:jooq-codegen-extension`，版本来自 TOML。
- `gen/src/main/java/org/cses/builder/JooqCSESGenerator.java` 调用内部生成器和策略，
  输出 Table、Record、继承 `JooqPojo` 的 `XxxObject`。
- `common` 通过 `api project(':gen')` 向业务层提供生成类型；业务持久化对象可继承
  生成对象，查询使用生成的 Tables/Keys，并复用 `toRecord()` 等转换方法。

Flow 沿用生成器与类型协议，包名使用 `org.flow.gen.flow`。生成入口单独放入 codegen
source set，数据库适配器依赖 gen；领域对象仍遵循当前引擎协议。生成时使用临时库和
当前 PostgreSQL 建表基线。

## 日常编译与重新生成

普通编译直接使用已提交代码，不读取数据库。只有显式生成才需要 PostgreSQL：

```bash
./gradlew :gen:classes
bash docs/harness/generate-jooq.sh
git diff -- gen/src/main/java/org/flow/gen/flow
```

生成脚本要求 Docker 可用，创建临时 PostgreSQL 17 容器、`flow` 数据库和 `public`
schema，并使用随机本地端口。它不读取业务连接，退出时清理本次容器和卷。日志保存
在 `build/gen-validation/run.*/`，包括 Schema、生成器和 PostgreSQL 日志；清理错误
直接输出到终端，即使构建日志目录被删除也会尝试清理容器。

脚本在同一事务内执行 core 的 `src/main/resources/db/postgres/` 中的三份文件。
`types.sql` 创建原生类型与查询表达式，`tables.sql` 创建十二张运行表，`logs.sql` 创建
默认 `logs` 表。宿主初始化和生成器共用这些文件。修改基线后重新生成并检查差异。

Gradle 参数可透传给生成命令，例如已缓存依赖时：

```bash
bash docs/harness/generate-jooq.sh --offline --console=plain
```

不要在另一构建正在编译 gen 时重新生成。生成器会清理目标包中的旧文件；先验证
13 张表和主键是否完整可防止明显错误连接清空输出，但不能代替生成完成后的差异
检查。生成失败后修复环境并重新生成，不能提交不完整产物。

## 显式指定生成数据库

需要检查另一份已准备好的独立测试 Schema 时，可直接运行：

```bash
./gradlew :gen:generateJooq
```

命令从运行环境读取以下变量；值缺失时直接报错，不回退应用数据源：

| 变量 | 含义 |
| --- | --- |
| `FLOW_GEN_URL` | 必填 PostgreSQL JDBC URL |
| `FLOW_GEN_USER` | 必填数据库用户 |
| `FLOW_GEN_PASSWORD` | 必须设置，允许空值 |
| `FLOW_GEN_SCHEMA` | 默认 `public`，显式值不能为空 |

入口也支持 `--url`、`--user`、`--password`、`--schema` 命名参数，优先于环境变量。
常规操作使用环境变量，不把密码写进源码、Gradle 文件或提交记录。生成器直接持有
连接，不把连接凭据写入生成配置和输出文件，也不在失败消息中回显 JDBC 异常正文。

## 依赖与运行边界

`gen/build.gradle` 将 `JooqFlowGenerator` 放在独立 codegen source set，调用内部
生成器；正常运行使用 gen 的 Table、Record 和 `JooqPojo` 子类。gen 不依赖 Flow
运行模块；core、queue、executor、worker、scheduler、indexer 中的持久化直接依赖
gen，数据库底层 jdbc 不依赖 core。各能力数据库包为 `org.flow.<owner>.jdbc`，
Core Domain 和服务契约不使用这些数据库类型。

显式版本统一在 `gradle/libs.versions.toml` 中维护，框架子组件由配套平台管理：

| 组件 | 当前版本 |
| --- | --- |
| 内部 jOOQ、jooq-paas、codegen extension 及 BOM | `3.21.4.1` |
| PaaS BOM / 本地源码 | `2.5.0.29` / `pass-2.0-neworg` |
| Micronaut 平台 | `5.0.3.29`，与本地 PaaS 联合构建对齐 |

gen 的生成和运行 classpath 均使用普通 Micronaut platform 与 enforced jOOQ/PaaS
BOM，避免内部制品默认的 PaaS 2.5/Micronaut 5 提高 JDK 下限。jooq-paas 固定引入
的 Micronaut JOOQ 7.x 在依赖边界排除，再显式声明平台管理的 6.x；生成器的依赖边
执行相同处理。本次 Session 接入已改用 Java 25，继续由环境或标准 Java 配置选择 JDK；见 ADR 0133。

使用内部生成器和 `JooqPojo` 需要 PaaS common、json、sql、exception，以及内部
Bean 装配需要的 paas-pulsar 和必要客户端。这是用户对 ADR 0126 范围的明确修订；
不引入 PaaS 聚合包、AuthX 或 CSES 业务，Flow 队列仍是 JDBC。

内部 DBEventSender 及条件 Bean 定义依赖 Pulsar 类型，运行期保留这些类型。根
`verifyRuntimeIsolation` 允许以下必要制品，并排除 HTTP Server Netty、PaaS 聚合包、
AuthX、CSES 业务和 jOOQ codegen/meta；生成器仍留在独立 codegen classpath。

| 制品组 | 允许的模块 |
| --- | --- |
| `org.paas` | paas-common、paas-json、paas-sql、paas-exception、paas-pulsar |
| `org.apache.pulsar` | bouncy-castle-bc、pulsar-client-admin-api、pulsar-client-api、pulsar-client-original、pulsar-common |
| `org.jooq` | jooq、jooq-paas |

jdbc 的 `FlowDatabaseContextConfigurer` 使用 `@ContextConfigurer` 注册名为
`flow-jdbc-runtime-defaults`、顺序为 `Integer.MIN_VALUE` 的 PropertySource：

| 默认配置 | 默认值 | 用途 |
| --- | --- | --- |
| `jooq.send-event` | `false` | 不发送内部 jOOQ DB 事件 |
| `table.db-change.listeners.pulsar.enabled` | `false` | 不启用数据库变更的 Pulsar 监听 |
| `pulsar.consumer.enabled` | `false` | 不启用内部 Pulsar 消费者 |

这些都是低优先级默认值；宿主 YAML、环境变量、系统属性或 builder 显式配置可
覆盖它们。Flow 自身不使用 Pulsar 工作队列，保留客户端类型不等于连接消息服务。
配置默认值与宿主覆盖的既有专项结果见下文日期记录；模块迁移后的结果需单独验证。

core 获得数据库依赖后，tooling 也会经 core 传递包含 jdbc、gen 及相关数据库库。
工具依然不启动 Context、不连接数据库；其隔离检查允许这些库，继续禁止实际运行
角色及 jOOQ codegen/meta。生成器始终只存在于 gen 的独立 codegen classpath。

外部消费工程仍需自行配置内部仓库，完整规则见
[依赖管理](dependency-management.md#内部-micronaut-与消费方仓库)。除内部 Micronaut
外，仓库内容过滤还包括内部 jOOQ 和 PaaS：

```groovy
includeVersionByRegex 'org[.]jooq', '.*', '[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+.*'
includeGroupByRegex 'org[.]paas([.].*)?'
```

生成结果不带固定 schema，运行时继续使用配置的表名、别名、schema、`search_path`
与独立日志数据源。JSONB 仍采用原生序列化，事务和队列协议保持不变。写入只使用
可写列，不对 `GENERATED ALWAYS` 字段使用全列 POJO Map；旧 Entry/DataSourceEngine
规则的适用边界见 [JOOQ 规范](../standards/jooq.md#当前原生模块适用边界)。

## 存储侧使用生成对象

直接使用规则见 [ADR 0128](../decisions/0128-use-generated-tables-directly.md)。例如：

```java
var executions = jdbcRepository.getTable(Tables.EXECUTIONS);
context.select(executions.VALUE)
    .from(executions)
    .where(executions.ID.eq(executionId));
```

`getTable(Tables.X)` 返回当前配置的真实生成表实例，保留其枚举、JSONB、二进制与
时间字段类型。配置物理表名称使用该生成类型的 `rename(Name)`，查询别名使用
`.as(...)` 后的字段。不要用 `getName()` 再创建物理 Field，也不要复制生成表字段。
API 筛选和排序只允许当前生成表已有字段。

`JdbcTableConfig` 的构造入口要求显式提供生成表，不再通过中央表名 switch 推断
元数据。core、queue、executor、worker、scheduler、indexer 各自的
`<Owner>JdbcTableConfigsFactory` 负责本模块表绑定，通过 `configured(...)` 逐表
读取 `flow.jdbc.tables.<name>.table`，缺省使用该生成表名称。只覆盖其中一张表的
物理名称时，其他表继续采用各自默认值；不能仅添加陌生配置键来生成新的物理表模型。

`FlowJavaGenerator` 继承内部 `ExtJavaGenerator`，通过正式生成扩展点输出按限定
表名解析的 `getSchema()`；默认表不固定 schema，别名不加 schema。修订生成规则后，
仍须运行上面的生成脚本，不能手工修改输出文件。

原生持久化只写 key、JSONB value 等实际可写字段，保留数据库计算列。领域枚举、
时间和队列游标在值边界转换；窗口、聚合、JSON 路径、UTC 和全文函数的表达式引用
生成字段。AVG/SUM 结果按实际聚合值转换为指标 API 的 Double，不改变物理列类型。

## 2026-10-15：首次接入验证

清理全部子模块后，Java 21 全模块编译、testClasses 和 JAR 构建通过。73 个生成
Java 文件重复生成前后的 SHA-256 完全一致。

完整回归已执行约 27 分钟，101 个任务全部实际执行；15 个有测试的工程、514 个
测试套件共 5,246 项，5,216 项通过、26 项跳过、4 项失败。与 `fc1e3f4` 归档基线
相比新增 49 项全部通过，无缺失工程或测试套件；本轮完整命令仍返回失败。

- 3 项失败仍来自 `PebbleDocBasicMdDriftTest`，依赖已退出的 UI `basic.md`。
- `PurgeKVTest.version_filter_by_date` 预期 1、实际 0，在当前代码与提交前
  `fc1e3f4` 快照的同一 PostgreSQL 容器中均复现；原始测试和临时诊断测试在两边
  各失败 1 项。旧版本置为 `last=false` 时，既有触发器更新物理 `updated`，而
  JSON 内的 `updated` 不变。物理列在当前/基线分别晚于 cutoff 670/823 微秒，
  因此日期筛选不命中。四组离线 SQL、绑定类型与精度对照相同，证明提交前即可
  复现；此前通过时没有精确时序日志，不推测当时原因。本轮未为消除该失败修改
  业务语义、原测试断言或迁移 SQL。详细证据见本机
  `build/gen-validation/kv-diagnosis/README.md`。
- 根模块 16 项真实 PostgreSQL、引擎、生命周期及进程强杀测试通过；PostgreSQL
  模块 984 项通过，包含自定义带引号表名与 schema 的验证。
- gen 36 项、JDBC 新增 7 项元数据及 5 项默认值/宿主覆盖专项测试均通过。

完整回归统计已固定保存在 `build/gen-validation/test-summary.json`，514 个套件
的 JUnit 结果归档为 `build/gen-validation/junit-results.zip`。后续 focused 测试
结果与这份全量记录分开保存，不能用局部重跑覆盖全量统计。

最终 129 个配置全部解析成功。410 个外部依赖 JAR 的普通类和 Java 21 生效的
Multi-Release 类均已检查，没有缺失制品或高于 major 65 的类；2,612 个生产类的
最高字节码版本为 65。根 runtime 有 278 个制品（含项目制品），gen 为 152 个，
tooling 为 181 个。根与 gen 不含 HTTP Server Netty、jOOQ codegen/meta；实际
PaaS 只有上述五个模块且均为 `1.0.0.161`，jooq/jooq-paas 为 `3.21.4.1`，codegen
配置的 jOOQ 家族也统一为 `3.21.4.1`。

仅依赖 `org.flow:flow:2.0.5` 的独立 composite 工程执行 `clean run verifyDependencies`
通过，耗时 9 秒，输出 `host-java-consumer-ok java=21` 和
`gen-consumer-dependencies-ok`。该检查覆盖公开 API 编译、运行角色类加载及最终
依赖，不启动 Context；真实 Context 由根 PostgreSQL/引擎测试覆盖。本轮没有发布
Maven 制品，也不把源码消费结果扩展为 Maven 发布验收。

最终来源账本校验及 `git diff --check` 通过。完整回归仍保留上述 4 项失败，不能
记为全绿；归档全量结果与后续诊断、独立消费结果分别保留。


## 2026-10-16：直接使用生成表的验证

本轮按 ADR 0128 移除复制表元数据、手写物理 Field 和未知表兜底，重新生成全部表；
生成器的限定 schema、带空格名称和别名输出已验证。生产 jOOQ 使用范围为 JDBC、
PostgreSQL 与队列三个适配模块，共 53 个引用 jOOQ 的生产文件。

- 全项目生产与测试源码编译通过，根运行产物和 tooling 运行依赖隔离检查通过。
- gen 60 项全部通过；根模块 16 项真实 PostgreSQL/引擎测试全部通过。
- JDBC 107 项：96 通过、11 跳过，没有失败；包含真实生成字段、类型、配置拒绝及
  AVG/SUM 聚合结果转换的回归测试。迁移测试通过 harness 提供的独立库执行。
- PostgreSQL 984 项全部通过，包括实际 schema/表名配置、查询别名、标签与源码
  全文、排序、UTC 分组、生成枚举、JSONB/bytea 及队列相关行为。
- 完整命令 `bash docs/harness/verify-flow.sh --full-regression --console=plain`
  执行约 25 分钟，101 个任务中 24 实际执行、77 为 UP-TO-DATE。15 个有测试的工程、
  514 个套件汇总 5,274 项：5,244 通过、26 跳过、4 失败。与首次 gen 接入归档相比，
  净增加 28 项通过，无缺失工程、套件或新增失败。未受影响套件由 Gradle 复用已有
  结果，gen 的 60 项在本次生成后单独执行通过。

四项失败仍为上述三项 UI 文档检查和 PurgeKV 日期筛选；完整命令因此返回 1，不能
记为全绿。本轮未修改这些失败的业务行为、测试断言或迁移 SQL。

本机证据保存在 `build/gen-storage-validation/`：`full-regression.log`、
`test-summary.json`、`junit-results.zip` 和 `baseline-comparison.json`。旧
`build/gen-validation/` 归档继续保留。来源账本与 `git diff --check` 通过，本轮
临时生成库和回归库已清理。此记录对应提交前验证，未发布制品。
