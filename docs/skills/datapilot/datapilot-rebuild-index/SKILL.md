---
name: datapilot-rebuild-index
description: |
  Rebuild secondary indexes (Elasticsearch / in-memory) for a DataPilot collection.
  Use when the user asks to "rebuild index", "reindex", "refresh index", "重建索引",
  "datapilot 索引重建", or needs FULL / INCREMENTAL / PARTIAL / CLEAR reindex via
  HTTP `/dataPilot/rebuildIndex` or the embedded `DataSourceService.rebuildIndex`.
  Scope: 覆盖 `RebuildOption` / `RebuildMode` 与两种调用入口，以及启动时
  `@Dbindex(mappingVersion)` 版本校验与索引自动创建；日常 DB→ES 漏同步补偿请配合
  `datapilot-index-fallback-scan`。
argument-hint: "[collection] [mode]"
allowed-tools: Read Grep Glob Bash
paths: "src/main/java/org/dataPilot/dbindex/**"
version: 0.3.0
---

# DataPilot Rebuild Index

为已有集合重建二级索引的唯一权威入口。通过 `RebuildOption` + `RebuildMode`
触发 HTTP 或嵌入式调用，覆盖全量 / 增量 / 指定主键 / 清空四种语义。

## When to Use

- “帮我重建 xxx 集合的索引 / reindex collection”
- “ES 索引和源表对不上，需要刷新”
- “只想重跑这几条主键的索引”
- “先把索引清掉再重来”
- 编辑 `org.dataPilot.dbindex.conf.RebuildOption`、`DataSourceService#rebuildIndex`
  或 `DataSourceController#rebuildIndex` 附近代码时

## RebuildOption Fields

位置：`org.dataPilot.dbindex.conf.RebuildOption`

| Field           | Type           | Purpose |
|-----------------|----------------|---------|
| `name`          | `String`       | 目标集合名；必填 |
| `dataSourceKey` | `String`       | 数据源 key；多数据源场景必填，单数据源可省略并由默认解析器补齐 |
| `clearIndex`    | `boolean`      | 重建前是否先清空现有索引；与 `mode=CLEAR` 语义不同，这里是“先清后建” |
| `mode`          | `RebuildMode`  | 重建语义枚举，见下表 |
| `values`        | `List<Object>` | 主键列表，仅 `PARTIAL` 模式使用；其它模式应留空 |

## RebuildMode Semantics

位置：`org.dataPilot.dbindex.conf.RebuildMode`

| Value         | Semantics | When to Use |
|---------------|-----------|-------------|
| `FULL`        | 扫描源表全部记录，重建整份索引 | 索引结构升级、数据漂移严重、首次补建 |
| `INCREMENTAL` | 按增量水位（更新时间 / 自增键）补齐新增与更新 | 定时任务、追平日常写入、代价最低 |
| `PARTIAL`     | 仅对 `values` 列出的主键重建 | 个别记录异常、业务纠正、单条修复 |
| `CLEAR`       | 仅删除索引、不再重建 | 下线集合、准备彻底重做前的清场 |

> 提示：`clearIndex=true` + `mode=FULL` 与直接 `mode=CLEAR` 不等价；前者会紧接着全量
> 写回，后者只清不建。

## 启动时索引自动检查

系统启动时，`DataPilotIndex.ensureIndexMappingsOnStartup()` 会**仅对标注了 `@Dbindex` 的集合**执行 ES 索引检查：

| 场景 | 行为 |
|------|------|
| ES 索引不存在 | 自动创建（含主索引、分片索引、桶索引） |
| 索引存在，`mappingVersion` 匹配 | 跳过 |
| 索引存在，`mappingVersion` 不匹配 + `autoUpdate=false` | **warn 告警**，不自动重建，不阻断启动 |
| 索引存在，`mappingVersion` 不匹配 + `autoUpdate=true` | 自动重建索引（`FULL` 模式，删旧索引→建新索引→全量写回） |

> `mappingVersion` 在 `@Dbindex` 注解中声明。为空时使用 mapping 结构 SHA-256 摘要比对（向后兼容）。
> `autoUpdate` 默认 `false`，保持向后兼容。设为 `true` 后 mapping 变更自动触发全量重建，适合 DataPilot 全权管理索引的场景。

