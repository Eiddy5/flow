# Workflow Core V2 Implementation Plan

> **For agentic workers:** Implement task-by-task with test-first verification. Do not preserve V1 internals when they conflict with the V2 architecture.

**Goal:** Replace the V1 runtime prototype with the V2 CommandContext, Session and staged Operation architecture while preserving all four VER-FLOW-001 scenarios.

**Architecture:** CommandExecutor owns one CommandContext per call. Commands perform initial loading and bind FlowContext; FlowOperations use OperationContext, write each completed stage through RuntimeSession and schedule the next operation. The first adapter is entirely in memory.

**Tech Stack:** Java 21, Gradle, JUnit 5, Micronaut project layout.

---

### Task 1: Command execution lifecycle

**Files:**
- Create: `server/src/test/java/org/cses/flow/runtime/CommandExecutionLifecycleTest.java`
- Create: `server/src/main/java/org/cses/flow/runtime/context/CommandContext.java`
- Create: `server/src/main/java/org/cses/flow/runtime/context/CommandContextFactory.java`
- Create: `server/src/main/java/org/cses/flow/runtime/context/CommandContextState.java`
- Create: `server/src/main/java/org/cses/flow/runtime/execution/EngineOperation.java`
- Create: `server/src/main/java/org/cses/flow/runtime/execution/FlowOperation.java`
- Create: `server/src/main/java/org/cses/flow/runtime/execution/OperationScheduler.java`
- Replace: `server/src/main/java/org/cses/flow/runtime/execution/ExecutionQueue.java`
- Replace: `server/src/main/java/org/cses/flow/runtime/execution/ExecutionRunner.java`
- Replace: `server/src/main/java/org/cses/flow/runtime/command/Command.java`
- Replace: `server/src/main/java/org/cses/flow/runtime/command/CommandOperation.java`
- Move replacement to: `server/src/main/java/org/cses/flow/runtime/execution/CommandExecutor.java`

Steps:

- [ ] Add failing tests proving one successful commit, rollback on operation failure, FIFO execution and an empty queue after return.
- [ ] Run `./gradlew :server:test --tests '*CommandExecutionLifecycleTest'` and confirm missing V2 types fail compilation.
- [ ] Implement the minimal CommandContext and execution loop.
- [ ] Re-run the target test and confirm it passes.

### Task 2: Session ports and in-memory transaction adapter

**Files:**
- Create: `server/src/main/java/org/cses/flow/definition/session/DefinitionSession.java`
- Create: `server/src/main/java/org/cses/flow/runtime/session/RuntimeSession.java`
- Create: `server/src/main/java/org/cses/flow/runtime/session/EngineSessionFactory.java`
- Create: `server/src/main/java/org/cses/flow/runtime/session/EngineTransaction.java`
- Create: `server/src/main/java/org/cses/flow/runtime/query/RuntimeQuery.java`
- Create: `server/src/main/java/org/cses/flow/infrastructure/memory/InMemoryRuntimeState.java`
- Create: `server/src/main/java/org/cses/flow/infrastructure/memory/InMemoryDefinitionSession.java`
- Create: `server/src/main/java/org/cses/flow/infrastructure/memory/InMemoryRuntimeSession.java`
- Create: `server/src/main/java/org/cses/flow/infrastructure/memory/InMemoryEngineTransaction.java`
- Create: `server/src/main/java/org/cses/flow/infrastructure/memory/InMemoryEngineSessionFactory.java`
- Create: `server/src/main/java/org/cses/flow/infrastructure/memory/InMemoryRuntimeQuery.java`

Steps:

- [ ] Extend lifecycle tests with commit/rollback visibility assertions against in-memory state.
- [ ] Confirm the new assertions fail.
- [ ] Implement session-local runtime snapshots, commit publication and rollback discard.
- [ ] Confirm the lifecycle tests pass.

### Task 3: Runtime models and narrow contexts

**Files:**
- Replace: `server/src/main/java/org/cses/flow/runtime/context/FlowContext.java`
- Create: `server/src/main/java/org/cses/flow/runtime/context/OperationContext.java`
- Create: `server/src/main/java/org/cses/flow/runtime/context/ActivityContext.java`
- Create: `server/src/main/java/org/cses/flow/runtime/context/ResumeTarget.java`
- Modify: `server/src/main/java/org/cses/flow/runtime/model/Process.java`
- Modify: `server/src/main/java/org/cses/flow/runtime/model/Executor.java`
- Modify: runtime state enums and Activity.
- Move Task model into `runtime/model` and add copy support.
- Replace TaskCompletedSignal with the four external fields defined by V2.

Steps:

- [ ] Add failing model state-transition and resume-relation tests.
- [ ] Implement validated state transitions and copy constructors used by memory transactions.
- [ ] Re-run model tests.

### Task 4: Behavior protocol and staged Operations

**Files:**
- Move behavior contracts to `runtime/behavior`.
- Create: `NodeExecutionResult`, `TaskDefinition` and START/ACTION/WAIT/END implementations.
- Create under `runtime/execution/operation`: `EnterNodeOperation`, `LeaveNodeOperation`, `TraverseEdgeOperation`, `ResumeNodeOperation`, `EndProcessOperation`.

Steps:

- [ ] Migrate `FlowStartAndNodeProgressionTest` to the V2 public interfaces and in-memory adapters.
- [ ] Run it and confirm failures are caused by missing V2 operation behavior.
- [ ] Implement Enter, Leave, Traverse, Resume and End operations in that order.
- [ ] Re-run after each operation stage until all four scenarios pass.

### Task 5: Commands, Engine and Task facade

**Files:**
- Replace: `StartFlowCommand`, `HandleSignalCommand`, `FlowEngine`, `TaskService`.
- Replace external `CompleteTaskCommand` with `CompleteTaskRequest`.
- Update test fixture and snapshot printer to use RuntimeQuery and CommandContext.

Steps:

- [ ] Add architecture assertions that FlowEngine only depends on CommandExecutor and TaskService only depends on FlowEngine.
- [ ] Implement the V2 facades and commands.
- [ ] Verify TaskService performs no pre-query and Signal contains no internal runtime IDs.
- [ ] Run VER-FLOW-001 tests and inspect all eight snapshots.

### Task 6: Remove V1 runtime structure and migrate project memory

**Files:**
- Delete V1 repositories, in-memory runtime repositories, FlowContextFactory, ExecutionOperationFactory, ContinueExecutorOperation and TakeOutgoingEdgesOperation.
- Update `docs/standards/in-memory-workflow-core.md`.
- Update `docs/verification/01-flow-start-and-node-progression.md`.
- Update `docs/agents/reviewer.md` where V1 ownership rules remain.

Steps:

- [ ] Remove all V1-only classes after V2 tests pass.
- [ ] Search production code for direct runtime Repository dependencies.
- [ ] Synchronize standards and verification rules with V2.
- [ ] Run the target test, full test suite and full build.
- [ ] Confirm `./gradlew build` succeeds with no test failures.
