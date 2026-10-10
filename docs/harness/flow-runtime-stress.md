# Flow 运行逻辑压力验证

本手册验证已有运行能力在持续调用、并发操作和恢复过程中的逻辑闭环。
压测通过公开 `FlowService`、`ExecutionService` 驱动真实 Micronaut、PostgreSQL
和 Pulsar；独立业务 HTTP 服务记录调用次数，作为重复副作用的观察点。
本机数据不代表生产容量，也不证明所有故障点已经覆盖。
用户明确要求不使用生产环境：本轮所有数据库、Broker 和业务 HTTP 目标均为本机隔离
测试资源。复用正式代码中的连接池实现，不表示连接生产数据库或部署到生产环境。

本轮执行结果见 [压力报告](../test-reports/flow/STRESS-2026-09-22-1641.md) 和
[UC 批量报告](../test-reports/flow/BATCH-2026-09-22-1641.md)。已发现恢复与取消竞争
反例，后续八类场景的持续负载显式排除了该场景，不能解读为完整混合负载通过。

## 场景优先的验证目标

用户明确本轮重点是测试各种 Flow 场景。先按已有 UC 建立具体编排与用户操作的
覆盖，再通过重复运行和并发执行暴露偶发问题。吞吐、执行次数和时延作为辅助证据；
同一个 Flow 重复执行一万次，不等于覆盖一万种 Flow 场景。

用户进一步明确 Flow 只有基于原生 Task 的编排，不提供审批业务。后续报告按原生
机制分类，历史审批命名的 UC 只作为已有组合测试证据。异常进程退出后恢复的新增
验证见 [原生机制与崩溃恢复](flow-native-recovery.md)；正常关闭 ApplicationContext
后的重启证据不能替代进程强制退出验证。

场景覆盖表至少说明以下内容：

1. 编排结构：顺序、条件、并行、循环、嵌套、子流程，以及已确认的组合。
2. 用户路径：启动、查询、恢复、取消、退回、重试和触发入口。
3. 已确认边界：非法输入、业务失败、重复操作、竞争、服务重启和迟到消息。
4. 可观察结果：实际走过的分支、步骤次数、输出如何被后续消费、派生运行来源，
   以及最终状态和遗留工作。不能只断言启动成功或根实例进入终态。

每个组合都必须映射到已确认 UC 的预期及实际执行证据。单项场景通过不能推导任意
组合通过；尚未确认的父子取消、子流程退回等规则应列为需求边界，不能自行补出
业务预期。按场景分类的导航放在批量报告中，压力报告只补充重复执行和竞争证据。

## 环境隔离

必须使用本轮专属数据库和 Broker。UC fixture 会清理租户数据及测试订阅积压，
不能把这些地址指向业务环境。先确认端口、容器名无占用，再创建以下资源。
相同名字已存在时先核对归属，不覆盖或删除来源不明的资源。

以下是 2026-09-22 本轮使用的配置。测试凭据仅用于专属本地容器。

```bash
docker run -d --name flow-runtime-stress-20260922-pg \
  --memory=1g --cpus=2 \
  -p 127.0.0.1:55441:5432 \
  -e POSTGRES_USER=flow -e POSTGRES_PASSWORD=flow -e POSTGRES_DB=flow \
  postgres:17 -c max_connections=150

docker run -d --name flow-runtime-stress-20260922-pulsar \
  --memory=3g --cpus=4 \
  -p 127.0.0.1:56661:56661 -p 127.0.0.1:58091:8080 \
  -e PULSAR_PREFIX_brokerServicePort=56661 \
  -e PULSAR_PREFIX_isDelayedDeliveryDeliverAtTimeStrict=true \
  -e PULSAR_MEM='-Xms512m -Xmx1g -XX:MaxDirectMemorySize=512m' \
  apachepulsar/pulsar:4.0.7 bash -c \
  'bin/apply-config-from-env.py conf/standalone.conf && exec bin/pulsar standalone --advertised-address 127.0.0.1 --no-functions-worker --no-stream-storage'
```

等待 PostgreSQL ready 与 Broker health 正常后，初始化一次性空测试库。
建表入口通过相对 `\ir` 引用同目录下的表脚本，因此复制整个基线目录。

```bash
docker exec flow-runtime-stress-20260922-pg pg_isready -U flow -d flow
curl --fail http://127.0.0.1:58091/admin/v2/brokers/health
docker cp gen/sql/flow flow-runtime-stress-20260922-pg:/tmp/flow-baseline
docker exec flow-runtime-stress-20260922-pg \
  psql -v ON_ERROR_STOP=1 -U flow -d flow -f /tmp/flow-baseline/001_create_flow_tables.sql
docker exec flow-runtime-stress-20260922-pg \
  createdb -U flow flow_stress_host
```