## 与兜底扫描的边界

| 需求 | 使用能力 |
|---|---|
| mapping 变化、首次建索引、严重历史漂移 | `FULL` rebuild |
| 已知少量主键异常 | `PARTIAL` rebuild |
| 日常消息丢失或消费者短时中断 | DBIndex fallback scan |
| DB 已删除但 ES 残留 | 查询清理或 rebuild；fallback scan 不处理删除 |

兜底扫描按 UpdateAt 水位持续 upsert 最近变更行，配置见 `datapilot-index-fallback-scan` skill。它不替代显式 rebuild，也没有 `/dataPilot/rebuildIndex` HTTP 入口。

### @Dbindex 注解属性

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `versionControlField` | `String` | `""` | 索引更新版本控制字段 |
| `mappingVersion` | `String` | `""` | mapping 版本号，为空时使用结构摘要 |
| `autoUpdate` | `boolean` | `false` | mapping 版本不匹配时是否自动全量重建 |
| `sharding` | `boolean` | `false` | 是否启用分片索引 |
| `shardingType` | `boolean` | `false` | 分片类型开关（历史字段） |

## HTTP Examples

端点：`POST /dataPilot/rebuildIndex`，请求体为 `RebuildOption` JSON。

### FULL 全量重建

```bash
curl -X POST http://localhost:8080/dataPilot/rebuildIndex \
  -H "Content-Type: application/json" \
  -d '{
    "name": "order",
    "dataSourceKey": "main",
    "clearIndex": true,
    "mode": "FULL"
  }'
```

### INCREMENTAL 增量追平

```bash
curl -X POST http://localhost:8080/dataPilot/rebuildIndex \
  -H "Content-Type: application/json" \
  -d '{
    "name": "order",
    "dataSourceKey": "main",
    "clearIndex": false,
    "mode": "INCREMENTAL"
  }'
```

### PARTIAL 按主键修复

```bash
curl -X POST http://localhost:8080/dataPilot/rebuildIndex \
  -H "Content-Type: application/json" \
  -d '{
    "name": "order",
    "dataSourceKey": "main",
    "mode": "PARTIAL",
    "values": ["1001", "1002", "1003"]
  }'
```

### CLEAR 清空索引

```bash
curl -X POST http://localhost:8080/dataPilot/rebuildIndex \
  -H "Content-Type: application/json" \
  -d '{
    "name": "order",
    "dataSourceKey": "main",
    "mode": "CLEAR"
  }'
```

### 仅重建单个集合（多集合库内定向）

```bash
curl -X POST http://localhost:8080/dataPilot/rebuildIndex \
  -H "Content-Type: application/json" \
  -d '{
    "name": "order_item",
    "dataSourceKey": "main",
    "mode": "FULL"
  }'
```

## Java Embedded Example

注入 `DataSourceService`，直接在进程内触发重建。

```java
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.dataPilot.context.DataPilotContext;
import org.dataPilot.dbindex.conf.RebuildMode;
import org.dataPilot.dbindex.conf.RebuildOption;
import org.dataPilot.service.DataSourceService;
import org.dataPilot.common.exception.DataPilotException;
import org.dataPilot.common.exception.MetaChangeException;

import com.google.gson.JsonObject;

import java.util.List;

@Singleton
public class OrderIndexMaintainer {

    @Inject
    DataSourceService<DataPilotContext, ?> dataSourceService;

    /** 全量重建订单集合 */
    public JsonObject rebuildOrderFull(DataPilotContext ctx) {
        RebuildOption option = new RebuildOption();
        option.name = "order";
        option.dataSourceKey = "main";
        option.clearIndex = true;
        option.mode = RebuildMode.FULL;
        return invoke(ctx, option);
    }

    /** 增量追平 */
    public JsonObject rebuildOrderIncremental(DataPilotContext ctx) {
        RebuildOption option = new RebuildOption();
        option.name = "order";
        option.dataSourceKey = "main";
        option.mode = RebuildMode.INCREMENTAL;
        return invoke(ctx, option);
    }

    /** 按主键修复 */
    public JsonObject rebuildOrderPartial(DataPilotContext ctx, List<Object> ids) {
        RebuildOption option = new RebuildOption();
        option.name = "order";
        option.dataSourceKey = "main";
        option.mode = RebuildMode.PARTIAL;
        option.values = ids;
        return invoke(ctx, option);
    }

    /** 清空索引 */
    public JsonObject clearOrderIndex(DataPilotContext ctx) {
        RebuildOption option = new RebuildOption();
        option.name = "order";
        option.dataSourceKey = "main";
        option.mode = RebuildMode.CLEAR;
        return invoke(ctx, option);
    }

    private JsonObject invoke(DataPilotContext ctx, RebuildOption option) {
        try {
            return dataSourceService.rebuildIndex(ctx, option);
        } catch (MetaChangeException e) {
            // 元数据变更：需先刷新 schema 再重试
            throw e;
        } catch (DataPilotException e) {
            // 通用 SDK 错误：上抛由调用方决定重试策略
            throw e;
        }
    }
}
```

