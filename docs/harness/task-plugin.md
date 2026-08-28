# 构建期 Task 插件接入手册

## 适用范围

本手册用于在 Flow 源码、宿主应用或普通构建依赖中新增 Task 类型。插件必须在构建期
进入宿主 classpath，并具有 Micronaut BeanDefinition；当前版本不支持插件目录扫描、
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
import org.cses.flow.core.plugins.annotations.Example;
import org.cses.flow.core.plugins.annotations.Plugin;
import org.cses.flow.core.runner.RunContext;

import java.util.Map;

@Plugin(
    title = "通知",
    description = "向指定频道发送一条通知",
    examples = {
        @Example(
            title = "向运维频道发送通知",
            code = """
                key: notification-flow
                tasks:
                  - key: notify-operations
                    type: org.cses.flow.extensions.notification.Notification
                    channel: operations
                """,
            full = true
        )
    }
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
        return RunResult.success(Map.of());
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
- 类必须位于具名 Java package；该真实 package path 自动成为插件目录分组，无需
  单独声明或注册来源。
- `@Plugin` 的 title、description 和 examples 都可选；空 title 自动使用类 simple
  name，description 自动归一为 `""`，examples 自动归一为空列表。当前不支持 alias
  或 icon。
- `Example` 是独立注解类型，但只能作为 `@Plugin.examples` 的数组元素使用，不能
  直接标注插件类。`code` 保存一个或多个独立完整 Flow YAML 源码块，`lang` 默认是
  `yaml`。
- `Example.full=true` 时，源码块必须自行包含 Flow `key`、`tasks` 和准确的插件
  canonical `type`，消费方直接使用该 Flow；`full=false` 仅兼容历史元信息，新插件
  不得再声明配置片段。任何形式都不能声明 `id`、`parentId` 或 `taskId`。
- 插件字段保持私有，由 Jackson 字段绑定；只公开只读访问器，不公开 Setter。
- 插件字段使用 Bean Validation 注解声明局部约束。项目代码直接构造对象后调用
  `ModelValidator.validate(task)` 主动校验。
- 插件字段可用 Swagger `@Schema` 提供标题、描述和示例；这些注解同时进入自动
  生成的定义 Schema，不负责指定页面组件或布局。
- 插件有专有定义字段时，应通过 `typeSpecificEqualityState()` 纳入定义相等性；
  多个字段可以返回 record 或不可变 List。

插件不需要创建 `TaskExtension`、`*TaskPlugin`、注册文件、类型常量、插件专属
properties Codec 或中心分支。数据库 Adapter 统一通过共享的
`TaskPropertiesCodec` 持久化插件专有字段。

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
  - key: prepare
    type: org.cses.flow.extensions.tasks.AutomaticTask
    outputs:
      - key: result
        type: STRING
  - key: write-log
    type: org.cses.flow.extensions.log.Log
    message: "处理结果：{{ outputs.prepare.result }}"
```

当前路径只读取本次 TaskRun 的可见输入。串行作用域中，
`outputs.<taskKey>.<outputKey>` 表示位于当前 Task 之前且已经完成的 Task 输出；并行
兄弟之间不共享这个输出域。表达式不能调用方法、执行脚本或修改运行上下文；语法错误
使部署失败，运行时找不到路径使 Log 明确失败且不输出未解析消息。

## 目录与定义查询

应用启动后可以通过公共查询接口发现插件：

```text
GET /api/plugins
GET /api/plugins/org.cses.flow.extensions.notification.Notification
```

列表接口按真实 Java package 返回轻量 Task 元信息，不返回示例源码。分组与每个 Task
元信息都包含
`packageName`，其值直接来自 `Class#getPackageName()`；package 按完整路径、Task 按
canonical class name 排序。详情接口返回同一份元信息、`examples` 和 JSON Schema
Draft 7。每个 example 包含 `title`、`code`、`lang` 和 `full`：

- `type` 是必填字符串，`const` 固定为具体类 canonical name。
- `key` 和插件自身的必填字段进入 `required`。
- `id` 不对调用方公开；默认存在的 Task 公共字段不会被误标为必填。
- 未声明字段由 `additionalProperties: false` 拒绝。
- 只有继承 `Branch` 的结构型插件才公开 `tasks`，其元素表示任意递归 Task，不展开
  当前插件清单；子 Task 选型后再查询其详情。普通 Runnable Task 和 Pause 不公开
  `tasks`。

Schema 在第一次详情查询时生成并缓存。Schema 生成失败不影响应用启动，会在对应
详情查询时由框架暴露。

## 装配与验证

1. Flow 内插件运行 `./gradlew :core:compileJava`；宿主插件编译对应宿主模块，确认
   Micronaut 能发现该 Bean。
2. 启动应用，调用 `GET /api/plugins`，确认目标 `packageName` 分组包含该类的
   canonical name、packageName、title 和 description。缺少注解、公共无参构造、
   具名 package 或恰好一种运行能力等问题必须阻止启动。
3. 调用插件详情接口，确认 examples 保持声明顺序和源码内容；Schema 包含公共字段和
   插件专有字段，排除 `id`，并把 type 固定为该 canonical name。
4. 部署包含该 FQCN 和插件专有字段的 Flow，确认未知字段与非法字段值严格失败。
5. 读取 API 和数据库记录，确认 `type` 都是相同 FQCN。
6. 保存并重新读取 Flow Reversion，确认插件专有字段从 `properties` JSONB 完整
   恢复，嵌套 Task 树结构不变。
7. 对 RunnableTask 验证 Worker 调用 `run`；对 OrchestrationTask 验证只有 Executor
   解释其固定编排特征。

## 边界

- YAML 只能选择启动时已注册的项目内类，不能触发任意 `Class.forName`。
- Task 不直接推进 Execution 或 TaskRun 状态。RunnableTask 通过 `RunContext` 运行；
  `variables` 的保留键 `$flow.execution` 和 `$flow.inputs` 分别携带当前 Execution
  与实际输入，Task 应通过 `executionId()` 和 `inputs()` 读取它们，不得修改 Execution
  或直接访问 Flow 的 Execution/TaskRun Repository。宿主业务能力应通过明确的扩展接口
  接入，不通过通用容器查找。OrchestrationTask 只声明 Executor 识别的编排特征。
- 抽象 Task 不保存 `tasks` 或 `parentId`。结构型 `Branch` 通过 `tasks` 保存递归
  children；Pause 的 `pause` 是类型专有包含关系。所有包含关系都通过
  `Task.definitionChildren()` 暴露。Repository 写入定义树时派生 `parent_id`，读取后
  必须按具体 Task 类型恢复 Branch 子树或类型专有定义树。
- 重命名 Task 类或修改 package 会改变持久化类型，是一次显式兼容性变更。
