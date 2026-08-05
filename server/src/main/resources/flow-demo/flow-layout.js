(function registerFlowCanvasLayout(root, factory) {
    const layout = factory();
    if (typeof module === "object" && module.exports) {
        module.exports = layout;
        return;
    }
    root.FlowCanvasLayout = layout;
})(typeof globalThis === "undefined" ? window : globalThis, () => {
    "use strict";

    const NODE_WIDTH = 184;
    const NODE_HEIGHT = 88;
    const COLUMN_GAP = 54;
    const COLUMN_STEP = NODE_WIDTH + COLUMN_GAP;
    const ROW_STEP = 148;
    const NODE_TOP = 14;
    const FIRST_NODE_X = 94;
    const TERMINAL_WIDTH = 54;
    const PARALLEL_TASK_TYPE =
        "org.cses.flow.extensions.flow.Parallel";

    function build(tasks) {
        const context = {
            nodes: [],
            edges: [],
            maxStage: -1,
        };
        const roots = Array.isArray(tasks) ? tasks : [];
        let stage = 0;
        let previousExits = [];
        let firstEntry = null;

        roots.forEach((task, index) => {
            const taskBlock = buildTaskBlock(
                task,
                String(index),
                [],
                "main",
            );
            placeBlock(context, taskBlock, stage, 0);
            if (firstEntry === null) {
                firstEntry = taskBlock.entry;
            }
            connectAll(
                previousExits,
                taskBlock.entry,
                "serial",
                context,
            );
            previousExits = taskBlock.exits;
            stage = taskBlock.maxStage + 1;
        });

        const nodes = context.nodes.map((node) => ({
            ...node,
            x: FIRST_NODE_X + node.stage * COLUMN_STEP,
            y: NODE_TOP + node.row * ROW_STEP,
        }));
        const nodesByPath = new Map(
            nodes.map((node) => [node.path, node]),
        );
        const rowCount =
            nodes.reduce(
                (maximum, node) => Math.max(maximum, node.row + 1),
                1,
            );
        const endX =
            nodes.reduce(
                (maximum, node) =>
                    Math.max(maximum, node.x + NODE_WIDTH),
                FIRST_NODE_X,
            )
            + COLUMN_GAP;
        const width = endX + TERMINAL_WIDTH + 116;
        const height =
            NODE_TOP + rowCount * ROW_STEP + NODE_HEIGHT + 28;

        return {
            nodes,
            nodesByPath,
            edges: context.edges,
            rows: buildRows(nodes, rowCount),
            width,
            height,
            nodeWidth: NODE_WIDTH,
            nodeHeight: NODE_HEIGHT,
            start: {
                x: 0,
                y: NODE_TOP + (NODE_HEIGHT - 25) / 2,
                width: TERMINAL_WIDTH,
                targetPath: firstEntry?.path || null,
            },
            end: {
                x: endX,
                y: NODE_TOP + (NODE_HEIGHT - 25) / 2,
                width: TERMINAL_WIDTH,
                sourcePaths: previousExits.map((node) => node.path),
            },
        };
    }

    function buildTaskBlock(
        task,
        path,
        branchPath,
        laneKind,
    ) {
        const node = {
            task,
            path,
            stage: 0,
            row: 0,
            branchPath,
            laneKind,
            slot: 0,
        };
        const block = {
            nodes: [node],
            edges: [],
            entry: node,
            exits: [node],
            maxStage: 0,
        };

        const children = Array.isArray(task.tasks)
            ? task.tasks
            : [];
        if (children.length === 0) {
            return block;
        }

        const branchKind = taskBranchKind(task, children);
        if (branchKind !== null) {
            const branchResults = children.map((child, index) => {
                const childBlock = buildTaskBlock(
                    child,
                    `${path}.${index}`,
                    [...branchPath, index],
                    branchKind,
                );
                placeBlock(
                    block,
                    childBlock,
                    index + 1,
                    1,
                );
                connectAll(
                    [node],
                    childBlock.entry,
                    branchKind,
                    block,
                );
                return childBlock;
            });
            block.exits = branchResults.flatMap(
                (result) => result.exits,
            );
            return block;
        }

        let childStage = 1;
        let previousExits = [node];
        let earlierChildExpanded = false;

        children.forEach((child, index) => {
            const returnsToParentRow =
                index > 0 && earlierChildExpanded;
            const childRow = returnsToParentRow ? 0 : 1;
            const childLaneKind = returnsToParentRow
                ? laneKind
                : "subflow";
            const childBlock = buildTaskBlock(
                child,
                `${path}.${index}`,
                branchPath,
                childLaneKind,
            );
            const childExpanded =
                childBlock.maxStage
                > childBlock.entry.stage;
            placeBlock(
                block,
                childBlock,
                childStage,
                childRow,
            );
            connectAll(
                previousExits,
                childBlock.entry,
                index === 0 ? "subflow" : "serial",
                block,
            );
            earlierChildExpanded =
                earlierChildExpanded
                || childExpanded;
            previousExits = childBlock.exits;
            childStage = childBlock.maxStage + 1;
        });

        block.exits = previousExits;
        return block;
    }

    function placeBlock(target, source, stage, row) {
        let targetStage = stage;
        while (
            blockCollides(
                target.nodes,
                source.nodes,
                targetStage,
                row,
            )
        ) {
            targetStage += 1;
        }
        translateBlock(source, targetStage, row);
        target.nodes.push(...source.nodes);
        target.edges.push(...source.edges);
        target.maxStage = Math.max(
            target.maxStage,
            source.maxStage,
        );
    }

    function blockCollides(
        targetNodes,
        sourceNodes,
        stageOffset,
        rowOffset,
    ) {
        const occupied = new Set(
            targetNodes.map(
                (node) => `${node.stage}:${node.row}`,
            ),
        );
        return sourceNodes.some((node) =>
            occupied.has(
                `${node.stage + stageOffset}:`
                + `${node.row + rowOffset}`,
            ),
        );
    }

    function translateBlock(block, stageOffset, rowOffset) {
        block.nodes.forEach((node) => {
            node.stage += stageOffset;
            node.row += rowOffset;
        });
        block.maxStage += stageOffset;
    }

    function taskBranchKind(task, children) {
        if (task.type === PARALLEL_TASK_TYPE) {
            return "parallel";
        }
        if (children.some((child) => isConditionalRoute(child.route))) {
            return "route";
        }
        return null;
    }

    function isConditionalRoute(route) {
        const expression = String(route || "").trim();
        return expression !== ""
            && expression.toUpperCase() !== "DIRECT";
    }

    function connectAll(sources, target, kind, context) {
        if (!target) {
            return;
        }
        sources.forEach((source) => {
            context.edges.push({
                from: source.path,
                to: target.path,
                kind,
            });
        });
    }

    function buildRows(nodes, rowCount) {
        return Array.from({ length: rowCount }, (_, row) => {
            const rowNodes = nodes.filter((node) => node.row === row);
            const kinds = new Set(
                rowNodes.map((node) => node.laneKind),
            );
            let kind = "subflow";
            if (row === 0) {
                kind = "main";
            } else if (kinds.has("parallel")) {
                kind = "parallel";
            } else if (kinds.has("route")) {
                kind = "route";
            }
            return {
                row,
                kind,
                taskCount: rowNodes.length,
                y: NODE_TOP + row * ROW_STEP,
            };
        });
    }

    return Object.freeze({
        build,
        constants: Object.freeze({
            NODE_WIDTH,
            NODE_HEIGHT,
            COLUMN_GAP,
            COLUMN_STEP,
            ROW_STEP,
            NODE_TOP,
            FIRST_NODE_X,
            TERMINAL_WIDTH,
        }),
    });
});
