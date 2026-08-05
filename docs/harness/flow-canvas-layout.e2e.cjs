const assert = require("node:assert/strict");
const { chromium } = require("playwright");

const demoUrl =
    process.env.FLOW_DEMO_URL
    || "http://127.0.0.1:3434/demo/index.html";
const chromeExecutable =
    process.env.FLOW_DEMO_CHROME
    || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const AUTO_TASK_TYPE =
    "org.cses.flow.extensions.tasks.AutomaticTask";
const PAUSE_TASK_TYPE =
    "org.cses.flow.extensions.flow.Pause";

const definition = {
    key: "并行测试1",
    description: "画布结构布局回归样例",
    inputs: [],
    outputs: [],
    tasks: [
        {
            key: "auto-task-1",
            type: AUTO_TASK_TYPE,
            inputs: [],
            outputs: [],
            route: "DIRECT",
            dependOn: [],
            tasks: [
                {
                    key: "approval-task-1",
                    type: PAUSE_TASK_TYPE,
                    inputs: [],
                    outputs: [
                        { key: "decision", type: "STRING" },
                        { key: "comment", type: "STRING" },
                    ],
                    route: "DIRECT",
                    dependOn: [],
                    tasks: [
                        {
                            key: "auto-task-2",
                            type: AUTO_TASK_TYPE,
                            inputs: [],
                            outputs: [],
                            route:
                                'outputs.decision == "APPROVED"',
                            dependOn: [],
                            tasks: [
                                {
                                    key: "auto-task-4",
                                    type: AUTO_TASK_TYPE,
                                    inputs: [],
                                    outputs: [],
                                    route: "DIRECT",
                                    dependOn: [],
                                    tasks: [],
                                },
                            ],
                        },
                        {
                            key: "auto-task-3",
                            type: AUTO_TASK_TYPE,
                            inputs: [],
                            outputs: [],
                            route:
                                'outputs.decision == "REJECTED"',
                            dependOn: [],
                            tasks: [
                                {
                                    key: "auto-task-5",
                                    type: AUTO_TASK_TYPE,
                                    inputs: [],
                                    outputs: [],
                                    route: "DIRECT",
                                    dependOn: [],
                                    tasks: [],
                                },
                            ],
                        },
                    ],
                },
                {
                    key: "auto-task-6",
                    type: AUTO_TASK_TYPE,
                    inputs: [],
                    outputs: [],
                    route: "DIRECT",
                    dependOn: [],
                    tasks: [],
                },
            ],
        },
    ],
};

const draft = {
    id: "layout-regression",
    raw: "layout regression fixture",
    lockVersion: 1,
    createdAt: 1,
    updatedAt: 1,
    createdBy: "layout-test",
    updatedBy: "layout-test",
    deployedFlow: null,
};

async function respondApi(route) {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    let payload;
    if (path === "/api/demo/session") {
        payload = {
            companyId: "layout-test",
            userId: "layout-test",
            displayName: "Layout Test",
        };
    } else if (path === "/api/demo/data-types") {
        payload = [];
    } else if (path === "/api/demo/flows") {
        payload = [draft];
    } else if (path === "/api/demo/flows/layout-regression") {
        payload = draft;
    } else if (path === "/api/demo/flows/preview") {
        payload = { definition };
    } else if (path === "/api/demo/executions") {
        payload = [];
    } else {
        return route.abort();
    }
    return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(payload),
    });
}

async function main() {
    const browser = await chromium.launch({
        headless: true,
        executablePath: chromeExecutable,
    });
    try {
        const page = await browser.newPage({
            viewport: { width: 1800, height: 1200 },
        });
        await page.route("**/api/demo/**", respondApi);
        await page.goto(demoUrl);
        await page
            .locator('.flow-node[data-path="0.0.1.0"]')
            .waitFor();
        await page
            .locator('.flow-node[data-path="0.0"]')
            .click();
        await page
            .locator(
                '[data-layout-path="0.0"] .node-quick-actions',
            )
            .waitFor();

        const positions = await page
            .locator(".flow-node[data-path]")
            .evaluateAll((nodes) =>
                Object.fromEntries(
                    nodes.map((node) => {
                        const rect = node.getBoundingClientRect();
                        return [
                            node.dataset.path,
                            {
                                key:
                                    node.querySelector("strong")
                                        ?.textContent,
                                x: Math.round(rect.x),
                                y: Math.round(rect.y),
                            },
                        ];
                    }),
                ),
            );
        const sameRow = (left, right) =>
            Math.abs(positions[left].y - positions[right].y) <= 2;

        assert.ok(
            sameRow("0", "0.1"),
            "auto-task-1 与 auto-task-6 应位于主干同一行",
        );
        assert.ok(
            !sameRow("0", "0.0"),
            "approval-task-1 应在主干下一行",
        );
        assert.ok(
            sameRow("0.0.0", "0.0.1"),
            "auto-task-2 与 auto-task-3 应位于同一 Route 层",
        );
        assert.ok(
            sameRow("0.0.0.0", "0.0.1.0"),
            "auto-task-4 与 auto-task-5 应位于同一后续层",
        );
        assert.ok(
            positions["0.1"].x > positions["0.0.1.0"].x,
            "auto-task-6 应回到主干，并位于完整中间结构之后",
        );
        assert.equal(
            await page.locator(
                '.connection-route[data-from="0.0"]'
                + '[data-to="0.0.0"]',
            ).count(),
            1,
            "approval-task-1 应直接分叉到 auto-task-2",
        );
        assert.equal(
            await page.locator(
                '.connection-route[data-from="0.0"]'
                + '[data-to="0.0.1"]',
            ).count(),
            1,
            "approval-task-1 应直接分叉到 auto-task-3",
        );
        assert.equal(
            await page.locator(
                '.flow-connection[data-from="0.0.0"]'
                + '[data-to="0.0.1"]',
            ).count(),
            0,
            "Route 同级节点之间不能绘制串行连线",
        );

        const addFlowTask = page.locator(
            '.canvas-structure-summary '
            + '[data-action="open-task-picker"]'
            + '[data-placement="flow"]',
        );
        assert.equal(
            await addFlowTask.count(),
            1,
            "画布必须提供明确写入 Flow.tasks 的添加入口",
        );
        await addFlowTask.click();
        await page.getByText(
            "新 Task 会直接写入 Flow.tasks",
            { exact: false },
        ).waitFor();
        await page.locator(
            '[data-modal-field="taskType"]',
        ).selectOption(AUTO_TASK_TYPE);
        await page.locator(
            '[data-action="confirm-create-task"]',
        ).click();
        await page.locator('.flow-node[data-path="1"]').waitFor();

        await page.locator('.flow-node[data-path="0"]').click();
        const addChildTask = page.locator(
            '[data-layout-path="0"] '
            + '[data-action="open-node-task-picker"]',
        );
        assert.match(
            (await addChildTask.textContent()) || "",
            /添加子 Task/,
            "节点操作必须明确写入当前 Task.tasks",
        );
        await addChildTask.click();
        await page.getByText(
            "新 Task 会写入当前节点的 tasks",
            { exact: false },
        ).waitFor();
        await page.locator(
            '[data-modal-field="taskType"]',
        ).selectOption(PAUSE_TASK_TYPE);
        await page.locator(
            '[data-action="confirm-create-task"]',
        ).click();
        await page.locator('.flow-node[data-path="0.2"]').waitFor();

        console.log(
            "Flow canvas layout and task-scope regression passed",
            JSON.stringify(positions),
        );
    } finally {
        await browser.close();
    }
}

main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