`flow_stress_host` 是具名数据源隔离测试用的空宿主库。当前本地 PAAS 依赖需要 JDK 25；
仅为验证命令选择该 JDK，不修改项目工具链或依赖版本。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export FLOW_POSTGRES_TEST_URL=jdbc:postgresql://127.0.0.1:55441/flow
export FLOW_POSTGRES_DEFAULT_TEST_URL=jdbc:postgresql://127.0.0.1:55441/flow_stress_host
export FLOW_POSTGRES_TEST_USER=flow
export FLOW_POSTGRES_TEST_PASSWORD=flow
export FLOW_POSTGRES_TEST_CLEANUP=true
export FLOW_PULSAR_TEST_URL=pulsar://127.0.0.1:56661
export FLOW_PULSAR_TEST_ADMIN_URL=http://127.0.0.1:58091
```

## 执行顺序与证据

1. 保存 Git HEAD 和当前工作区状态，以区分已有修改与本轮测试修改。
2. 按 [UC 测试规范](../standards/uc-testing.md) 逐个执行全部 Accepted UC，
   对首次失败单独复验，再运行模块与完整回归。
3. 执行独立技术压力测试，逐级增加并发，并进行持续混合负载。
   并发数表示负载端同时在途的场景数，不等同于 Worker 并发度。
4. 对可复现的卡死或重复副作用停止提升负载，保存实例身份、操作序列、状态与消息现场，
   单独复验。业务预期失败与框架意外异常分别计数。
5. 在 fixture 清理前查询最终状态和积压。清理完成不能用作业务闭环证据。

每个阶段保留独立日志和 JUnit XML，不能让后续 Gradle 执行覆盖失败现场。
本轮原始产物目录为 `build/stress-validation/20260922-1440/`，包括资源采样
`resource-samples.jsonl`。该目录属于本地构建产物；长期结论写入
`docs/test-reports/flow/`，依照规范滚动保留。

## 压力入口与参数

技术测试入口为
[`RuntimeStressTest`](../../core/src/test/java/org/cses/flow/core/services/executions/RuntimeStressTest.java)，
不绑定新的 UC，也不扩大已有 UC 语义。普通 `test` 默认跳过这个长时入口，
必须显式设置 `FLOW_STRESS_ENABLED=true`。

| 参数 | 用途 |
| --- | --- |
| `FLOW_STRESS_CONCURRENCY` | 同时在途的用户旅程数，逐级使用 1、4、16、32 |
| `FLOW_STRESS_COUNT` | 有限阶段的旅程总数 |
| `FLOW_STRESS_SECONDS` | 大于 0 时按持续时间发起，替代 COUNT 截止；到期后等待在途场景收尾 |
| `FLOW_STRESS_CONTEXTS` | 同一测试 JVM 中的真实 ApplicationContext 数；本轮用 2，不能等同于两个独立进程 |
| `FLOW_STRESS_TENANTS` | 独立租户数；本轮用 4 |
| `FLOW_STRESS_KIND` | `mixed`、`short`、`composite`、`pause`、`race`、`retry`、`exhaust`、`retry-cancel`、`subflow` 或 `replay` |
| `FLOW_STRESS_KINDS` | `mixed` 使用的逗号分隔场景列表，默认包含全部 9 类；排除场景必须在结果中明确说明 |
| `FLOW_STRESS_DATASOURCE` | 默认 `pooled`，在本地隔离环境通过既有具名 flow DataSource/JOOQ 工厂使用正式连接池实现；`direct` 保留逐查询新建连接的 UC 适配器路径，用于复现本轮初始环境异常 |
| `FLOW_STRESS_POOL_SIZE` | 池化模式中每个 flow 连接池上限，默认 16；两个上下文各自持有独立池 |
| `FLOW_STRESS_OUTPUT` | 本阶段原始 CSV、资源快照和指标输出目录，使用绝对路径 |
| `FLOW_VALIDATION_PHASE` | 本阶段标签；配合独立 Gradle 结果目录保存 XML |

`mixed` 轮流执行各类场景。`short` 是三个成功 HTTP 业务步骤；`composite` 组合
顺序、并行、循环和条件。其余名称分别对应恢复、恢复与取消竞争、重试成功、重试耗尽、
重试等待取消、基本子流程和退回隔离。业务 HTTP 服务由测试自行启动和关闭。

示例：在准备好的隔离环境运行 4 并发、128 个混合旅程，保留数据库现场。
每次运行使用新的输出目录；报告目录也应按阶段隔离。

```bash
export FLOW_POSTGRES_TEST_CLEANUP=false
export FLOW_STRESS_ENABLED=true
export FLOW_STRESS_CONCURRENCY=4
export FLOW_STRESS_COUNT=128
export FLOW_STRESS_SECONDS=0
export FLOW_STRESS_CONTEXTS=2
export FLOW_STRESS_TENANTS=4
export FLOW_STRESS_KIND=mixed
export FLOW_STRESS_DATASOURCE=pooled
export FLOW_STRESS_POOL_SIZE=16
export FLOW_VALIDATION_PHASE=mixed-c4
export FLOW_STRESS_OUTPUT="$PWD/build/stress-validation/manual/mixed-c4"
mkdir -p "$FLOW_STRESS_OUTPUT"
cat > "$FLOW_STRESS_OUTPUT/results.init.gradle" <<'GRADLE'
allprojects {
    tasks.withType(Test).configureEach {
        def root = new File(System.getenv('FLOW_STRESS_OUTPUT'), project.name + '/' + name)
        binaryResultsDirectory.set(new File(root, 'binary'))
        reports.junitXml.outputLocation.set(new File(root, 'xml'))
        reports.html.outputLocation.set(new File(root, 'html'))
        maxParallelForks = 1
        maxHeapSize = '1536m'
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        testLogging { showStandardStreams = false; events 'failed', 'skipped' }
    }
}
GRADLE
./gradlew :core:test --tests '*RuntimeStressTest' --no-parallel --console=plain \
  -I "$FLOW_STRESS_OUTPUT/results.init.gradle"
