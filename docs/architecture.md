# Flow 当前代码架构与核心流程

## 文档定位

本文基于当前仓库源码描述 Flow 的代码模块、运行时依赖和核心业务流程，用于快速理解
系统，而不是替代 `docs/decisions/` 中的架构决策。目录职责以
[`project-structure.md`](project-structure.md) 为准，具体设计原因以
[`decisions/README.md`](decisions/README.md) 索引的 ADR 为准。

当前图覆盖：

- `core` 中的完整非 HTTP Flow 能力与 `server` 中的 HTTP、启动边界。
- `gen` 的开发期数据库基线如何由人工执行，以及 JOOQ 生成代码如何进入 Core。
- 从 FlowDraft 保存、Flow 部署到 Execution 执行、暂停、恢复、取消和结束的主流程。

## 当前代码架构图

```mermaid
flowchart LR
    subgraph clients ["调用方"]
        browser["Flow API 客户端"]
        externalCaller["外部业务能力"]
    end

    subgraph server ["server 模块：HTTP 服务与启动"]
        subgraph inbound ["入站与应用装配"]
            micronautApp["Application / Micronaut Netty"]
            pluginController["PluginController"]
        end
    end

    subgraph coreModule ["core 模块：完整非 HTTP Flow 能力"]
        subgraph useCases ["Core 公开用例"]
            flowService["FlowService"]
            executionService["ExecutionService"]
            pluginService["PluginService"]
        end

        subgraph application ["命令与查询"]
            commandExecutor["CommandExecutor + HandlerRegistry"]
            commandHandlers["CommandHandlers"]
            queryHandlers["QueryHandlers"]
            yamlParser["JacksonMapper / YamlParser / FlowDefinitionDeserializer"]
            pluginSchema["PluginSchemaGenerator"]
        end

        subgraph coreRuntime ["领域与运行时"]
            domains["FlowDraft / Flow / Execution / TaskRun / State"]
            repositoryPorts["Core Repository 端口"]
            queueContracts["DispatchQueue / QueueSubscription"]
            executor["DefaultExecutor 路由 + ExecutorCommandHandler + ExecutionRunner + ExecutorService"]
            worker["WorkerDispatcher"]
            pluginRuntime["PluginRegistry / RegisteredPlugin / PluginMetadata"]
        end

        subgraph taskExtensions ["Task 扩展"]
            autoTask["AutomaticTask @Plugin / RunnableTask"]
            pauseTask["Pause @Plugin / OrchestrationTask"]
            parallelTask["Parallel @Plugin / OrchestrationTask"]
            inProjectPlugins["项目内具体 Task @Plugin"]
        end

        subgraph infrastructure ["基础设施适配"]
            postgresRepositories["PostgreSQL Repositories + Entries"]
            defaultQueue["DefaultDispatchQueue + JsonFactory / Class"]
            jooqBoundary["具名 flow JOOQ"]
        end
    end

    subgraph gen ["gen 模块"]
        migrations["PostgreSQL 建表基线"]
        generatedJooq["JOOQ 生成 Tables / Records / POJOs"]
    end

    postgres[(Flow 独立 PostgreSQL / public schema)]
    consul["Consul 配置与服务注册"]

    browser -->|"HTTP / JSON"| micronautApp
    micronautApp --> pluginController
    pluginController --> pluginService
    externalCaller -->|"executionId + taskRunId + outputs"| executionService

    flowService --> commandExecutor
    executionService --> commandExecutor
    flowService --> queryHandlers
    executionService --> queryHandlers
    pluginService --> pluginRuntime
    pluginService --> pluginSchema
    commandExecutor --> commandHandlers

    commandHandlers --> domains
    commandHandlers --> repositoryPorts
    commandHandlers --> executor
    commandHandlers --> yamlParser
    commandHandlers --> pluginRuntime
    queryHandlers --> repositoryPorts
    yamlParser -->|"严格物化完整 Flow"| domains
    pluginRuntime -->|"按 canonical class name 绑定具体 Task"| domains
    pluginSchema -->|"复用严格 Jackson 定义契约"| yamlParser

    executor --> domains
    executor --> repositoryPorts
    executor -->|"解释 OrchestrationTask"| pauseTask
    executor -->|"解释 OrchestrationTask"| parallelTask
    executor --> worker
    worker -->|"调用 RunnableTask.run"| autoTask

    autoTask -.->|"Micronaut 编译期发现"| pluginRuntime
    pauseTask -.->|"Micronaut 编译期发现"| pluginRuntime
    parallelTask -.->|"Micronaut 编译期发现"| pluginRuntime
    inProjectPlugins -.->|"Micronaut 编译期发现"| pluginRuntime

    postgresRepositories -.->|"实现"| repositoryPorts
    defaultQueue -.->|"实现"| queueContracts
    commandExecutor -->|"开启写事务"| jooqBoundary
    queryHandlers -->|"开启读事务"| jooqBoundary
    postgresRepositories --> generatedJooq
    defaultQueue --> generatedJooq
    defaultQueue -->|"JSONB / 周期轮询 / FOR UPDATE SKIP LOCKED"| jooqBoundary
    jooqBoundary -->|"DSLContext / SQL"| postgres
    migrations -.->|"启动前由部署人员手工执行"| postgres
    postgres -.->|"构建期读取 schema 并生成"| generatedJooq
    consul -.->|"生产环境启动配置"| micronautApp

    classDef caller fill:#E8F1FF,stroke:#4A78C2,color:#172B4D
    classDef inboundNode fill:#E9F7EF,stroke:#3D9970,color:#173B2A
    classDef coreNode fill:#FFF4D6,stroke:#D49B22,color:#4A3400
    classDef extensionNode fill:#F3E8FF,stroke:#8C5AC2,color:#35204D
    classDef adapterNode fill:#FFECE8,stroke:#C96952,color:#4A2118
    classDef storeNode fill:#E8EEF5,stroke:#60758A,color:#21313F

    class browser,externalCaller caller
    class micronautApp,pluginController inboundNode
    class flowService,executionService,pluginService,commandExecutor,commandHandlers,queryHandlers,yamlParser,domains,repositoryPorts,queueContracts,executor,worker,pluginRuntime,pluginSchema coreNode
    class autoTask,pauseTask,parallelTask,inProjectPlugins extensionNode
    class postgresRepositories,defaultQueue,jooqBoundary,migrations,generatedJooq adapterNode
    class postgres,consul storeNode
```

