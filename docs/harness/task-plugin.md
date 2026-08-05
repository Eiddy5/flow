# 项目内 Task 插件接入手册

## 适用范围

本手册用于在 Flow 项目源码内新增 Task 类型。当前版本不支持外部插件 JAR、
ServiceLoader、运行时安装、热卸载或独立 ClassLoader。

## 最小实现

一个独立 Task 插件只需要在自己的扩展目录中提供一个具体类：

```java
package org.cses.flow.extensions.notification;

import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.cses.flow.core.domains.tasks.RunResult;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;

import java.util.Map;

@Plugin(
    title = "通知",
    description = "向指定频道发送一条通知"
)
@SuperBuilder
@NoArgsConstructor
public final class Notification extends Task implements RunnableTask {

    @NotBlank
    @Schema(
        title = "频道",
        description = "接收通知的频道名称",
        example = "operations"
    )
    private String channel;

    public String channel() {
        return channel;
    }

    @Override
    public RunResult run(RunContext context) {
        return RunResult.completed(Map.of());
    }

    @Override
    protected Object typeSpecificEqualityState() {
        return channel;
    }
}
```

约束如下：

- 类必须是公共、非抽象类，并具有公共无参构造。
- 类继承 `Task`；`Task` 已实现通用 `Plugin`。
- 类直接标注 `@Plugin`，并恰好实现 `RunnableTask` 或 `OrchestrationTask` 之一。
- `@Plugin` 的 title 和 description 都可选；空 title 自动使用类 simple name，
  description 自动归一为 `""`。当前不支持 alias 或 icon。
- 插件字段保持私有，由 Jackson 字段绑定；只公开只读访问器，不公开 Setter。
- 插件字段使用 Bean Validation 注解声明局部约束。项目代码直接构造对象后调用
  `ModelValidator.validate(task)` 主动校验。
- 插件字段可用 Swagger `@Schema` 提供标题、描述和示例；这些注解同时进入自动
  生成的定义 Schema，不负责指定页面组件或布局。
- 插件有专有定义字段时，应通过 `typeSpecificEqualityState()` 纳入定义相等性；
  多个字段可以返回 record 或不可变 List。

不需要创建 `TaskExtension`、`*TaskPlugin`、注册文件、类型常量、properties Codec
或中心分支。

目录先按语义所有权确定：Log、Notification 等独立扩展能力使用
`extensions/<extension-name>`；Pause、Parallel 以及后续 Loop、Loop Until、
Subflow 是 Flow 自身解释的编排 Task，统一位于 `extensions/flow`，不按具体类型
各自创建目录。

## YAML 定义

`type` 必须是 `Class#getCanonicalName()` 返回的精确值：

```yaml
key: notification-flow
tasks:
  - key: notify-operations
    type: org.cses.flow.extensions.notification.Notification
    channel: operations
```

解析区分大小写，也不 trim。短类型、别名、二进制类名和未注册类名都会失败。
`id`、`parentId` 与 `taskId` 是系统字段，不能出现在 YAML 中。

### Log 消息表达式

`Log` 位于独立的 `extensions/log` 目录，类型名称不添加 `Task` 后缀。`message`
可以混合固定文本和一个或多个 `{{ path.to.value }}`：

```yaml
key: logging-flow
tasks:
  - key: write-log
    type: org.cses.flow.extensions.log.Log
    dependOn:
      - prepare
    message: "处理结果：{{ dependOnOutputs.prepare.result }}"
```

当前路径只读取本次运行输入：`outputs.<key>` 表示直接父 Task 输出，
`dependOnOutputs.<taskKey>.<key>` 表示依赖 Task 输出。表达式不能调用方法、执行
脚本或修改运行上下文；语法错误使部署失败，运行时找不到路径使 Log 明确失败且不
输出未解析消息。

## 目录与定义查询

应用启动后可以通过公共查询接口发现插件：

```text
GET /api/plugins
GET /api/plugins/org.cses.flow.extensions.notification.Notification
```

列表接口按插件包返回 Task 元信息。当前项目内插件全部聚合到 `core` 包，Task 按
canonical class name 排序。详情接口返回同一份元信息和 JSON Schema Draft 7：

- `type` 是必填字符串，`const` 固定为具体类 canonical name。
- `key` 和插件自身的必填字段进入 `required`。
- `id` 不对调用方公开；默认存在的 Task 公共字段不会被误标为必填。
- 未声明字段由 `additionalProperties: false` 拒绝。
- `tasks` 表示任意递归 Task，不展开当前插件清单；子 Task 选型后再查询其详情。

Schema 在第一次详情查询时生成并缓存。Schema 生成失败不影响应用启动，会在对应
详情查询时由框架暴露。

## 装配与验证

1. 运行 `./gradlew :server:compileJava`，确认 Micronaut 能发现该 Bean。
2. 启动应用，调用 `GET /api/plugins`，确认 `core.tasks` 包含该类的 canonical
   name、title 和 description。缺少注解、公共无参构造、恰好一种运行能力等问题
   必须阻止启动。
3. 调用插件详情接口，确认 Schema 包含公共字段和插件专有字段，排除 `id`，并把
   type 固定为该 canonical name。
4. 部署包含该 FQCN 和插件专有字段的 Flow，确认未知字段与非法字段值严格失败。
5. 读取 API 和数据库记录，确认 `type` 都是相同 FQCN。
6. 保存并重新读取 Flow Reversion，确认插件专有字段从 `properties` JSONB 完整
   恢复，嵌套 Task 树结构不变。
7. 对 RunnableTask 验证 Worker 调用 `run`；对 OrchestrationTask 验证只有 Executor
   解释其固定编排特征。

## 边界

- YAML 只能选择启动时已注册的项目内类，不能触发任意 `Class.forName`。
- Task 不直接推进 Execution 或 TaskRun 状态。RunnableTask 只通过最小
  `RunContext` 运行；OrchestrationTask 只声明 Executor 识别的编排特征。
- 普通 Task 通过 `tasks` 保存递归 children，不保存 `parentId`。类型专有包含关系
  通过 `Task.definitionChildren()` 暴露，例如 Pause 的 `pause` Task 随插件
  properties 保存。Repository 在写入普通 `tasks` 时派生 `parent_id`，读取后必须
  同时恢复通用和类型专有定义树。
- 重命名 Task 类或修改 package 会改变持久化类型，是一次显式兼容性变更。