```

持续阶段将 `FLOW_STRESS_SECONDS` 设为 `600`（10 分钟）或更长。任何意外断言失败
会停止发起新旅程，保留已受理请求的结果；不能把提前停止写成完成了原计划负载。
压力入口禁用 Test 输出缓存和 UP-TO-DATE，每次负载参数变化都必须实际执行，
不能把上一次结果恢复到新目录后当作新一轮压力证据。
负载结束后对 command/event/delay 订阅进行有界排空观察，再复查实例状态与业务
调用次数。记录排空前后的现场，不调用清消息接口制造收敛。
完成独立压测后取消 `FLOW_STRESS_ENABLED`，普通 UC 回归恢复
`FLOW_POSTGRES_TEST_CLEANUP=true`，避免无意触发长时负载或改变既有清理约定。

池化入口同时给 default 数据源配置独立的空宿主测试库，给 flow 数据源配置已建表的
测试库，不读入业务连接。它记录实际 JOOQ/Hikari 类名和池大小，并断言 JOOQ 使用
同一个具名 flow 池；只有配置键而没有运行装配证据不算接入成功。

本轮发现 `race` 竞争反例后，对其余场景使用以下显式列表继续验证：

```bash
export FLOW_STRESS_KINDS=short,composite,pause,retry,exhaust,retry-cancel,subflow,replay
```

这一范围不能写成完整混合负载通过，也不能与直连适配器阶段不加区分地比较吞吐。
恢复全场景验证时取消 `FLOW_STRESS_KINDS`，保留原有竞争断言。

## 结果解读

- 记录受理数、达到预期终态数、超时数和意外失败数；同时保留预期 FAILED/KILLED
  场景，不能把它们直接当作框架故障或从分母中隐藏。
- 耗时应说明从哪个公开操作计时到哪个可查询终态；报告 P50/P95/P99 和吞吐量，
  包含等待重试或人工恢复驱动时必须注明。
- 检查外部调用次数、租户与实例关联，验证完成状态之外的重复执行和串数据问题。
- 分开记录 Execution 活动态、TaskRun 等待态、未发布 retry/scheduler outbox、
  Broker backlog/unacked。技术测试主动构造的未完成记录应单独归因。
- Docker 资源只代表 PostgreSQL/Pulsar；应用 JVM 指标需要单独采集。
- 未定义的业务组合和未注入的故障不能由一次绿色压测推定通过。

## 清理

先保存最终数据库和订阅快照、停止采样，再删除本轮专属容器与匿名卷。
只清理已确认由本轮创建的两个容器，不使用全局 prune。
反例的运行历史可以从专属测试库导出保留；导出文件只属于本地原始证据，不提交到 Git。

```bash
docker exec flow-runtime-stress-20260922-pg \
  pg_dump -U flow -d flow --no-owner --no-privileges | \
  gzip > build/stress-validation/20260922-1440/flow-final.sql.gz
docker rm -fv flow-runtime-stress-20260922-pg flow-runtime-stress-20260922-pulsar
```