图中实线表示主要运行时调用或数据访问，虚线表示 SPI 实现、构建期关系或配置关系。
`core` 是完整的非 HTTP Flow Gradle 模块，包含 Core Java 包、Executor、
Worker、扩展与 Infrastructure；`server` 是只保留 HTTP 与启动职责的薄模块，并通过
`api` 传递暴露 Core。Server 既可独立启动，也可完整嵌入 CSES，两者不是独立微服务。
Flow 基线只由部署人员对 Flow 数据库手工执行，运行时 JOOQ 只使用具名 `flow` 数据源。
开发期基线变化后需要重建该数据库，不提供旧 Schema 或旧数据的升级路径。
Core 提供类型化 Dispatch Queue Interface，并已把 Execution 启动接入使用统一
`queues` 表的 Default Adapter。该表以
`queue_type + queue_name` 隔离传输类别和逻辑 Queue；当前 Adapter 只写入并领取
`DISPATCH` 行。业务 Module 仍需为具体 Event 提供可空 `dsl()`、确定的 `Class<T>` 和
具名 Bean；Event 内部 `eventType` 仍由业务自行维护。当前 `ExecutorCommand`、
`Create`、具名 Queue Bean、只做路由的 eager `DefaultExecutor` 和统一
`ExecutorCommandHandler` 已连线；Broadcast Interface、消费游标或保留清理仍未实现。

`DefaultDispatchQueue` 直接读取 `Event.dsl()`：同步整批 Event 必须全部返回 `null`，
或全部返回同一个 `DSLContext` 实例；前者使用 Queue 自有事务，后者加入调用方事务。
Default Queue 使用项目现有 `JsonFactory` 把业务 Event 重组为排除 DSL 的 JSONB Queue
Entry，并用装配时传入的 `Class<T>` 恢复类型。异步发布始终在独立事务中提交，后台
任务只携带 Entry，不携带 Event 的 DSL。每个 Subscription 使用虚拟线程周期轮询
数据库，并在领取事务内按 `queue_type = 'DISPATCH'`、`queue_name` 通过
`FOR UPDATE SKIP LOCKED` 竞争并调用 Consumer；只有 Consumer 正常返回才删除消息，
异常会回滚领取事务并重试。未来 Broadcast 消息载荷仍写入 `queues`，所需的
广播专属投递状态单独保存，不拆分消息载荷表。

## 核心业务流程图

