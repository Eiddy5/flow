---
name: datapilot-index-fallback-scan
description: |
  Configure and operate cloud-datapilot DB-to-Elasticsearch fallback scanning with
  @DBIndexFallbackScan, DBIndexFallbackScanConfig, AUTO/FIXED scheduling, Redis state,
  Pulsar delayed messages, watermark overlap, and safety delay. Use when users ask about
  ES missed-event recovery, DBIndex consistency repair, fallback scan, index compensation,
  or automatically catching up records that were not synchronized to Elasticsearch.
argument-hint: "[datasource] [collection]"
allowed-tools: Read
paths: "**/*.java"
version: 1.0.0
---

# DBIndex 兜底扫描

该能力周期性扫描 DB 中最近变更的记录并批量 upsert 到 Elasticsearch，用于补偿消息丢失、消费者中断或短时索引失败。它是增量一致性保障，不替代 FULL rebuild。

## 前置条件

- 全局 `datapilot.es.fallback-scan.enabled=true`。
- collection 已启用 ES 索引。
- collection 存在类型为 `FieldType.UpdateAt` 的更新时间字段，字段名不要求固定。
- 引擎已配置 Redis、Pulsar 和 Elasticsearch。缺少 Redis 或 Pulsar 时模块不会启动。
- 当前 SDK 坐标：`org.x9.cloud:cloud-datapilot:2.0.47`。

## 启用方式

### Micronaut 注解

```java
import org.dataPilot.dbindex.conf.DBIndexFallbackScanStrategy;
import org.dataPilot.micronaut.annotation.DBIndexFallbackScan;

@Table(name = "orders", dataSourceKey = "sales")
@Dbindex
@DBIndexFallbackScan(
    enabled = true,
    strategy = DBIndexFallbackScanStrategy.AUTO,
    intervalSeconds = 300
)
public class Order extends MicModel {
    @UpdateAtField
    private Long updatedAt;
}
```

表级参数只控制当前 collection：

| 参数 | 默认值 | 含义 |
|---|---:|---|
| `enabled` | `true` | 显式启用或关闭当前 collection |
| `strategy` | `AUTO` | 调度策略：`AUTO` 或 `FIXED` |
| `intervalSeconds` | `300` | FIXED 周期，或 AUTO 无统计样本时的初始间隔 |

### Java collection option

```java
DBIndexFallbackScanOption scan = new DBIndexFallbackScanOption();
scan.setEnabled(true);
scan.setStrategy(DBIndexFallbackScanStrategy.FIXED);
scan.setIntervalSeconds(180);

DbCollectionOption option = DbCollectionOption.builder()
    .name("orders")
    .esIndex(es -> es.enabled(true))
    .fallbackScan(scan)
    .build();
```

## 全局配置

```yaml
datapilot:
  es:
    fallback-scan:
      enabled: true
      auto-enable-indexed-collections: false
      default-strategy: AUTO
      default-interval-seconds: 300
      min-interval-seconds: 60
      max-interval-seconds: 3600
      target-rows-per-scan: 5000
      overlap-seconds: 600
      safety-delay-seconds: 60
```

| 配置 | 作用 |
|---|---|
| `enabled` | 模块总开关，默认关闭 |
| `auto-enable-indexed-collections` | 自动启用所有“已建 ES 索引且有 UpdateAt 字段”的 collection |
| `default-strategy` | 表级未指定策略时使用 |
| `default-interval-seconds` | 固定周期或 AUTO 初始周期 |
| `min/max-interval-seconds` | AUTO 计算周期的上下限 |
| `target-rows-per-scan` | AUTO 希望每轮覆盖的 insert+update 变化行数 |
| `overlap-seconds` | 下一轮从旧水位向前重叠，避免边界漏扫 |
| `safety-delay-seconds` | 扫描上界落后当前时间，避开未稳定事务 |

非 Micronaut 环境可直接配置引擎：

```java
DBIndexFallbackScanConfig scan = new DBIndexFallbackScanConfig();
scan.setEnabled(true);
scan.setAutoEnableIndexedCollections(true);
scan.setDefaultStrategy(DBIndexFallbackScanStrategy.AUTO);
scan.setDefaultIntervalSeconds(300);
scan.setMinIntervalSeconds(60);
scan.setMaxIntervalSeconds(3600);
scan.setTargetRowsPerScan(5000);
scan.setOverlapSeconds(600);
scan.setSafetyDelaySeconds(60);

engineConfiguration.setDbIndexFallbackScanConfig(scan);
```

## AUTO 与 FIXED

- `FIXED`：每次成功后按表级或全局 interval 调度下一次扫描。
- `AUTO`：PostgreSQL 根据 `pg_stat_user_tables` 的 insert/update 变化速率计算下一次间隔，并限制在 min/max 之间。
- 非 PostgreSQL 使用 `AUTO` 时自动降级为 `FIXED`。
- 统计不可用、被 reset 或没有历史样本时使用默认 interval。

## 扫描语义

每轮窗口为：

```text
from = lastSuccessWatermark - overlapSeconds
to   = taskStartTime - safetyDelaySeconds
```

扫描按主键升序分页，生产环境每批 300 条，Development 模式每批 100 条。命中的记录会同时更新主索引、bucket index 和 split index。成功后通过 Redis CAS 推进水位，再发送下一条 Pulsar 延迟消息；失败由 Pulsar 重投，旧消息不会重复推进状态。

## 选择策略

| 场景 | 建议 |
|---|---|
| 高频 PostgreSQL 表 | `AUTO`，根据写入速率动态调度 |
| 写入稳定、希望可预测负载 | `FIXED` |
| 只有少数关键表需要补偿 | 关闭 auto-enable，逐表加注解 |
| 所有索引表都有可靠 UpdateAt | 开启 auto-enable，再对高压表显式关闭 |
| 大范围历史缺口 | 使用 FULL/INCREMENTAL rebuild，不依赖兜底扫描 |

## 限制与陷阱

- 兜底扫描只查询 DB 中仍存在的记录并 upsert ES，不能发现“DB 已删除但 ES 仍残留”的文档。
- `enabled=true` 但没有 UpdateAt 类型字段时，该 collection 不会启动扫描。
- `overlapSeconds` 增大可降低漏扫风险，但会增加重复 upsert。
- 不要把 `safetyDelaySeconds` 设为负数；负值会归一化为 0。
- 不要同时用极短 interval 和很大的扫描窗口，容易放大 DB/ES 压力。
- Redis 状态和 Pulsar 延迟消息共同保证调度推进，不能只配置其中一个。

## 验收清单

- [ ] 全局总开关已开启
- [ ] collection 已启用 ES index
- [ ] collection 有 UpdateAt 类型字段
- [ ] Redis、Pulsar、ES 均可用
- [ ] AUTO 策略仅在 PostgreSQL 上依赖统计
- [ ] overlap 与 safety delay 符合事务延迟特征
- [ ] 大范围历史缺口另行执行 rebuild