## Workflow

1. 确认目标 collection、dataSourceKey、ES index 和当前 mappingVersion
2. 按 `RebuildMode` 选型：日常追平用 `INCREMENTAL`，修复脏数据用 `PARTIAL`，
   结构变化用 `FULL`，下线用 `CLEAR`
3. 构造 `RebuildOption`，填 `name` / `dataSourceKey`；只有 `PARTIAL` 才设 `values`
4. 走 HTTP 或嵌入式调用之一；捕获重建相关异常
5. 校验返回 `JsonObject` 中的成功标识，并对源表抽样与索引结果比对

## Checklist

- [ ] 已选定正确的 `RebuildMode`
- [ ] `dataSourceKey` 在多数据源环境下已显式指定
- [ ] `PARTIAL` 模式提供了非空 `values`，其它模式 `values` 为空
- [ ] `clearIndex` 与 `mode` 语义未混淆
- [ ] 捕获 `MetaChangeException` 与 `DataPilotException`
- [ ] 重建完成后做了抽样校验

## Pitfalls

| Pitfall | Detail | Mitigation |
|---|---|---|
| 阻塞语义 | `rebuildIndex` 为同步调用，`FULL` 模式在大表上会长时间占用调用线程 | 放到后台执行器或独立线程池，HTTP 端配合更长超时 |
| 数据源 key 解析 | 多数据源下未传 `dataSourceKey` 会落到默认源，造成“重建了错的库” | 多源环境强制显式指定 |
| 失败恢复 | 重建过程中断后索引可能处于半新半旧状态 | 失败后用 `clearIndex=true` + `FULL` 重跑，或对受影响主键走 `PARTIAL` |
| 在途读请求 | 重建期间读请求可能命中旧数据或空结果；`CLEAR` 期间查询会返回空 | 业务侧对关键读路径加降级或延迟重建到低峰期 |
| 旧方法兼容 | 近期修复了数据源 rebuild 时对旧字段选项兼容失效的问题；老客户端若仍按旧字段构造 `RebuildOption`，需确认已升级到包含该修复的版本 | 升级 SDK 或改用新字段；复核 `RebuildOption` 反序列化路径 |
| `clearIndex` 与 `CLEAR` 混淆 | 两者语义不同，误用会导致“本想清空结果又写回”或“本想重建结果只清了” | 参考上文模式表严格区分 |

## Exceptions to Catch

| Exception | 含义 | 处理建议 |
|---|---|---|
| `MetaChangeException` | 集合 schema 与索引定义不一致 | 刷新元数据后再触发 `FULL` |
| `DataPilotException` | SDK 通用错误基类 | 记录日志、上抛或按业务策略重试 |
| `RuntimeException` | 底层存储 / ES 客户端异常 | 视情况重试或告警 |

## Common Mistakes

| Mistake | Fix |
|---|---|
| 使用 `CLEAR` 期望顺带全量重建 | 改用 `clearIndex=true` + `mode=FULL` |
| `PARTIAL` 模式忘记填 `values` | 补齐主键列表，否则等价于无操作 |
| 在请求线程里对大表执行 `FULL` | 移交后台任务，避免 HTTP 超时与连接堆积 |
| 忽略 `dataSourceKey` | 多数据源下必填，防止误操作其他库 |