```mermaid
flowchart TD
    saveStart(["用户保存或更新 Flow 草稿"])
    saveService["FlowService.saveDraft"]
    saveTransaction["CommandExecutor 开启 JOOQ 写事务"]
    saveDraft["SaveFlowDraftHandler 保存 FlowDraft 并校验乐观锁"]

    deployStart(["用户部署 Flow"])
    parseDefinition["DeployFlowHandler 加载草稿并解析 YAML"]
    materializeFlow["FlowDefinitionDeserializer 按真实类地址绑定 Task；Flow.deploy 形成精确 Reversion"]
    persistFlow["保存 Flow 与 FlowTasks"]

    executeStart(["用户启动最新且未删除的 Flow"])
    createExecution["ExecutionService 加载最新 Flow，生成 executionId 并构造 Create"]
    enqueueStart["写入 ExecutorCommand Queue"]
    acceptedEnd(["返回 CREATED 与 executionId"])
    consumeStart["DefaultExecutor 路由到 ExecutorCommandHandler；恢复 Session 并创建 Execution"]
    drive["ExecutionRunner 提交边界"]
    handle["ExecutorService.handle 状态推进循环"]
    plan["按顺序、route、dependOn 和 PARALLEL 规划下一批 TaskRun"]
    hasNext{"存在下一批 TaskRun？"}
    capability{"Task 运行能力？"}

    orchestrationDispatch["ExecutorService 直接启动 OrchestrationTask"]
    orchestrationKind{"编排特征？"}
    parallelScope["Parallel TaskRun 保持 RUNNING；释放可运行直接分支"]
    pauseAction["Pause TaskRun 保持 RUNNING；无条件执行 pause Task 完整子树"]
    pausePersist["pause 子树收敛后，持久化 Pause TaskRun 与 Execution 的稳定暂停点"]

    runnableDispatch["TaskRun 进入 RUNNING 并先持久化"]
    workerRun["WorkerDispatcher 同步调用 RunnableTask.run"]
    workerResult{"RunResult 目标状态？"}
    applyOutputs["校验 outputs 并完成 TaskRun"]
    terminate["终止 TaskRun 与 Execution 并持久化"]

    settled{"所有匹配任务已收敛？"}
    completeExecution["Execution 进入 COMPLETED 并持久化"]
    scopeSettled{"存在子树已收敛的 RUNNING Parallel？"}
    completeScope["完成 Parallel TaskRun 并继续规划"]
    pausedLeaves{"剩余叶子 TaskRun 是否为 PAUSED？"}
    runningStable["Execution 保持 RUNNING 并提交稳定暂停点"]

    resumeStart(["外部提交 Resume"])
    resumeValidation["锁定 Execution 并加载其精确 Flow Reversion"]
    resumeTask["按 resume Input 校验回调；Pause TaskRun 从 PAUSED 恢复为 RUNNING；Execution 保持 RUNNING"]

    cancelStart(["用户取消 Execution"])
    cancelExecution["锁定 Execution；终止未完成 TaskRun 并持久化"]

    completedEnd(["COMPLETED"])
    terminatedEnd(["TERMINATED"])

    saveStart --> saveService
    saveService --> saveTransaction
    saveTransaction --> saveDraft
    saveDraft --> deployStart
    deployStart --> parseDefinition
    parseDefinition --> materializeFlow
    materializeFlow --> persistFlow
    persistFlow --> executeStart
    executeStart --> createExecution
    createExecution --> enqueueStart
    enqueueStart --> acceptedEnd
    enqueueStart -.->|"事务提交后异步消费"| consumeStart
    consumeStart --> drive

    drive --> handle
    handle --> plan
    plan --> hasNext
    hasNext -->|"是"| capability
    capability -->|"OrchestrationTask"| orchestrationDispatch
    orchestrationDispatch --> orchestrationKind
    orchestrationKind -->|"Parallel 作用域"| parallelScope
    parallelScope --> handle
    orchestrationKind -->|"Pause"| pauseAction
    pauseAction --> pausePersist
    pausePersist --> runningStable

    capability -->|"RunnableTask"| runnableDispatch
    runnableDispatch --> workerRun
    workerRun --> workerResult
    workerResult -->|"COMPLETED"| applyOutputs
    applyOutputs --> handle
    workerResult -->|"TERMINATED"| terminate
    terminate --> terminatedEnd

    hasNext -->|"否"| settled
    settled -->|"是"| completeExecution
    completeExecution --> completedEnd
    settled -->|"否"| scopeSettled
    scopeSettled -->|"是"| completeScope
    completeScope --> handle
    scopeSettled -->|"否"| pausedLeaves
    pausedLeaves -->|"是"| runningStable
    pausedLeaves -->|"否"| runningStable

    resumeStart --> resumeValidation
    runningStable -.->|"外部结果到达"| resumeStart
    resumeValidation --> resumeTask
    resumeTask --> handle

    cancelStart --> cancelExecution
    cancelExecution --> terminatedEnd

    classDef startEnd fill:#E8F1FF,stroke:#4A78C2,color:#172B4D
    classDef action fill:#FFF4D6,stroke:#D49B22,color:#4A3400
    classDef decision fill:#F3E8FF,stroke:#8C5AC2,color:#35204D
    classDef waiting fill:#FFE7B3,stroke:#D98500,color:#4A2E00
    classDef success fill:#E5F6E9,stroke:#3D9970,color:#173B2A
    classDef failure fill:#FFE3DE,stroke:#C7503E,color:#4A1812

    class saveStart,deployStart,executeStart,acceptedEnd,resumeStart,cancelStart startEnd
    class saveService,saveTransaction,saveDraft,parseDefinition,materializeFlow,persistFlow,createExecution,enqueueStart,consumeStart,drive,handle,plan,orchestrationDispatch,parallelScope,completeScope,pauseAction,pausePersist,runnableDispatch,workerRun,applyOutputs,resumeValidation,resumeTask,cancelExecution,runningStable action
    class hasNext,capability,orchestrationKind,workerResult,settled,scopeSettled,pausedLeaves decision
    class pausePersist waiting
    class completeExecution,completedEnd success
    class terminate,terminatedEnd failure
```

