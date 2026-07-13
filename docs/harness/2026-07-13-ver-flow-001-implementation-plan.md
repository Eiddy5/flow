# VER-FLOW-001 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use test-driven-development and execute each task in order. Do not write production behavior before its failing acceptance test.

**Goal:** Implement the in-memory backend slice required by `01-flow-start-and-node-progression.md` for automatic and manual node progression.

**Architecture:** Keep the core in the existing `server` module under `org.cses.flow`. Definition and runtime repositories are interfaces with in-memory implementations; FlowEngine creates a FlowContext and Command, CommandExecutor schedules the first CommandOperation, and ExecutionRunner only drains ExecutionQueue operations.

**Tech Stack:** Java 21, Gradle, JUnit 5, Micronaut project runtime, Java collections for in-memory state.

---

### Task 1: Acceptance test skeleton

**Files:**
- Create: `server/src/test/java/org/cses/flow/runtime/FlowStartAndNodeProgressionTest.java`

- [ ] Write four acceptance tests named after S1 through S4.
- [ ] Build Flow definitions with START, ACTION, WAIT, and END nodes.
- [ ] Assert Process, Executor, Activity, Task, path order, and queue-stable outcomes from the verification document.
- [ ] Run `./gradlew :server:test --tests '*FlowStartAndNodeProgressionTest'`.
- [ ] Confirm RED because the requested core API does not exist.

### Task 2: Flow definition and deployment

**Files:**
- Create: `server/src/main/java/org/cses/flow/definition/model/Flow.java`
- Create: `server/src/main/java/org/cses/flow/definition/model/FlowState.java`
- Create: `server/src/main/java/org/cses/flow/definition/model/Node.java`
- Create: `server/src/main/java/org/cses/flow/definition/model/NodeType.java`
- Create: `server/src/main/java/org/cses/flow/definition/model/Edge.java`
- Create: `server/src/main/java/org/cses/flow/definition/command/CreateFlowCommand.java`
- Create: `server/src/main/java/org/cses/flow/definition/repository/FlowRepository.java`
- Create: `server/src/main/java/org/cses/flow/definition/service/FlowService.java`
- Create: `server/src/main/java/org/cses/flow/definition/service/FlowValidator.java`
- Create: `server/src/main/java/org/cses/flow/infrastructure/memory/InMemoryFlowRepository.java`
- Create: `server/src/main/java/org/cses/flow/infrastructure/memory/InMemoryIdGenerator.java`

- [ ] Add the minimal draft and deployed lifecycle used by the acceptance tests.
- [ ] Assemble Node incoming/outgoing and Edge source/target references inside a complete Flow.
- [ ] Validate exactly one START, at least one END, valid references, reachability, and legal outgoing edges.
- [ ] Assign the next deployed version per Flow key.
- [ ] Keep deployed definitions externally immutable.
- [ ] Re-run the target test and confirm remaining failures are runtime API gaps.

### Task 3: Runtime model and automatic execution kernel

**Files:**
- Create runtime models under `server/src/main/java/org/cses/flow/runtime/model/` for Process, Executor, Activity, Signal, and states.
- Create repository interfaces under `server/src/main/java/org/cses/flow/runtime/repository/`.
- Create in-memory runtime repositories under `server/src/main/java/org/cses/flow/infrastructure/memory/`.
- Create FlowContext and FlowContextFactory under `runtime/context/`.
- Create Command, CommandOperation, CommandExecutor, StartFlowCommand under `runtime/command/`.
- Create ExecutionOperation, ExecutionQueue, ExecutionRunner, ContinueExecutorOperation, and TakeOutgoingEdgesOperation under `runtime/execution/`.
- Create ActivityBehavior APIs and START/ACTION/END behaviors under `behavior/`.
- Create: `server/src/main/java/org/cses/flow/runtime/engine/FlowEngine.java`

- [ ] Make `FlowEngine.start(flowId)` select the latest deployed Flow for the requested Flow key.
- [ ] Make StartFlowCommand create Process and ask Process to create the root Executor.
- [ ] Make CommandExecutor create the first CommandOperation.
- [ ] Keep ExecutionRunner limited to queue draining.
- [ ] Create one Activity per entered Node and automatically continue through START, ACTION, and END.
- [ ] Run the target test until S1 is GREEN.

### Task 4: Manual wait and complete

**Files:**
- Create Task model, state, command, repository, and TaskService under `server/src/main/java/org/cses/flow/task/`.
- Create WAIT behavior under `server/src/main/java/org/cses/flow/behavior/wait/`.
- Create HandleSignalCommand under `server/src/main/java/org/cses/flow/runtime/command/`.
- Extend runtime operations and repositories only where S2 requires it.

- [ ] Make WAIT return a manual Task definition.
- [ ] Persist Task, leave Activity running, mark Executor waiting, and allow the queue to empty.
- [ ] Make TaskService.complete create a task-completed Signal and call FlowEngine.handleSignal synchronously.
- [ ] Create a new FlowContext for resume, complete Task and Activity, activate Executor, and schedule outgoing-edge traversal.
- [ ] Make duplicate completion with the same idempotency key a no-op.
- [ ] Run the target test until S2 is GREEN.

### Task 5: Combination scenarios

**Files:**
- Modify: `server/src/test/java/org/cses/flow/runtime/FlowStartAndNodeProgressionTest.java`
- Modify production files only when a failing S3 or S4 assertion demonstrates a missing behavior.

- [ ] Run S3 and verify automatic nodes execute on both sides of WAIT.
- [ ] Run S4 and verify each WAIT creates a distinct Task and each complete stops at the next stable state.
- [ ] Confirm activity ordering and execution counts.
- [ ] Confirm each start and complete returns with an empty ExecutionQueue.

### Task 6: Package alignment and verification

**Files:**
- Move: `server/src/main/java/org/flow/server/Application.java` to `server/src/main/java/org/cses/flow/Application.java`
- Modify: `server/build.gradle`
- Create: `docs/standards/in-memory-workflow-core.md`

- [ ] Update the Micronaut main class to `org.cses.flow.Application`.
- [ ] Document in-memory repository, immutable deployed Flow, and test isolation conventions.
- [ ] Run `./gradlew :server:test --tests '*FlowStartAndNodeProgressionTest'`.
- [ ] Run `./gradlew test`.
- [ ] Run `./gradlew build`.
- [ ] Use Reviewer to map every PASS-S1 through PASS-S4 item to fresh evidence.

