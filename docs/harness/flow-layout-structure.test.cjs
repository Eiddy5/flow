const assert = require("node:assert/strict");
const layoutEngine = require(
    "../../server/src/main/resources/flow-demo/flow-layout.js",
);

function task(key, tasks = [], options = {}) {
    return {
        key,
        type: options.type || "AUTO",
        route: options.route || "DIRECT",
        tasks,
    };
}

const definition = [
    task("auto-task-1"),
    task(
        "approval-task-1",
        [
            task(
                "auto-task-2",
                [
                    task(
                        "parallel-task-1",
                        [
                            task("auto-task-5"),
                            task("auto-task-6"),
                        ],
                        { type: "PARALLEL" },
                    ),
                ],
                {
                    route:
                        'outputs.decision == "APPROVED"',
                },
            ),
            task(
                "auto-task-3",
                [task("auto-task-7")],
                {
                    route:
                        'outputs.decision == "REJECTED"',
                },
            ),
        ],
        { type: "PAUSE" },
    ),
    task("auto-task-4"),
];

const layout = layoutEngine.build(definition);
const nodes = Object.fromEntries(
    layout.nodes.map((node) => [node.task.key, node]),
);
const step = layoutEngine.constants.COLUMN_STEP;

assert.equal(
    nodes["parallel-task-1"].x - nodes["auto-task-2"].x,
    step,
    "子节点应直接跟随 auto-task-2，不能等待同层其他节点",
);
assert.equal(
    nodes["auto-task-7"].x - nodes["auto-task-3"].x,
    step,
    "子节点应直接跟随 auto-task-3",
);
assert.equal(
    nodes["auto-task-5"].x - nodes["parallel-task-1"].x,
    step,
    "第一个并行分支应直接跟随 PARALLEL 节点",
);
assert.equal(
    nodes["auto-task-6"].x - nodes["auto-task-5"].x,
    step,
    "同级并行分支应紧邻展示",
);
assert.ok(
    nodes["auto-task-4"].x
        > Math.max(
            nodes["auto-task-5"].x,
            nodes["auto-task-6"].x,
            nodes["auto-task-7"].x,
        ),
    "顶层 next Task 必须放在完整子树之后",
);

console.log(
    "Flow recursive subtree layout passed",
    JSON.stringify(
        Object.fromEntries(
            Object.entries(nodes).map(([key, node]) => [
                key,
                {
                    row: node.row,
                    stage: node.stage,
                    x: node.x,
                },
            ]),
        ),
    ),
);