流程图强调当前实现中的关键事实：

1. 普通启动由 `ExecutionService` 构造 `Create` 并写入 Executor Command Queue；Service
   返回只代表受理，`ExecutorCommandHandler` 在新事务中创建并推进 Execution。可信
   pending continuation 仍在同一 JOOQ 事务保存 Execution 和 Queue Command。
2. Execution 始终绑定启动时的精确 Flow Reversion，继续、恢复和取消时不会切换到
   新版本。
3. `RunnableTask` 只由 Worker 调用；`OrchestrationTask` 只由 Executor 解释。当前 Worker
   同步执行，并遵循“持久化 TaskRun 后再调用”的顺序。
4. Pause 先执行其必填 `pause` Task 子树，子树收敛后持久化稳定暂停点；该 Pause
   TaskRun 是唯一持久化等待事实。合法 Resume 校验并恢复精确 Pause TaskRun，再由
   Executor 完成和推进，因此不依赖原 Server 进程仍然存活，也不需要额外等待聚合。
5. `ExecutorService.handle` 在内部连续收敛非 Runnable 的 OrchestrationTask；只有形成
   WorkerTask 或到达稳定状态时才返回 `ExecutionRunner` 提交边界。`DefaultExecutor`
   不参与状态推进，只做 Queue 路由。

## 主要源码依据

- [`server/src/main/java/org/cses/flow/controller/plugins/PluginController.java`](../server/src/main/java/org/cses/flow/controller/plugins/PluginController.java)
- [`core/src/main/java/org/cses/flow/core/commands/CommandExecutor.java`](../core/src/main/java/org/cses/flow/core/commands/CommandExecutor.java)
- [`core/src/main/java/org/cses/flow/core/services/executions/ExecutionService.java`](../core/src/main/java/org/cses/flow/core/services/executions/ExecutionService.java)
- [`core/src/main/java/org/cses/flow/executor/commands/ExecutorCommand.java`](../core/src/main/java/org/cses/flow/executor/commands/ExecutorCommand.java)
- [`core/src/main/java/org/cses/flow/executor/commands/Create.java`](../core/src/main/java/org/cses/flow/executor/commands/Create.java)
- [`core/src/main/java/org/cses/flow/executor/DefaultExecutor.java`](../core/src/main/java/org/cses/flow/executor/DefaultExecutor.java)
- [`core/src/main/java/org/cses/flow/executor/handlers/ExecutorCommandHandler.java`](../core/src/main/java/org/cses/flow/executor/handlers/ExecutorCommandHandler.java)
- [`core/src/main/java/org/cses/flow/executor/ExecutionRunner.java`](../core/src/main/java/org/cses/flow/executor/ExecutionRunner.java)
- [`core/src/main/java/org/cses/flow/executor/ExecutorService.java`](../core/src/main/java/org/cses/flow/executor/ExecutorService.java)
- [`core/src/main/java/org/cses/flow/worker/WorkerDispatcher.java`](../core/src/main/java/org/cses/flow/worker/WorkerDispatcher.java)
- [`core/src/main/java/org/cses/flow/infrastructure/jooq/`](../core/src/main/java/org/cses/flow/infrastructure/jooq/)
- [`core/src/main/java/org/cses/flow/infrastructure/repositories/`](../core/src/main/java/org/cses/flow/infrastructure/repositories/)
- [`core/src/main/java/org/cses/flow/infrastructure/queues/`](../core/src/main/java/org/cses/flow/infrastructure/queues/)
- [`gen/sql/flow/001_create_flow_tables.sql`](../gen/sql/flow/001_create_flow_tables.sql)
- [`gen/sql/flow/tables/`](../gen/sql/flow/tables/)
