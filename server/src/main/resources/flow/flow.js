(() => {
    "use strict";

    const API = "/api";
    const PLUGIN_API = "/api/plugins";
    const TASK_TYPES = Object.freeze({
        LOG: "org.cses.flow.extensions.log.Log",
        PAUSE: "org.cses.flow.extensions.flow.Pause",
        PARALLEL: "org.cses.flow.extensions.flow.Parallel",
    });
    const COMMON_TASK_FIELDS = new Set([
        "id",
        "type",
        "key",
        "inputs",
        "outputs",
        "route",
        "dependOn",
        "tasks",
    ]);
    const STATE_LABELS = {
        CREATED: "已创建",
        RUNNING: "运行中",
        PAUSED: "已暂停",
        RESTARTED: "恢复中",
        SUCCESS: "已成功",
        WARNING: "警告完成",
        FAILED: "已失败",
        KILLING: "终止中",
        KILLED: "已终止",
    };
    const ICONS = {
        flow:
            '<rect x="3" y="3" width="7" height="6" rx="2"/><rect x="14" y="15" width="7" height="6" rx="2"/><path d="M10 6h4a3 3 0 0 1 3 3v6"/>',
        activity:
            '<path d="M3 12h4l2.2-6 4.1 12 2.1-6H21"/>',
        plus: '<path d="M12 5v14M5 12h14"/>',
        save:
            '<path d="M5 3h12l3 3v15H4V3Z"/><path d="M8 3v6h8V3M8 21v-7h8v7"/>',
        rocket:
            '<path d="M14 5c3-3 6-3 6-3s0 3-3 6l-5 5-5-5Z"/><path d="m9 11-4 1-2 3 6 1M13 15l-1 4 3 2 2-6"/>',
        play: '<path d="m8 5 11 7-11 7Z"/>',
        code:
            '<path d="m8 9-4 3 4 3M16 9l4 3-4 3M14 5l-4 14"/>',
        grid:
            '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
        trash:
            '<path d="M4 7h16M9 7V4h6v3M7 7l1 14h8l1-14M10 11v6M14 11v6"/>',
        refresh:
            '<path d="M20 7h-7a6 6 0 1 0 6 6"/><path d="m16 4 4 3-4 3"/>',
        chevron:
            '<path d="m6 9 6 6 6-6"/>',
        stop: '<rect x="5" y="5" width="14" height="14" rx="2"/>',
        check: '<path d="m5 12 4 4L19 6"/>',
        arrowUp: '<path d="m12 19V5m0 0-6 6m6-6 6 6"/>',
        arrowDown: '<path d="M12 5v14m0 0 6-6m-6 6-6-6"/>',
        child:
            '<path d="M5 4v7a4 4 0 0 0 4 4h10"/><path d="m15 11 4 4-4 4"/>',
        sequence:
            '<path d="M5 7h11M5 17h11"/><path d="m13 4 3 3-3 3m0 4 3 3-3 3"/>',
        branch:
            '<path d="M5 5v5a3 3 0 0 0 3 3h10M8 13a3 3 0 0 0-3 3v3"/><path d="m15 9 4 4-4 4"/>',
        route:
            '<path d="m12 3 7 7-7 7-7-7Z"/><path d="M12 17v4M19 10h2M3 10h2"/>',
        close: '<path d="m6 6 12 12M18 6 6 18"/>',
        help:
            '<circle cx="12" cy="12" r="9"/><path d="M9.8 9a2.4 2.4 0 1 1 3.6 2.1c-.9.5-1.4 1-1.4 2M12 17h.01"/>',
        settings:
            '<circle cx="12" cy="12" r="3"/><path d="M19 12a7 7 0 0 0-.1-1l2-1.5-2-3.4-2.4 1a8 8 0 0 0-1.7-1L14.5 3h-5l-.4 3.1a8 8 0 0 0-1.7 1l-2.4-1-2 3.4L5.1 11a7 7 0 0 0 0 2L3 14.5l2 3.4 2.4-1a8 8 0 0 0 1.7 1l.4 3.1h5l.4-3.1a8 8 0 0 0 1.7-1l2.4 1 2-3.4-2.1-1.5a7 7 0 0 0 .1-1Z"/>',
    };

    const state = {
        session: null,
        dataTypes: [],
        drafts: [],
        executions: [],
        current: null,
        definition: null,
        raw: "",
        dirty: false,
        tab: "canvas",
        selectedPath: null,
        selectedExecutionId: null,
        replayTaskRunId: null,
        yamlError: null,
        busy: false,
        runCollapsed: false,
        modal: null,
        search: "",
        pluginPackages: [],
        pluginDetails: new Map(),
        pluginDetailRequests: new Map(),
    };

    let previewTimer = null;
    let previewSequence = 0;
    let replaySequence = 0;
    let pollTimer = null;
    let toastTimer = null;

    function icon(name) {
        return `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${ICONS[name] || ICONS.flow}</svg>`;
    }

    function escapeHtml(value) {
        return String(value ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#039;");
    }

    function escapeAttribute(value) {
        return escapeHtml(value);
    }

    async function requestJson(url, options = {}) {
        const request = {
            method: options.method || "GET",
            headers: {
                Accept: "application/json",
            },
        };
        if (Object.prototype.hasOwnProperty.call(options, "body")) {
            request.headers["Content-Type"] = "application/json";
            request.body = JSON.stringify(options.body);
        }
        const response = await fetch(url, request);
        if (!response.ok) {
            let message = `${response.status} ${response.statusText}`;
            try {
                const payload = await response.json();
                message = payload.message || payload.error || message;
            } catch {
                const text = await response.text();
                if (text) {
                    message = text;
                }
            }
            const error = new Error(message);
            error.status = response.status;
            throw error;
        }
        if (response.status === 204) {
            return null;
        }
        return response.json();
    }

    async function api(path, options = {}) {
        return requestJson(`${API}${path}`, options);
    }

    async function pluginApi(path, options = {}) {
        return requestJson(`${PLUGIN_API}${path}`, options);
    }

    async function loadPluginDetails(type) {
        if (!type || state.pluginDetails.has(type)) {
            return state.pluginDetails.get(type) || null;
        }
        if (state.pluginDetailRequests.has(type)) {
            return state.pluginDetailRequests.get(type);
        }
        const request = pluginApi(`/${encodeURIComponent(type)}`)
            .then((details) => {
                state.pluginDetails.set(type, details);
                return details;
            })
            .finally(() => {
                state.pluginDetailRequests.delete(type);
            });
        state.pluginDetailRequests.set(type, request);
        return request;
    }

    async function initialize() {
        bindGlobalEvents();
        try {
            const [
                session,
                dataTypes,
                pluginPackages,
                drafts,
                executions,
            ] =
                await Promise.all([
                api("/session"),
                api("/data-types"),
                pluginApi(""),
                api("/flows"),
                api("/executions"),
            ]);
            state.session = session;
            state.dataTypes = dataTypes;
            state.pluginPackages = pluginPackages;
            state.drafts = drafts;
            state.executions = executions;
            if (drafts.length > 0) {
                await openDraft(drafts[0].flowKey);
            } else {
                createLocalDraft(false);
            }
            render();
            schedulePoll();
        } catch (error) {
            renderBootError(error);
        }
    }

    function renderBootError(error) {
        document.querySelector("#app").innerHTML = `
            <div class="boot-screen">
                <div class="boot-mark">!</div>
                <strong>无法连接 Flow API</strong>
                <span>${escapeHtml(error.message)}</span>
                <button class="btn btn-primary" data-action="reload-page">
                    ${icon("refresh")} 重新连接
                </button>
            </div>
        `;
    }

    function render() {
        const previousCanvas = document.querySelector(".canvas-wrap");
        const previousViewport = previousCanvas
            ? {
                flowKey: previousCanvas.dataset.flowKey || "",
                left: previousCanvas.scrollLeft,
                top: previousCanvas.scrollTop,
            }
            : null;
        document.querySelector("#app").innerHTML = `
            <main class="shell">
                ${renderRail()}
                <section class="workspace">
                    ${renderTopbar()}
                    <div class="content">
                        ${renderSidebar()}
                        ${renderEditor()}
                        ${renderInspector()}
                    </div>
                </section>
            </main>
        `;
        document.querySelector("#modal-root").innerHTML =
            renderModal();
        requestAnimationFrame(() => {
            const canvas = document.querySelector(".canvas-wrap");
            if (!canvas) {
                return;
            }
            const flowKey = canvas.dataset.flowKey || "";
            if (
                previousViewport
                && previousViewport.flowKey === flowKey
            ) {
                canvas.scrollLeft = previousViewport.left;
                canvas.scrollTop = previousViewport.top;
                return;
            }
            canvas.scrollLeft = 0;
        });
    }

    function renderRail() {
        const initials = String(state.session?.userName || "Flow")
            .split(/\s+/)
            .map((part) => part[0])
            .join("")
            .slice(0, 2)
            .toUpperCase();
        return `
            <aside class="rail">
                <div class="brand-mark" title="Flow Studio">F</div>
                <button class="rail-button active" title="流程编排">
                    ${icon("flow")}
                </button>
                <button class="rail-button" title="运行事实">
                    ${icon("activity")}
                </button>
                <div class="rail-spacer"></div>
                <button class="rail-button" title="Flow 说明">
                    ${icon("help")}
                </button>
                <button class="rail-button" title="设置">
                    ${icon("settings")}
                </button>
                <div
                    class="avatar"
                    title="${escapeAttribute(state.session?.userName || "")}"
                >${escapeHtml(initials)}</div>
            </aside>
        `;
    }

    function renderTopbar() {
        const current = state.current;
        const title = current
            ? currentDefinitionKey()
            : "未选择流程";
        const deployed = current?.deployedFlow;
        const badge = deployed
            ? `<span class="badge badge-live">已部署 R${deployed.reversion}</span>`
            : '<span class="badge badge-draft">仅草稿</span>';
        const savedText = state.dirty
            ? "存在未保存修改"
            : current?.flowKey
                ? "草稿已保存"
                : "尚未创建草稿";
        return `
            <header class="topbar">
                <div class="title-wrap">
                    <div class="crumbs">
                        <span>Flow Studio</span>
                        <span>/</span>
                        <span>${escapeHtml(state.session?.companyId || "")}</span>
                    </div>
                    <div class="flow-title">
                        <h1>${escapeHtml(title)}</h1>
                        ${badge}
                    </div>
                </div>
                <div class="save-state ${state.dirty ? "dirty" : ""}">
                    ${state.dirty ? "●" : icon("check")}
                    <span>${escapeHtml(savedText)}</span>
                </div>
                <button
                    class="btn btn-ghost"
                    data-action="new-flow"
                    ${state.busy ? "disabled" : ""}
                >
                    ${icon("plus")} 新建
                </button>
                <button
                    class="btn btn-ghost"
                    data-action="open-task-picker"
                    data-placement="flow"
                    title="添加到 Flow.tasks，与现有顶层 Task 同级"
                    ${state.busy || !state.definition ? "disabled" : ""}
                >
                    ${icon("plus")} 添加流程 Task
                </button>
                <button
                    class="btn btn-ghost"
                    data-action="save-flow"
                    ${state.busy || !current ? "disabled" : ""}
                >
                    ${icon("save")} 保存草稿
                </button>
                <button
                    class="btn btn-primary"
                    data-action="deploy-flow"
                    ${state.busy || !current?.flowKey ? "disabled" : ""}
                >
                    ${icon("rocket")} 发布
                </button>
                <button
                    class="btn btn-run"
                    data-action="start-flow"
                    ${state.busy || !deployed ? "disabled" : ""}
                    title="${deployed ? `启动已部署的 R${deployed.reversion}` : "请先发布流程"}"
                >
                    ${icon("play")}
                    ${deployed ? `启动 R${deployed.reversion}` : "启动流程"}
                </button>
            </header>
        `;
    }

    function renderSidebar() {
        const drafts = state.drafts.filter((draft) =>
            draftName(draft)
                .toLowerCase()
                .includes(state.search.toLowerCase()),
        );
        return `
            <aside class="sidebar">
                <section class="sidebar-section">
                    <div class="section-head">
                        <h2>流程草稿</h2>
                        <span class="count">${state.drafts.length}</span>
                    </div>
                    <input
                        class="search"
                        data-role="draft-search"
                        value="${escapeAttribute(state.search)}"
                        placeholder="按 key 搜索"
                    >
                    <div class="draft-list">
                        ${drafts.length
                            ? drafts.map(renderDraftItem).join("")
                            : '<div class="inspector-note">没有匹配的草稿</div>'}
                    </div>
                </section>
            </aside>
        `;
    }

    function renderDraftItem(draft) {
        const active = state.current?.flowKey === draft.flowKey;
        const deployed = draft.deployedFlow;
        return `
            <button
                class="draft-item ${active ? "active" : ""}"
                data-action="select-draft"
                data-key="${escapeAttribute(draft.flowKey)}"
                data-search="${escapeAttribute(draftName(draft).toLowerCase())}"
            >
                <span class="draft-name">
                    <strong>${escapeHtml(draftName(draft))}</strong>
                    <span class="badge ${deployed ? "badge-live" : "badge-draft"}">
                        ${deployed ? `R${deployed.reversion}` : "草稿"}
                    </span>
                </span>
                <span class="draft-meta">
                    <span>${escapeHtml(formatTime(draft.updatedAt))}</span>
                </span>
            </button>
        `;
    }

    function renderEditor() {
        return `
            <section class="editor">
                <div class="editor-tabs">
                    <button
                        class="tab ${state.tab === "canvas" ? "active" : ""}"
                        data-action="switch-tab"
                        data-tab="canvas"
                    >
                        ${icon("grid")} 画布
                    </button>
                    <button
                        class="tab ${state.tab === "yaml" ? "active" : ""}"
                        data-action="switch-tab"
                        data-tab="yaml"
                    >
                        ${icon("code")} YAML
                    </button>
                    <span class="editor-help">
                        YAML 由服务端同一个 YamlParser 校验
                    </span>
                </div>
                ${state.tab === "yaml"
                    ? renderYamlEditor()
                    : renderCanvas()}
                ${renderRunPanel()}
            </section>
        `;
    }

    function renderYamlEditor() {
        return `
            <div class="yaml-panel">
                <div class="yaml-state ${state.yamlError ? "error" : ""}">
                    <span>
                        ${state.yamlError
                            ? escapeHtml(state.yamlError)
                            : "格式可解析；发布时还会执行 Flow 领域校验"}
                    </span>
                    <span class="mono">${state.raw.split("\n").length} 行</span>
                </div>
                <textarea
                    id="yaml-editor"
                    class="yaml-editor"
                    spellcheck="false"
                    aria-label="Flow YAML"
                >${escapeHtml(state.raw)}</textarea>
            </div>
        `;
    }

    function renderCanvas() {
        if (!state.definition) {
            return `
                <div class="canvas-wrap">
                    <div class="empty-canvas">
                        <strong>当前 YAML 无法生成画布</strong>
                        <span>切换到 YAML 标签查看服务端解析错误</span>
                    </div>
                </div>
            `;
        }
        const tasks = state.definition.tasks || [];
        const nodes = [];
        flattenTasks(tasks, null, 0, nodes);
        if (nodes.length === 0) {
            return `
                <div class="canvas-wrap" data-action="select-flow">
                    <div class="empty-canvas">
                        <span class="empty-step">新流程</span>
                        <strong>从第一个 Task 开始编排</strong>
                        <span>
                            先添加顺序任务，再从任一节点扩展子任务、并行或
                            Route 条件分支。
                        </span>
                        <button
                            class="btn btn-primary"
                            data-action="open-task-picker"
                            data-placement="flow"
                        >
                            ${icon("plus")} 添加第一个流程 Task
                        </button>
                        <span class="empty-guide">
                            1. 添加流程 Task　2. 配置字段　3. 保存、发布并真实运行
                        </span>
                    </div>
                </div>
            `;
        }
        const layout = window.FlowCanvasLayout.build(tasks);
        return `
            <div
                class="canvas-wrap"
                data-action="select-flow"
                data-flow-key="${escapeAttribute(state.current?.flowKey || "local")}"
            >
                <div class="canvas-stage horizontal-canvas">
                    ${renderCanvasStructureSummary(nodes)}
                    ${renderFlowStructureMap(layout)}
                </div>
            </div>
        `;
    }

    function renderCanvasStructureSummary(nodes) {
        const parallelCount = nodes.filter(
            (node) => node.task.type === TASK_TYPES.PARALLEL,
        ).length;
        const subflowCount = nodes.filter(
            (node) =>
                node.task.type !== TASK_TYPES.PARALLEL
                && (node.task.tasks || []).length > 0,
        ).length;
        const routeCount = nodes.filter(
            (node) =>
                parseRouteExpression(node.task.route)?.kind === "condition",
        ).length;
        return `
            <div class="canvas-structure-summary">
                <span class="summary-copy">
                    <strong>横向流程视图</strong>
                    <small>
                        主流程从左到右；中间子结构向下展开，完成后回到父层
                    </small>
                </span>
                <span class="structure-count">
                    ${nodes.length} 个 Task
                </span>
                <span class="structure-legend serial">
                    <i></i>串行
                </span>
                <span class="structure-legend parallel">
                    <i></i>并行流程 ${parallelCount}
                </span>
                <span class="structure-legend subflow">
                    <i></i>子流程 ${subflowCount}
                </span>
                <span class="structure-legend route">
                    <i></i>条件 ${routeCount}
                </span>
                <button
                    class="btn btn-primary canvas-flow-task-action"
                    data-action="open-task-picker"
                    data-placement="flow"
                    title="直接写入 Flow.tasks"
                >
                    ${icon("plus")} 添加流程 Task
                </button>
            </div>
        `;
    }

    function renderFlowStructureMap(layout) {
        return `
            <div
                class="flow-structure-board"
                style="--layout-height:${layout.height}px"
            >
                <div class="flow-row-labels">
                    ${layout.rows.map(renderFlowRowLabel).join("")}
                </div>
                <div
                    class="flow-structure-map"
                    style="
                        width:${layout.width}px;
                        height:${layout.height}px;
                    "
                >
                    ${layout.rows
                        .map(
                            (row) => `
                                <span
                                    class="flow-row-guide row-${escapeAttribute(row.kind)}"
                                    style="top:${row.y + layout.nodeHeight / 2}px"
                                ></span>
                            `,
                        )
                        .join("")}
                    ${renderFlowConnections(layout)}
                    <span
                        class="lane-terminal flow-map-terminal start"
                        style="
                            left:${layout.start.x}px;
                            top:${layout.start.y}px;
                        "
                    >
                        开始
                    </span>
                    ${layout.nodes
                        .map(
                            (node) => `
                                <div
                                    class="flow-layout-node"
                                    style="
                                        left:${node.x}px;
                                        top:${node.y}px;
                                    "
                                    data-layout-row="${node.row}"
                                    data-layout-stage="${node.stage}"
                                    data-layout-path="${escapeAttribute(node.path)}"
                                >
                                    ${renderNode(node)}
                                </div>
                            `,
                        )
                        .join("")}
                    <span
                        class="lane-terminal flow-map-terminal end"
                        style="
                            left:${layout.end.x}px;
                            top:${layout.end.y}px;
                        "
                    >
                        结束
                    </span>
                    <button
                        class="canvas-add-step flow-map-add-step"
                        style="
                            left:${layout.end.x + layout.end.width + 12}px;
                            top:${layout.end.y - 1}px;
                        "
                        data-action="open-task-picker"
                        data-placement="flow"
                    >
                        ${icon("plus")} 添加流程 Task
                    </button>
                </div>
            </div>
        `;
    }

    function renderFlowRowLabel(row) {
        const presentation = flowRowPresentation(row);
        return `
            <div
                class="flow-row-label row-${escapeAttribute(row.kind)}"
                style="top:${row.y + 13}px"
            >
                <span class="flow-row-icon">
                    ${icon(presentation.icon)}
                </span>
                <span>
                    <strong>${escapeHtml(presentation.title)}</strong>
                    <small>${row.taskCount} 个 Task</small>
                </span>
            </div>
        `;
    }

    function flowRowPresentation(row) {
        if (row.kind === "main") {
            return {
                icon: "sequence",
                title: "MAIN · 主流程",
            };
        }
        if (row.kind === "parallel") {
            return {
                icon: "branch",
                title: "PARALLEL · 并行",
            };
        }
        if (row.kind === "route") {
            return {
                icon: "route",
                title: "ROUTE · 条件选择",
            };
        }
        return {
            icon: "child",
            title: `SUBFLOW · 第 ${row.row} 层`,
        };
    }

    function renderFlowConnections(layout) {
        const paths = [];
        layout.edges.forEach((edge) => {
            const source = layout.nodesByPath.get(edge.from);
            const target = layout.nodesByPath.get(edge.to);
            if (!source || !target) {
                return;
            }
            if (
                ["route", "parallel"].includes(edge.kind)
                && target.y > source.y
            ) {
                paths.push(
                    renderFlowBranchConnector(
                        source.x + layout.nodeWidth / 2,
                        source.y + layout.nodeHeight,
                        target.x + layout.nodeWidth / 2,
                        target.y,
                        edge.kind,
                        edge.from,
                        edge.to,
                    ),
                );
                return;
            }
            paths.push(
                renderFlowConnector(
                    source.x + layout.nodeWidth,
                    source.y + layout.nodeHeight / 2,
                    target.x,
                    target.y + layout.nodeHeight / 2,
                    edge.kind,
                    edge.from,
                    edge.to,
                ),
            );
        });

        const firstNode = layout.nodesByPath.get(
            layout.start.targetPath,
        );
        if (firstNode) {
            paths.push(
                renderFlowConnector(
                    layout.start.x + layout.start.width,
                    layout.start.y + 12.5,
                    firstNode.x,
                    firstNode.y + layout.nodeHeight / 2,
                    "serial",
                    "start",
                    firstNode.path,
                ),
            );
        }
        layout.end.sourcePaths.forEach((sourcePath) => {
            const source = layout.nodesByPath.get(sourcePath);
            if (!source) {
                return;
            }
            paths.push(
                renderFlowConnector(
                    source.x + layout.nodeWidth,
                    source.y + layout.nodeHeight / 2,
                    layout.end.x,
                    layout.end.y + 12.5,
                    "serial",
                    source.path,
                    "end",
                ),
            );
        });

        return `
            <svg
                class="flow-connection-layer"
                viewBox="0 0 ${layout.width} ${layout.height}"
                width="${layout.width}"
                height="${layout.height}"
                aria-hidden="true"
            >
                ${paths.join("")}
            </svg>
        `;
    }

    function renderFlowBranchConnector(
        sourceX,
        sourceY,
        targetX,
        targetY,
        kind,
        from,
        to,
    ) {
        const verticalSpace = Math.max(targetY - sourceY, 0);
        const bendY =
            sourceY
            + Math.min(
                30,
                Math.max(verticalSpace / 2, 12),
            );
        const path = [
            `M ${sourceX} ${sourceY}`,
            `V ${bendY}`,
            `H ${targetX}`,
            `V ${targetY}`,
        ].join(" ");
        const arrow = [
            `M ${targetX - 4} ${targetY - 7}`,
            `L ${targetX} ${targetY}`,
            `L ${targetX + 4} ${targetY - 7}`,
        ].join(" ");
        return `
            <g
                class="flow-connection connection-${escapeAttribute(kind)}"
                data-from="${escapeAttribute(from)}"
                data-to="${escapeAttribute(to)}"
            >
                <path class="flow-edge" d="${path}"></path>
                <path class="flow-arrow" d="${arrow}"></path>
            </g>
        `;
    }

    function renderFlowConnector(
        sourceX,
        sourceY,
        targetX,
        targetY,
        kind,
        from,
        to,
    ) {
        const path = flowConnectorPath(
            sourceX,
            sourceY,
            targetX,
            targetY,
        );
        const arrow = [
            `M ${targetX - 7} ${targetY - 4}`,
            `L ${targetX} ${targetY}`,
            `L ${targetX - 7} ${targetY + 4}`,
        ].join(" ");
        return `
            <g
                class="flow-connection connection-${escapeAttribute(kind)}"
                data-from="${escapeAttribute(from)}"
                data-to="${escapeAttribute(to)}"
            >
                <path class="flow-edge" d="${path}"></path>
                <path class="flow-arrow" d="${arrow}"></path>
            </g>
        `;
    }

    function flowConnectorPath(
        sourceX,
        sourceY,
        targetX,
        targetY,
    ) {
        if (Math.abs(sourceY - targetY) < 1) {
            return `M ${sourceX} ${sourceY} H ${targetX}`;
        }
        const horizontalSpace = Math.max(targetX - sourceX, 0);
        const bendX =
            sourceX
            + Math.min(
                28,
                Math.max(horizontalSpace / 2, 12),
            );
        return [
            `M ${sourceX} ${sourceY}`,
            `H ${bendX}`,
            `V ${targetY}`,
            `H ${targetX}`,
        ].join(" ");
    }

    function renderNode(node) {
        const run = taskRunForKey(node.task.key);
        const runState = run?.state?.toLowerCase() || "";
        const replaying = run?.id === state.replayTaskRunId;
        const routeMissed = isRouteMissed(node);
        const selected = state.selectedPath === node.path;
        const active =
            !routeMissed
            && (
                replaying
                || ["CREATED", "RUNNING", "PAUSED"].includes(run?.state)
            );
        const completed =
            !routeMissed
            && ["SUCCESS", "WARNING"].includes(run?.state);
        const semanticBadges = renderNodeSemanticBadges(node);
        return `
            <div class="flow-node-wrap ${selected ? "has-actions" : ""}">
                <button
                    class="flow-node
                        ${selected ? "selected" : ""}
                        ${runState ? `state-${runState}` : ""}
                        ${active ? "is-active" : ""}
                        ${completed ? "is-completed" : ""}
                        ${routeMissed ? "state-route-missed" : ""}
                        ${replaying ? "replaying" : ""}"
                    data-action="select-task"
                    data-path="${escapeAttribute(node.path)}"
                >
                    <span class="node-top">
                        <span class="node-type ${escapeAttribute(taskTypeClass(node.task.type))}">
                            ${taskTypeIcon(node.task.type)}
                        </span>
                        <span class="node-copy">
                            <strong>${escapeHtml(node.task.key || "未命名任务")}</strong>
                            <span>
                                ${escapeHtml(taskTypeCode(node.task.type))}
                            </span>
                        </span>
                        <span class="node-menu">${icon("chevron")}</span>
                    </span>
                    ${semanticBadges
                        ? `<span class="node-semantic-badges">
                            ${semanticBadges}
                        </span>`
                        : ""}
                    <span class="node-foot">
                        <span class="node-run-state">
                            <span class="status-dot ${routeMissed ? "route-missed" : runState}"></span>
                            ${escapeHtml(
                                routeMissed
                                    ? "条件未命中"
                                    : run
                                        ? stateLabel(run.state)
                                        : "未运行",
                            )}
                        </span>
                    </span>
                </button>
                ${selected
                    ? `<div class="node-quick-actions">
                        <button
                            data-action="open-node-task-picker"
                            data-task-path="${escapeAttribute(node.path)}"
                            data-placement="after"
                            title="插入当前 Task 之后，保持同一层级"
                        >
                            后续 Task
                        </button>
                        <button
                            class="node-child-action"
                            data-action="open-node-task-picker"
                            data-task-path="${escapeAttribute(node.path)}"
                            data-placement="child"
                            title="写入当前节点的 tasks，成为直接子 Task"
                        >
                            子 Task
                        </button>
                        <button
                            class="node-quick-icon"
                            data-action="open-branch-builder"
                            data-branch-mode="parallel"
                            title="添加并行流程"
                            aria-label="添加并行流程"
                        >${icon("branch")}</button>
                        <button
                            class="node-quick-icon"
                            data-action="open-branch-builder"
                            data-branch-mode="route"
                            title="添加 Route 条件"
                            aria-label="添加 Route 条件"
                        >${icon("route")}</button>
                    </div>`
                    : ""}
            </div>
        `;
    }

    function renderNodeSemanticBadges(node) {
        const badges = [];
        const parsed = parseRouteExpression(node.task.route);
        const children = Array.isArray(node.task.tasks)
            ? node.task.tasks
            : [];
        const conditionalChildren = children.filter(
            (task) =>
                parseRouteExpression(task.route)?.kind === "condition",
        );
        if (node.task.type === TASK_TYPES.PARALLEL) {
            badges.push(
                '<span class="node-semantic-badge parallel">PARALLEL · 并行</span>',
            );
        } else if (conditionalChildren.length > 0) {
            badges.push(`
                <span class="node-semantic-badge route">
                    ROUTE · ${conditionalChildren.length} 个条件
                </span>
            `);
        } else if (children.length > 0) {
            badges.push(
                '<span class="node-semantic-badge subflow">SUBFLOW · 子流程</span>',
            );
        }
        if (parsed?.kind === "condition") {
            badges.push(`
                <span class="node-semantic-badge route">
                    ${escapeHtml(parsed.outputKey)}
                    = ${escapeHtml(parsed.expectedValue)}
                </span>
            `);
        }
        return badges.join("");
    }

    function renderRunPanel() {
        const executions = selectedFlowExecutions();
        const execution = selectedExecution();
        return `
            <section class="run-panel ${state.runCollapsed ? "collapsed" : ""}">
                <div class="run-head">
                    <h2>真实运行事实</h2>
                    <span class="count">
                        ${executions.length} 次运行 · 非前端模拟
                    </span>
                    <button
                        class="btn btn-icon btn-ghost"
                        data-action="toggle-run-panel"
                        title="${state.runCollapsed ? "展开" : "收起"}"
                    >
                        ${icon("chevron")}
                    </button>
                </div>
                ${state.runCollapsed
                    ? ""
                    : `<div class="run-body">
                        <div class="execution-list">
                            ${executions.length
                                ? executions.map(renderExecutionItem).join("")
                                : '<div class="run-empty">尚无运行记录</div>'}
                        </div>
                        <div class="run-facts">
                            ${execution
                                ? renderExecutionFacts(execution)
                                : '<div class="run-empty">发布并启动后，这里显示持久化的 TaskRun</div>'}
                        </div>
                    </div>`}
            </section>
        `;
    }

    function renderExecutionItem(execution) {
        return `
            <button
                class="execution-item ${execution.id === selectedExecution()?.id ? "active" : ""}"
                data-action="select-execution"
                data-id="${escapeAttribute(execution.id)}"
            >
                <strong>${escapeHtml(execution.id)}</strong>
                <span>
                    R${execution.flowVersion} ·
                    ${escapeHtml(stateLabel(execution.state))} ·
                    ${escapeHtml(formatTime(execution.createdAt))}
                </span>
            </button>
        `;
    }

    function renderExecutionFacts(execution) {
        const active = ["CREATED", "RUNNING", "PAUSED"].includes(
            execution.state,
        );
        return `
            <div class="execution-summary">
                <span class="badge badge-${execution.state.toLowerCase()}">
                    ${escapeHtml(stateLabel(execution.state))}
                </span>
                <code>${escapeHtml(execution.id)}</code>
                ${active
                    ? `<button
                        class="btn btn-danger"
                        data-action="cancel-execution"
                        data-id="${escapeAttribute(execution.id)}"
                    >
                        ${icon("stop")} 取消
                    </button>`
                    : ""}
            </div>
            ${execution.taskRuns.length
                ? execution.taskRuns.map((run) =>
                    renderTaskRunFact(execution, run),
                ).join("")
                : '<div class="inspector-note">Execution 尚未产生 TaskRun</div>'}
        `;
    }

    function renderTaskRunFact(execution, run) {
        const key = deployedTaskKey(run.taskId) || run.taskId;
        const detail = run.error
            ? run.error
            : Object.keys(run.outputs || {}).length
                ? JSON.stringify(run.outputs)
                : `${run.history.length} 条状态记录`;
        return `
            <div class="fact-row">
                <span class="fact-key" title="${escapeAttribute(run.taskId)}">
                    ${escapeHtml(key)}${run.iteration
                        ? ` #${escapeHtml(run.iteration)}`
                        : ""}
                </span>
                <span class="badge badge-${run.state.toLowerCase()}">
                    ${escapeHtml(stateLabel(run.state))}
                </span>
                <span class="fact-detail" title="${escapeAttribute(detail)}">
                    ${escapeHtml(detail)}
                </span>
                ${run.state === "PAUSED"
                    ? `<button
                        class="btn btn-primary"
                        data-action="open-resume"
                        data-execution-id="${escapeAttribute(execution.id)}"
                        data-task-run-id="${escapeAttribute(run.id)}"
                    >提交结果并恢复</button>`
                    : ""}
            </div>
        `;
    }

    function renderInspector() {
        if (!state.definition) {
            return `
                <aside class="inspector">
                    <div class="inspector-head"><h2>属性</h2></div>
                    <div class="inspector-note">
                        YAML 解析成功后可以在这里编辑流程和节点属性。
                    </div>
                </aside>
            `;
        }
        const task = selectedTask();
        return task
            ? renderTaskInspector(task)
            : renderFlowInspector();
    }

    function renderFlowInspector() {
        return `
            <aside class="inspector">
                <div class="inspector-head">
                    <h2>流程属性</h2>
                    ${state.current?.flowKey
                        ? `<button
                            class="btn btn-icon btn-danger"
                            data-action="delete-flow"
                            title="删除流程"
                        >${icon("trash")}</button>`
                        : ""}
                </div>
                ${fieldInput(
                    "流程 key",
                    "flow-key",
                    state.definition.key || "",
                    "发布后不能跨版本修改 key",
                )}
                ${fieldTextarea(
                    "描述",
                    "flow-description",
                    state.definition.description || "",
                    "说明这个流程解决的业务问题",
                )}
                ${renderDataListEditor(
                    "流程输入",
                    "flow-inputs",
                    state.definition.inputs,
                    "每个 Input 独立定义 key 和 type",
                    "添加输入",
                )}
                ${renderDataListEditor(
                    "流程输出",
                    "flow-outputs",
                    state.definition.outputs,
                    "每个 Output 独立定义；当前 Core 不伪造运行输入",
                    "添加输出",
                )}
                <div class="inspector-divider"></div>
                <div class="inspector-note">
                    技术 ID 由 Flow Core 创建。这里编辑的是业务定义；保存草稿不要求
                    定义完整，发布时会执行完整领域校验。
                </div>
            </aside>
        `;
    }

    function renderTaskInspector(task) {
        return `
            <aside class="inspector">
                <div class="inspector-head">
                    <h2>节点属性</h2>
                    <div class="inspector-actions">
                        <button
                            class="btn btn-icon btn-ghost"
                            data-action="move-task"
                            data-direction="-1"
                            title="上移"
                        >${icon("arrowUp")}</button>
                        <button
                            class="btn btn-icon btn-ghost"
                            data-action="move-task"
                            data-direction="1"
                            title="下移"
                        >${icon("arrowDown")}</button>
                        <button
                            class="btn btn-icon btn-danger"
                            data-action="delete-task"
                            title="删除节点"
                        >${icon("trash")}</button>
                    </div>
                </div>
                ${fieldInput(
                    "任务 key",
                    "task-key",
                    task.key || "",
                    "同一 Flow 的递归任务树内必须唯一",
                )}
                <div class="field">
                    <label>任务类型</label>
                    <select data-field="task-type">
                        ${renderTaskTypeOptions(task.type)}
                    </select>
                </div>
                ${renderPluginDefinitionFields(task)}
                ${renderRouteEditor(task)}
                ${renderDependencyEditor(task)}
                ${renderDataListEditor(
                    "Task 输入",
                    "task-inputs",
                    task.inputs,
                    "每个 Input 使用独立字段定义",
                    "添加输入",
                )}
                ${task.type === TASK_TYPES.PAUSE
                    ? renderDataListEditor(
                        "恢复输入",
                        "task-resume",
                        task.resume,
                        "外部 Resume 表单按 Input 的类型、必填、默认值和约束校验",
                        "添加恢复输入",
                    )
                    : renderDataListEditor(
                        "Task 输出",
                        "task-outputs",
                        task.outputs,
                        "每个 Output 使用独立字段定义",
                        "添加输出",
                    )}
                ${task.type === TASK_TYPES.PAUSE
                    ? `<div class="approval-template">
                        <div>
                            <strong>审批表单预设</strong>
                            <span>运行时根据 Resume Input 显示同意、拒绝按钮和审批意见。</span>
                        </div>
                        <button
                            class="btn btn-ghost"
                            data-action="apply-approval-template"
                        >
                            使用预设
                        </button>
                    </div>`
                    : ""}
                ${task.type === TASK_TYPES.PARALLEL
                    ? `<div class="inspector-note parallel-note">
                        PARALLEL 是结构节点：完成自身后同时启动所有可运行的直接
                        子任务，并等待所有分支结束后，外层串行流程才会继续。
                    </div>`
                    : ""}
                <div class="inspector-divider"></div>
                <div class="inspector-note">
                    节点技术 ID 在发布时由领域模型生成，并在后续 Reversion 中按
                    task key 复用。画布只编辑定义字段。
                </div>
            </aside>
        `;
    }

    function renderRouteEditor(task) {
        const parent = parentTaskForPath(state.selectedPath);
        if (!parent) {
            return `
                <section class="route-editor">
                    <div class="route-editor-head">
                        <label>Route 路由</label>
                        <span class="route-chip direct">DIRECT</span>
                    </div>
                    <div class="route-readonly">
                        顶层任务属于顺序阶段，领域规则要求 Route 固定为 DIRECT。
                    </div>
                </section>
            `;
        }
        const parsed = parseRouteExpression(task.route);
        const conditional = parsed?.kind === "condition";
        const outputs = (Array.isArray(parent.outputs)
            ? parent.outputs
            : []).filter(
            (output) =>
                String(output.type || "STRING").toUpperCase() === "STRING",
        );
        const selectedOutput = parsed?.outputKey
            || firstRouteOutput(parent)?.key
            || "";
        const routeValues = routeValueSelection(
            parent,
            task,
            selectedOutput,
            parsed?.expectedValue || "",
        );
        return `
            <section class="route-editor">
                <div class="route-editor-head">
                    <label>Route 路由</label>
                    <span class="route-chip ${conditional ? "condition" : "direct"}">
                        ${conditional ? "条件命中" : "DIRECT"}
                    </span>
                </div>
                <div class="field">
                    <label>进入方式</label>
                    <select data-field="task-route-mode">
                        <option value="DIRECT" ${conditional ? "" : "selected"}>
                            DIRECT · 父任务完成后直接进入
                        </option>
                        <option value="CONDITION" ${conditional ? "selected" : ""}>
                            CONDITION · 根据父任务输出进入
                        </option>
                    </select>
                </div>
                ${conditional
                    ? outputs.length
                        ? `<div class="field-row route-fields">
                            <div class="field">
                                <label>父任务输出</label>
                                <select data-field="task-route-output">
                                    ${outputs.map((output) => `
                                        <option
                                            value="${escapeAttribute(output.key || "")}"
                                            ${output.key === selectedOutput ? "selected" : ""}
                                        >
                                            outputs.${escapeHtml(output.key || "未命名")} ·
                                            ${escapeHtml(output.type || "STRING")}
                                        </option>
                                    `).join("")}
                                </select>
                            </div>
                            <div class="field">
                                <label>匹配值</label>
                                ${routeValues.controlled
                                    ? `<select data-field="task-route-value">
                                        ${routeValues.values.map((value) => `
                                            <option
                                                value="${escapeAttribute(value)}"
                                                ${value === parsed?.expectedValue ? "selected" : ""}
                                            >${escapeHtml(value)}</option>
                                        `).join("")}
                                    </select>`
                                    : `<input
                                        data-field="task-route-value"
                                        value="${escapeAttribute(parsed?.expectedValue || "")}"
                                        placeholder="当前 Output 未声明可选值"
                                    >`}
                            </div>
                        </div>
                        <code class="route-preview">${escapeHtml(
                            routeExpression(
                                selectedOutput,
                                parsed?.expectedValue || "",
                            ),
                        )}</code>
                        <small>
                            输出参数只来自直连父任务。审批结果及已有分支值使用下拉
                            选择；普通 STRING 没有可选值元数据时才允许自定义。
                        </small>`
                        : `<div class="route-empty">
                            <span>父任务还没有声明可用于路由的输出。</span>
                            <button
                                class="btn btn-ghost"
                                data-action="add-parent-route-output"
                            >${icon("plus")} 添加 decision 输出</button>
                        </div>`
                    : `<small>
                        ${parent.type === TASK_TYPES.PARALLEL
                            ? "当前父任务是 PARALLEL，所有可运行的 DIRECT 子任务会作为同一并行批次进入。"
                            : "当前父任务不是 PARALLEL，多个 DIRECT 子任务会按定义顺序逐个进入。"}
                    </small>`}
                ${!parsed
                    ? `<div class="route-warning">
                        当前表达式无法由页面识别：${escapeHtml(task.route)}。
                        请选择一种进入方式修正，或在 YAML 中编辑。
                    </div>`
                    : ""}
            </section>
        `;
    }

    function renderDependencyEditor(task) {
        const nodes = [];
        flattenTasks(state.definition?.tasks, null, 0, nodes);
        const candidates = nodes.filter((node) =>
            node.path !== state.selectedPath
            && !node.path.startsWith(`${state.selectedPath}.`),
        );
        const selected = new Set(task.dependOn || []);
        const known = new Set(candidates.map((node) => node.task.key));
        const missing = [...selected].filter((key) => !known.has(key));
        return `
            <section class="dependency-editor">
                <div class="data-list-head">
                    <label>
                        前置依赖
                        <span>${selected.size}</span>
                    </label>
                    <span class="structure-truth">全部完成后进入</span>
                </div>
                <div class="dependency-list">
                    ${candidates.length
                        ? candidates.map((node) => `
                            <label class="dependency-option">
                                <input
                                    type="checkbox"
                                    data-dependency-key="${escapeAttribute(node.task.key || "")}"
                                    ${selected.has(node.task.key) ? "checked" : ""}
                                    ${node.task.key ? "" : "disabled"}
                                >
                                <span>
                                    <strong>${escapeHtml(node.task.key || "未命名任务")}</strong>
                                    <small>${escapeHtml(taskStructureLabel(node))}</small>
                                </span>
                            </label>
                        `).join("")
                        : `<div class="data-list-empty">
                            暂无可选任务；并行向导可自动创建汇合依赖。
                        </div>`}
                    ${missing.map((key) => `
                        <div class="dependency-missing">
                            未找到依赖：${escapeHtml(key)}
                        </div>
                    `).join("")}
                </div>
                <small>
                    可多选；发布时服务端会检查任务存在、自依赖和依赖环。
                </small>
            </section>
        `;
    }

    function fieldInput(label, field, value, help) {
        return `
            <div class="field">
                <label>${escapeHtml(label)}</label>
                <input
                    data-field="${escapeAttribute(field)}"
                    value="${escapeAttribute(value)}"
                >
                <small>${escapeHtml(help)}</small>
            </div>
        `;
    }

    function fieldTextarea(label, field, value, help) {
        return `
            <div class="field">
                <label>${escapeHtml(label)}</label>
                <textarea data-field="${escapeAttribute(field)}">${escapeHtml(value)}</textarea>
                <small>${escapeHtml(help)}</small>
            </div>
        `;
    }

    function renderDataListEditor(
        label,
        listName,
        items,
        help,
        addLabel,
    ) {
        const values = Array.isArray(items) ? items : [];
        return `
            <section class="data-list-editor">
                <div class="data-list-head">
                    <label>
                        ${escapeHtml(label)}
                        <span>${values.length}</span>
                    </label>
                    <button
                        class="data-list-add"
                        data-action="add-data-item"
                        data-list-name="${escapeAttribute(listName)}"
                    >
                        ${icon("plus")} ${escapeHtml(addLabel)}
                    </button>
                </div>
                <div class="data-list-rows">
                    ${values.length
                        ? values.map((item, index) =>
                            renderDataListRow(
                                label,
                                listName,
                                item,
                                index,
                            ),
                        ).join("")
                        : `<div class="data-list-empty">
                            暂无定义，点击“${escapeHtml(addLabel)}”
                        </div>`}
                </div>
                <small>${escapeHtml(help)}</small>
            </section>
        `;
    }

    function renderDataListRow(label, listName, item, index) {
        if (
            listName.endsWith("-inputs")
            || listName === "task-resume"
        ) {
            return renderInputDefinitionRow(
                label,
                listName,
                item,
                index,
            );
        }
        return renderOutputDefinitionRow(label, listName, item, index);
    }

    function renderOutputDefinitionRow(label, listName, item, index) {
        const row = index + 1;
        return `
            <div class="data-list-row">
                <span class="data-list-index">${row}</span>
                <input
                    value="${escapeAttribute(item?.key || "")}"
                    placeholder="字段 key"
                    aria-label="${escapeAttribute(label)} ${row} key"
                    data-list-name="${escapeAttribute(listName)}"
                    data-list-index="${index}"
                    data-list-field="key"
                >
                <select
                    aria-label="${escapeAttribute(label)} ${row} type"
                    data-list-name="${escapeAttribute(listName)}"
                    data-list-index="${index}"
                    data-list-field="type"
                >${renderDataTypeOptions(item?.type)}</select>
                <button
                    class="btn btn-icon btn-danger data-list-remove"
                    data-action="remove-data-item"
                    data-list-name="${escapeAttribute(listName)}"
                    data-list-index="${index}"
                    aria-label="删除${escapeAttribute(label)} ${row}"
                    title="删除这一项"
                >${icon("trash")}</button>
            </div>
        `;
    }

    function renderInputDefinitionRow(label, listName, item, index) {
        const row = index + 1;
        const type = String(item?.type || "STRING").toUpperCase();
        const metadata = inputTypeMetadata(type);
        const fields = (metadata?.fields || []).filter(
            (field) => field.key !== "key",
        );
        return `
            <div class="data-list-row input-definition">
                <div class="input-definition-head">
                    <span class="data-list-index">${row}</span>
                    <input
                        value="${escapeAttribute(item?.key || "")}"
                        placeholder="字段 key"
                        aria-label="${escapeAttribute(label)} ${row} key"
                        data-list-name="${escapeAttribute(listName)}"
                        data-list-index="${index}"
                        data-list-field="key"
                    >
                    <select
                        aria-label="${escapeAttribute(label)} ${row} type"
                        data-list-name="${escapeAttribute(listName)}"
                        data-list-index="${index}"
                        data-list-field="type"
                    >${renderDataTypeOptions(type)}</select>
                    <button
                        class="btn btn-icon btn-danger data-list-remove"
                        data-action="remove-data-item"
                        data-list-name="${escapeAttribute(listName)}"
                        data-list-index="${index}"
                        aria-label="删除${escapeAttribute(label)} ${row}"
                        title="删除这一项"
                    >${icon("trash")}</button>
                </div>
                <div class="input-definition-fields">
                    ${fields.map((field) =>
                        renderInputDefinitionField(
                            listName,
                            index,
                            item,
                            type,
                            field,
                        ),
                    ).join("")}
                </div>
                <div class="input-definition-type">
                    <strong>${escapeHtml(metadata?.inputClass || type)}</strong>
                    <span>Java ${escapeHtml(metadata?.valueClass || type)}</span>
                </div>
            </div>
        `;
    }

    function renderInputDefinitionField(
        listName,
        index,
        item,
        type,
        field,
    ) {
        const attributes = `
            data-list-name="${escapeAttribute(listName)}"
            data-list-index="${index}"
            data-list-field="${escapeAttribute(field.key)}"
        `;
        const label = `
            <span>
                ${escapeHtml(field.displayName)}
                ${field.required ? "<b>*</b>" : ""}
            </span>
        `;
        let control;
        if (field.key === "required") {
            control = `
                <label class="input-required-toggle">
                    <input
                        type="checkbox"
                        ${item?.required === true ? "checked" : ""}
                        ${attributes}
                    >
                    <span>${item?.required === true ? "是" : "否"}</span>
                </label>
            `;
        } else if (field.control === "boolean") {
            const value = item?.[field.key];
            control = `
                <select ${attributes}>
                    <option value="" ${value == null ? "selected" : ""}>
                        不设置
                    </option>
                    <option value="true" ${value === true ? "selected" : ""}>
                        true
                    </option>
                    <option value="false" ${value === false ? "selected" : ""}>
                        false
                    </option>
                </select>
            `;
        } else {
            const isNumber = field.control === "number";
            const value = item?.[field.key];
            control = `
                <input
                    type="${isNumber ? "number" : "text"}"
                    ${isNumber ? `step="${isIntegralDataType(type) ? "1" : "any"}"` : ""}
                    ${type === "CHARACTER" && field.key === "defaultValue"
                        ? 'maxlength="1"'
                        : ""}
                    value="${escapeAttribute(value ?? "")}"
                    placeholder="${field.required ? "必填" : "不设置"}"
                    ${attributes}
                >
            `;
        }
        return `
            <label
                class="input-definition-field"
                title="${escapeAttribute(field.description || "")}"
            >
                ${label}
                ${control}
            </label>
        `;
    }

    function renderDataTypeOptions(selectedType) {
        const selected = String(selectedType || "STRING").toUpperCase();
        const catalog = state.dataTypes.length
            ? state.dataTypes
            : [{ code: "STRING", valueClass: "String" }];
        return catalog.map((type) => `
            <option
                value="${escapeAttribute(type.code)}"
                ${type.code === selected ? "selected" : ""}
            >
                ${escapeHtml(type.code)} · ${escapeHtml(type.valueClass)}
            </option>
        `).join("");
    }

    function inputTypeMetadata(type) {
        const normalized = String(type || "STRING").toUpperCase();
        return state.dataTypes.find((item) => item.code === normalized)
            || null;
    }

    function isIntegralDataType(type) {
        return [
            "BYTE",
            "SHORT",
            "INTEGER",
            "LONG",
        ].includes(String(type || "").toUpperCase());
    }

    function renderModal() {
        if (!state.modal) {
            return "";
        }
        if (state.modal.kind === "flow-creator") {
            return renderFlowCreatorModal();
        }
        if (state.modal.kind === "task-picker") {
            return renderTaskPickerModal();
        }
        if (state.modal.kind === "branch-builder") {
            return renderBranchBuilderModal();
        }
        if (state.modal.kind === "start") {
            return renderStartModal();
        }
        if (state.modal.kind === "approval") {
            return renderApprovalModal();
        }
        return "";
    }

    function renderFlowCreatorModal() {
        const modal = state.modal;
        return `
            <div class="modal-backdrop" data-action="close-modal">
                <section
                    class="modal flow-creator-modal"
                    role="dialog"
                    aria-modal="true"
                    aria-label="新建流程"
                >
                    <div class="modal-title">
                        <div>
                            <span class="eyebrow">新建流程</span>
                            <h3>填写流程基础信息</h3>
                        </div>
                        <button
                            class="btn btn-icon btn-ghost"
                            data-action="close-modal"
                            aria-label="关闭"
                        >${icon("close")}</button>
                    </div>
                    <p>
                        先创建空流程草稿。创建完成后，再从画布添加第一个流程 Task。
                    </p>
                    <div class="flow-creator-form">
                        <div class="field">
                            <label>流程 key</label>
                            <input
                                data-modal-field="flowKey"
                                value="${escapeAttribute(modal.flowKey)}"
                                placeholder="例如 employee-onboarding"
                                autocomplete="off"
                            >
                            <small>
                                用于识别流程；发布后不能跨 Reversion 修改。
                            </small>
                        </div>
                        <div class="field">
                            <label>流程描述</label>
                            <textarea
                                data-modal-field="description"
                                placeholder="说明这个流程解决的业务问题"
                            >${escapeHtml(modal.description)}</textarea>
                        </div>
                    </div>
                    <div class="modal-actions">
                        <button class="btn btn-ghost" data-action="close-modal">
                            取消
                        </button>
                        <button
                            class="btn btn-primary"
                            data-action="create-flow"
                            ${state.busy ? "disabled" : ""}
                        >
                            ${icon("plus")} 创建流程
                        </button>
                    </div>
                </section>
            </div>
        `;
    }

    function renderTaskPickerModal() {
        const placement = state.modal.placement;
        const plugins = availableTaskPlugins();
        const selectedPlugin = taskPluginMetadata(
            state.modal.taskType,
        ) || plugins[0] || null;
        const child = placement === "child";
        const after = placement === "after";
        const parent = after
            ? parentTaskForPath(state.selectedPath)
            : null;
        const parallelSibling =
            after && parent?.type === TASK_TYPES.PARALLEL;
        const title = child
            ? "添加子 Task"
            : after
                ? "添加后续 Task"
                : "添加流程 Task";
        return `
            <div class="modal-backdrop" data-action="close-modal">
                <section
                    class="modal task-picker-modal"
                    role="dialog"
                    aria-modal="true"
                    aria-label="${title}"
                >
                    <div class="modal-title">
                        <div>
                            <span class="eyebrow">
                                ${title}
                            </span>
                            <h3>选择 Task 类型</h3>
                        </div>
                        <button
                            class="btn btn-icon btn-ghost"
                            data-action="close-modal"
                            aria-label="关闭"
                        >${icon("close")}</button>
                    </div>
                    <p>
                        ${child
                            ? "新 Task 会写入当前节点的 tasks，成为它的直接子 Task。"
                            : after
                                ? parallelSibling
                                    ? "当前 Task 直属于 PARALLEL；同级后续会成为新的并行分支。若要在当前分支完成后串行继续，请添加子 Task。"
                                    : "新 Task 会插入当前 Task 之后并保持同一层级；当前 Task 的子流程会先执行完成。"
                                : "新 Task 会直接写入 Flow.tasks，与现有顶层 Task 同级，并追加到流程末尾。"}
                        添加后可在右侧设置 key、依赖、Route、输入和输出。
                    </p>
                    <div class="task-picker-form">
                        <div class="field">
                            <label>已注册 Task</label>
                            <select
                                data-modal-field="taskType"
                                ${plugins.length ? "" : "disabled"}
                            >
                                ${renderRegisteredTaskOptions(
                                    selectedPlugin?.type,
                                )}
                            </select>
                            <small>
                                来自插件注册表，共 ${plugins.length} 种可用 Task
                            </small>
                        </div>
                        ${selectedPlugin
                            ? renderSelectedTaskPlugin(selectedPlugin)
                            : `<div class="task-picker-empty">
                                当前没有已注册的 Task，无法继续编排。
                            </div>`}
                    </div>
                    <div class="modal-actions">
                        <button
                            class="btn btn-ghost"
                            data-action="close-modal"
                        >
                            取消
                        </button>
                        <button
                            class="btn btn-primary"
                            data-action="confirm-create-task"
                            ${selectedPlugin ? "" : "disabled"}
                        >
                            ${icon("plus")} 添加 Task
                        </button>
                    </div>
                </section>
            </div>
        `;
    }

    function renderBranchBuilderModal() {
        const modal = state.modal;
        const parallel = modal.mode === "parallel";
        const parent = taskAtPath(modal.parentPath);
        return `
            <div class="modal-backdrop" data-action="close-modal">
                <section
                    class="modal branch-builder-modal"
                    role="dialog"
                    aria-modal="true"
                    aria-label="${parallel ? "创建并行流程" : "创建 Route 选择流程"}"
                >
                    <div class="modal-title">
                        <div>
                            <span class="eyebrow">
                                ${parallel ? "显式并行" : "条件路由"}
                            </span>
                            <h3>
                                ${parallel ? "创建并行节点与两个分支" : "按父任务输出选择分支"}
                            </h3>
                        </div>
                        <button
                            class="btn btn-icon btn-ghost"
                            data-action="close-modal"
                            aria-label="关闭"
                        >${icon("close")}</button>
                    </div>
                    <p>
                        父任务：<strong>${escapeHtml(parent?.key || modal.parentKey)}</strong>。
                        ${parallel
                            ? "向导会先创建一个 PARALLEL 子节点，再把两个分支放入该节点；普通同级子任务仍按顺序执行。"
                            : "所有命中条件的子任务都会按定义顺序进入，未命中分支不会创建 TaskRun。"}
                    </p>
                    ${parallel
                        ? renderParallelBuilderFields(modal)
                        : renderRouteBuilderFields(modal, parent)}
                    <div class="modal-actions">
                        <button class="btn btn-ghost" data-action="close-modal">
                            取消
                        </button>
                        <button
                            class="btn btn-primary"
                            data-action="create-branch-structure"
                        >
                            ${icon(parallel ? "branch" : "route")}
                            ${parallel ? "创建并行流程" : "创建 Route 分支"}
                        </button>
                    </div>
                </section>
            </div>
        `;
    }

    function renderParallelBuilderFields(modal) {
        return `
            <div class="branch-form">
                <div class="field">
                    <label>并行节点 key</label>
                    <input
                        data-modal-field="parallelKey"
                        value="${escapeAttribute(modal.parallelKey)}"
                        placeholder="parallel-task"
                    >
                    <small>
                        该 PARALLEL 节点是唯一会并行启动直接子任务的结构边界。
                    </small>
                </div>
                ${renderBranchRow(
                    "分支 A",
                    "branchAKey",
                    modal.branchAKey,
                    "branchAType",
                    modal.branchAType,
                )}
                ${renderBranchRow(
                    "分支 B",
                    "branchBKey",
                    modal.branchBKey,
                    "branchBType",
                    modal.branchBType,
                )}
                <label class="builder-check">
                    <input
                        type="checkbox"
                        data-modal-field="createJoin"
                        ${modal.createJoin ? "checked" : ""}
                    >
                    <span>
                        <strong>创建汇合后的后续任务</strong>
                    <small>PARALLEL 会先等待两个分支完成，再按顺序执行该 LOG 任务。</small>
                    </span>
                </label>
                ${modal.createJoin
                    ? `<div class="field">
                        <label>汇合任务 key</label>
                        <input
                            data-modal-field="joinKey"
                            value="${escapeAttribute(modal.joinKey)}"
                            placeholder="parallel-join"
                        >
                    </div>`
                    : ""}
                <div class="builder-note">
                    PARALLEL 表达流程语义上的并行；当前 Worker 仍逐个派发候选任务，
                    但多个 PAUSE 分支可以同时保持 PAUSED，不承诺同一线程物理并发。
                </div>
            </div>
        `;
    }

    function renderRouteBuilderFields(modal, parent) {
        const routeOutputs = (parent?.outputs || []).filter(
            isStringOutput,
        );
        const existingOutput = routeOutputs.find(
            (output) => output.key === modal.outputKey,
        );
        const output = existingOutput
            || { key: modal.outputKey, type: "STRING" };
        const controlledValues =
            approvalDecisionOutput([output]) === output
                ? Object.values(approvalDecisionValues(output))
                : [];
        return `
            <div class="branch-form">
                <div class="field">
                    <label>直连父任务输出</label>
                    ${routeOutputs.length
                        ? `<select data-modal-field="outputKey">
                            ${routeOutputs.map((candidate) => `
                                <option
                                    value="${escapeAttribute(candidate.key || "")}"
                                    ${candidate.key === modal.outputKey ? "selected" : ""}
                                >
                                    outputs.${escapeHtml(candidate.key || "未命名")}
                                    · ${escapeHtml(candidate.type || "STRING")}
                                </option>
                            `).join("")}
                        </select>`
                        : `<input
                            value="outputs.${escapeAttribute(modal.outputKey)}"
                            readonly
                        >`}
                    <small>
                        ${existingOutput
                            ? "参数来自当前节点声明的 outputs"
                            : "当前节点没有 STRING 输出，请先声明实际可产生的输出"}
                    </small>
                </div>
                ${renderRouteBranchRow(
                    "命中值 A",
                    "branchAKey",
                    modal.branchAKey,
                    "branchAType",
                    modal.branchAType,
                    "branchAValue",
                    modal.branchAValue,
                    controlledValues,
                )}
                ${renderRouteBranchRow(
                    "命中值 B",
                    "branchBKey",
                    modal.branchBKey,
                    "branchBType",
                    modal.branchBType,
                    "branchBValue",
                    modal.branchBValue,
                    controlledValues,
                )}
                <div class="builder-note">
                    比较规则为精确字符串匹配并区分大小写；两个条件如果同时命中，
                    两个分支都会进入，不是强制互斥的 if/else。
                </div>
            </div>
        `;
    }

    function renderBranchRow(
        label,
        keyField,
        keyValue,
        typeField,
        typeValue,
    ) {
        return `
            <div class="branch-row">
                <span class="branch-row-label">${escapeHtml(label)}</span>
                <input
                    data-modal-field="${escapeAttribute(keyField)}"
                    value="${escapeAttribute(keyValue)}"
                    placeholder="任务 key"
                    aria-label="${escapeAttribute(label)}任务 key"
                >
                ${renderTaskTypeSelect(typeField, typeValue, label)}
            </div>
        `;
    }

    function renderRouteBranchRow(
        label,
        keyField,
        keyValue,
        typeField,
        typeValue,
        valueField,
        expectedValue,
        valueOptions,
    ) {
        return `
            <div class="route-branch-row">
                <span class="branch-row-label">${escapeHtml(label)}</span>
                <input
                    data-modal-field="${escapeAttribute(keyField)}"
                    value="${escapeAttribute(keyValue)}"
                    placeholder="任务 key"
                    aria-label="${escapeAttribute(label)}任务 key"
                >
                ${renderTaskTypeSelect(typeField, typeValue, label)}
                ${valueOptions.length
                    ? `<select
                        data-modal-field="${escapeAttribute(valueField)}"
                        aria-label="${escapeAttribute(label)}匹配值"
                    >
                        ${valueOptions.map((value) => `
                            <option
                                value="${escapeAttribute(value)}"
                                ${value === expectedValue ? "selected" : ""}
                            >${escapeHtml(value)}</option>
                        `).join("")}
                    </select>`
                    : `<input
                        data-modal-field="${escapeAttribute(valueField)}"
                        value="${escapeAttribute(expectedValue)}"
                        placeholder="匹配值"
                        aria-label="${escapeAttribute(label)}匹配值"
                    >`}
            </div>
        `;
    }

    function renderTaskTypeSelect(field, value, label) {
        return `
            <select
                data-modal-field="${escapeAttribute(field)}"
                aria-label="${escapeAttribute(label)}任务类型"
            >
                ${renderTaskTypeOptions(value)}
            </select>
        `;
    }

    function renderStartModal() {
        const modal = state.modal;
        const inputs = Array.isArray(modal.inputs) ? modal.inputs : [];
        return `
            <div class="modal-backdrop" data-action="close-modal">
                <section
                    class="modal approval-modal"
                    role="dialog"
                    aria-modal="true"
                    aria-label="启动 Flow"
                >
                    <div class="modal-title">
                        <div>
                            <span class="eyebrow">启动 Execution</span>
                            <h3>填写运行输入</h3>
                        </div>
                        <button
                            class="btn btn-icon btn-ghost"
                            data-action="close-modal"
                            aria-label="关闭"
                        >${icon("close")}</button>
                    </div>
                    <p>
                        输入会按已发布的 Flow Reversion 做类型规范化，随后随
                        Execution 启动命令进入持久化 Queue。
                    </p>
                    <div id="start-input-form" class="approval-form">
                        ${inputs.length
                            ? inputs.map(renderStartInputField).join("")
                            : `<div class="inspector-note">
                                当前 Flow 没有声明运行输入，确认后直接启动。
                            </div>`}
                    </div>
                    <div class="modal-actions approval-actions">
                        <button class="btn btn-ghost" data-action="close-modal">
                            取消
                        </button>
                        <button
                            class="btn btn-primary"
                            data-action="submit-start"
                            ${state.busy ? "disabled" : ""}
                        >
                            ${icon("play")} 启动 Execution
                        </button>
                    </div>
                </section>
            </div>
        `;
    }

    function renderStartInputField(input, index) {
        const key = String(input?.key || `input-${index + 1}`);
        const type = String(input?.type || "STRING").toUpperCase();
        const value = input?.defaultValue ?? "";
        const required = input?.required === true;
        const label = input?.displayName || key;
        const attributes = `
            data-start-key="${escapeAttribute(key)}"
            data-start-type="${escapeAttribute(type)}"
        `;
        let control;
        if (type === "BOOLEAN") {
            control = `
                <select ${attributes}>
                    <option value="" ${value === "" ? "selected" : ""}>
                        不提供
                    </option>
                    <option value="true" ${value === true ? "selected" : ""}>
                        true
                    </option>
                    <option value="false" ${value === false ? "selected" : ""}>
                        false
                    </option>
                </select>
            `;
        } else if (
            [
                "BYTE",
                "SHORT",
                "INTEGER",
                "LONG",
                "FLOAT",
                "DOUBLE",
            ].includes(type)
        ) {
            const constraints = input?.constraints || {};
            control = `
                <input
                    type="number"
                    step="${["BYTE", "SHORT", "INTEGER", "LONG"].includes(type) ? "1" : "any"}"
                    ${constraints.min !== undefined ? `min="${escapeAttribute(constraints.min)}"` : ""}
                    ${constraints.max !== undefined ? `max="${escapeAttribute(constraints.max)}"` : ""}
                    value="${escapeAttribute(value)}"
                    placeholder="${required ? "必填" : "不提供"}"
                    ${attributes}
                >
            `;
        } else {
            control = `
                <input
                    type="${type === "CHARACTER" ? "text" : "text"}"
                    ${type === "CHARACTER" ? 'maxlength="1"' : ""}
                    value="${escapeAttribute(value)}"
                    placeholder="${required ? "必填" : "不提供"}"
                    ${attributes}
                >
            `;
        }
        return `
            <div class="approval-field">
                <label for="start-input-${index}">
                    ${escapeHtml(label)}${required ? " *" : ""}
                </label>
                ${control}
                <small>${escapeHtml(key)} · ${escapeHtml(type)}</small>
            </div>
        `;
    }

    function renderApprovalModal() {
        const modal = state.modal;
        const decision = approvalDecisionOutput(modal.inputs);
        const fields = modal.inputs.filter(
            (output) => output.key !== decision?.key,
        );
        const values = decision ? approvalDecisionValues(decision) : null;
        return `
            <div class="modal-backdrop" data-action="close-modal">
                <section
                    class="modal approval-modal"
                    role="dialog"
                    aria-modal="true"
                    aria-label="处理外部审批"
                >
                    <div class="modal-title">
                        <div>
                            <span class="eyebrow">待处理任务</span>
                            <h3>处理 ${escapeHtml(modal.taskKey)}</h3>
                        </div>
                        <button
                            class="btn btn-icon btn-ghost"
                            data-action="close-modal"
                            aria-label="关闭"
                        >${icon("close")}</button>
                    </div>
                    <p>
                        填写结果后，PAUSE TaskRun 会先恢复为 RUNNING，再由
                        Executor 状态机继续推进流程。
                    </p>
                    <div id="approval-form" class="approval-form">
                        ${decision
                            ? `<div class="approval-decision">
                                <label>审批决定</label>
                                <span>请使用底部的“同意”或“拒绝”按钮提交决定。</span>
                            </div>`
                            : ""}
                        ${fields.length
                            ? fields.map(renderApprovalField).join("")
                            : decision
                                ? ""
                                : `<div class="inspector-note">
                                    当前 PAUSE 没有声明输出，确认后将直接继续流程。
                                </div>`}
                    </div>
                    <div class="modal-actions approval-actions">
                        <button class="btn btn-ghost" data-action="close-modal">
                            暂不处理
                        </button>
                        ${decision
                            ? `<button
                                class="btn btn-danger"
                                data-action="submit-approval"
                                data-decision-value="${escapeAttribute(values.reject)}"
                            >
                                拒绝并继续
                            </button>
                            <button
                                class="btn btn-primary"
                                data-action="submit-approval"
                                data-decision-value="${escapeAttribute(values.approve)}"
                            >
                                ${icon("check")} 同意并继续
                            </button>`
                            : `<button
                                class="btn btn-primary"
                                data-action="submit-approval"
                            >
                                ${icon("check")} 提交并继续
                            </button>`}
                    </div>
                </section>
            </div>
        `;
    }

    function renderApprovalField(output) {
        const key = output.key;
        const type = String(output.type || "STRING").toUpperCase();
        const label = approvalFieldLabel(key);
        if (["BOOLEAN", "BOOL"].includes(type)) {
            return `
                <div class="approval-field">
                    <label for="approval-${escapeAttribute(key)}">
                        ${escapeHtml(label)}
                    </label>
                    <select
                        id="approval-${escapeAttribute(key)}"
                        data-output-key="${escapeAttribute(key)}"
                        data-output-type="${escapeAttribute(type)}"
                    >
                        <option value="">请选择</option>
                        <option value="true">是</option>
                        <option value="false">否</option>
                    </select>
                </div>
            `;
        }
        if (
            ["INTEGER", "INT", "LONG", "NUMBER", "DOUBLE", "DECIMAL"]
                .includes(type)
        ) {
            return `
                <div class="approval-field">
                    <label for="approval-${escapeAttribute(key)}">
                        ${escapeHtml(label)}
                    </label>
                    <input
                        id="approval-${escapeAttribute(key)}"
                        type="number"
                        data-output-key="${escapeAttribute(key)}"
                        data-output-type="${escapeAttribute(type)}"
                        placeholder="请输入数值"
                    >
                </div>
            `;
        }
        if (isLongTextOutput(key)) {
            return `
                <div class="approval-field">
                    <label for="approval-${escapeAttribute(key)}">
                        ${escapeHtml(label)}
                    </label>
                    <textarea
                        id="approval-${escapeAttribute(key)}"
                        data-output-key="${escapeAttribute(key)}"
                        data-output-type="${escapeAttribute(type)}"
                        placeholder="请输入审批意见（可选）"
                    ></textarea>
                </div>
            `;
        }
        return `
            <div class="approval-field">
                <label for="approval-${escapeAttribute(key)}">
                    ${escapeHtml(label)}
                </label>
                <input
                    id="approval-${escapeAttribute(key)}"
                    data-output-key="${escapeAttribute(key)}"
                    data-output-type="${escapeAttribute(type)}"
                    placeholder="请输入${escapeAttribute(label)}"
                >
            </div>
        `;
    }

    function bindGlobalEvents() {
        document.addEventListener("click", async (event) => {
            const element = event.target.closest("[data-action]");
            if (!element) {
                return;
            }
            const action = element.dataset.action;
            if (
                action !== "close-modal" &&
                element.closest(".modal") &&
                action === undefined
            ) {
                return;
            }
            event.stopPropagation();
            await handleAction(action, element);
        });

        document.addEventListener("input", (event) => {
            if (event.target.matches("[data-role='draft-search']")) {
                state.search = event.target.value;
                filterDraftItems();
                return;
            }
            if (event.target.id === "yaml-editor") {
                handleYamlInput(event.target);
                return;
            }
            if (event.target.matches("[data-modal-field]")) {
                updateModalField(event.target);
                return;
            }
            if (
                event.target.matches(
                    "[data-list-name][data-list-field]",
                )
            ) {
                if (
                    event.target.tagName === "SELECT"
                    || event.target.type === "checkbox"
                ) {
                    return;
                }
                updateDataListItem(
                    event.target.dataset.listName,
                    Number(event.target.dataset.listIndex),
                    event.target.dataset.listField,
                    dataListControlValue(event.target),
                );
                updateSaveState();
                return;
            }
            if (event.target.matches("[data-plugin-field]")) {
                if (event.target.dataset.pluginKind === "json") {
                    return;
                }
                updatePluginDefinitionField(event.target);
                updateSaveState();
                return;
            }
            if (event.target.matches("[data-field]")) {
                updateDefinitionField(
                    event.target.dataset.field,
                    event.target.value,
                );
                updateSaveState();
            }
        });

        document.addEventListener("change", async (event) => {
            if (event.target.matches("[data-modal-field]")) {
                updateModalField(event.target);
                render();
                return;
            }
            if (
                event.target.matches(
                    "[data-list-name][data-list-field]",
                )
            ) {
                updateDataListItem(
                    event.target.dataset.listName,
                    Number(event.target.dataset.listIndex),
                    event.target.dataset.listField,
                    dataListControlValue(event.target),
                );
                render();
                return;
            }
            if (event.target.matches("[data-dependency-key]")) {
                toggleTaskDependency(
                    event.target.dataset.dependencyKey,
                    event.target.checked,
                );
                render();
                return;
            }
            if (event.target.matches("[data-plugin-field]")) {
                updatePluginDefinitionField(event.target);
                render();
                return;
            }
            if (event.target.matches("[data-field]")) {
                updateDefinitionField(
                    event.target.dataset.field,
                    event.target.value,
                );
                if (event.target.dataset.field === "task-type") {
                    await loadPluginDetails(event.target.value);
                    const task = selectedTask();
                    if (task) {
                        applyPluginDefaults(task, event.target.value);
                        markDefinitionChanged(false);
                    }
                }
                render();
            }
        });

        document.addEventListener("keydown", (event) => {
            if (
                (event.metaKey || event.ctrlKey) &&
                event.key.toLowerCase() === "s"
            ) {
                event.preventDefault();
                runBusyAction(saveDraft);
            }
            if (event.key === "Escape" && state.modal) {
                state.modal = null;
                render();
            }
        });
    }

    async function handleAction(action, element) {
        switch (action) {
            case "reload-page":
                window.location.reload();
                break;
            case "new-flow":
                if (confirmDiscardChanges()) {
                    openFlowCreator();
                }
                break;
            case "create-flow":
                await runBusyAction(createFlowFromModal);
                break;
            case "open-task-picker":
                openTaskPicker(element.dataset.placement || "flow");
                break;
            case "open-node-task-picker":
                state.selectedPath = element.dataset.taskPath;
                openTaskPicker(element.dataset.placement || "child");
                break;
            case "confirm-create-task": {
                const placement = state.modal?.placement || "flow";
                const taskType = state.modal?.taskType;
                if (!taskType) {
                    showToast("当前没有可用的 Task 插件", true);
                    break;
                }
                state.modal = null;
                await loadPluginDetails(taskType);
                addTask(taskType, placement);
                break;
            }
            case "open-branch-builder":
                openBranchBuilder(element.dataset.branchMode);
                break;
            case "create-branch-structure":
                try {
                    createBranchStructure();
                } catch (error) {
                    showToast(error.message, true);
                }
                break;
            case "save-flow":
                await runBusyAction(saveDraft);
                break;
            case "deploy-flow":
                await runBusyAction(deployFlow);
                break;
            case "start-flow":
                await runBusyAction(startFlow);
                break;
            case "submit-start":
                await runBusyAction(() => {
                    const inputs = collectStartInputs();
                    return startFlowWithInputs(inputs);
                });
                break;
            case "select-draft":
                if (
                    element.dataset.key !== state.current?.flowKey &&
                    confirmDiscardChanges()
                ) {
                    await runBusyAction(() =>
                        openDraft(element.dataset.key),
                    );
                }
                break;
            case "switch-tab":
                state.tab = element.dataset.tab;
                render();
                break;
            case "select-flow":
                state.selectedPath = null;
                render();
                break;
            case "select-task":
                state.selectedPath = element.dataset.path;
                await loadPluginDetails(selectedTask()?.type);
                render();
                break;
            case "add-parent-route-output":
                addParentRouteOutput();
                break;
            case "apply-approval-template":
                applyApprovalTemplate();
                break;
            case "add-data-item":
                addDataListItem(element.dataset.listName);
                break;
            case "remove-data-item":
                removeDataListItem(
                    element.dataset.listName,
                    Number(element.dataset.listIndex),
                );
                break;
            case "delete-task":
                deleteSelectedTask();
                break;
            case "move-task":
                moveSelectedTask(Number(element.dataset.direction));
                break;
            case "delete-flow":
                await runBusyAction(deleteFlow);
                break;
            case "toggle-run-panel":
                state.runCollapsed = !state.runCollapsed;
                render();
                break;
            case "select-execution":
                state.selectedExecutionId = element.dataset.id;
                render();
                break;
            case "cancel-execution":
                await runBusyAction(() =>
                    cancelExecution(element.dataset.id),
                );
                break;
            case "open-resume":
                openResumeModal(
                    element.dataset.executionId,
                    element.dataset.taskRunId,
                );
                break;
            case "close-modal":
                if (
                    element.classList.contains("modal-backdrop") &&
                    eventTargetInsideModal(element)
                ) {
                    break;
                }
                state.modal = null;
                render();
                break;
            case "submit-approval":
                {
                    const outputs = collectApprovalOutputs(
                        element.dataset.decisionValue,
                    );
                    await runBusyAction(() =>
                        resumeExecution(outputs),
                    );
                }
                break;
            default:
                break;
        }
    }

    function eventTargetInsideModal(backdrop) {
        return backdrop.querySelector(".modal:hover") !== null;
    }

    async function runBusyAction(action) {
        if (state.busy) {
            return;
        }
        state.busy = true;
        render();
        try {
            await action();
        } catch (error) {
            showToast(error.message, true);
        } finally {
            state.busy = false;
            render();
        }
    }

    function openFlowCreator() {
        state.modal = {
            kind: "flow-creator",
            flowKey: suggestedFlowKey(),
            description: "",
        };
        render();
        setTimeout(() => {
            document.querySelector(
                '.flow-creator-modal [data-modal-field="flowKey"]',
            )?.focus();
        }, 0);
    }

    async function createFlowFromModal() {
        if (state.modal?.kind !== "flow-creator") {
            return;
        }
        const flowKey = requireBuilderText(
            state.modal.flowKey,
            "流程 key",
        );
        if (
            state.drafts.some(
                (draft) => draftName(draft) === flowKey,
            )
        ) {
            throw new Error(`流程 key 已存在：${flowKey}`);
        }
        const definition = {
            key: flowKey,
            description:
                String(state.modal.description || "").trim()
                || "请描述这个流程解决的业务问题",
            inputs: [],
            outputs: [],
            tasks: [],
        };
        const saved = await api("/flows", {
            method: "POST",
            body: {
                key: flowKey,
                raw: stringifyYaml(definition),
            },
        });
        state.current = saved;
        state.definition = definition;
        state.raw = saved.raw;
        state.dirty = false;
        state.yamlError = null;
        state.selectedPath = null;
        state.selectedExecutionId = null;
        state.tab = "canvas";
        state.modal = null;
        mergeDraft(saved);
        showToast("流程草稿已创建，可以添加第一个流程 Task");
    }

    function suggestedFlowKey() {
        const keys = new Set(state.drafts.map(draftName));
        let index = state.drafts.length + 1;
        while (keys.has(`flow-${index}`)) {
            index++;
        }
        return `flow-${index}`;
    }

    function openTaskPicker(placement) {
        if (!state.definition) {
            return;
        }
        const target = ["after", "child"].includes(placement)
            ? placement
            : "flow";
        if (
            target !== "flow"
            && (
                state.selectedPath === null
                || !selectedTask()
            )
        ) {
            showToast("请先选择一个 Task", true);
            return;
        }
        if (
            target === "child"
            && selectedTask()?.type === TASK_TYPES.PAUSE
        ) {
            showToast(
                "PAUSE 不使用 tasks；请编辑它的 pause 前置任务，或添加同级后续 Task",
                true,
            );
            return;
        }
        const firstPlugin = availableTaskPlugins()[0];
        if (!firstPlugin) {
            showToast("当前没有已注册的 Task 插件", true);
            return;
        }
        state.modal = {
            kind: "task-picker",
            placement: target,
            taskType: firstPlugin.type,
        };
        render();
    }

    function openBranchBuilder(mode) {
        if (!state.definition || state.selectedPath === null) {
            showToast("请先选择一个父 Task", true);
            return;
        }
        if (!["parallel", "route"].includes(mode)) {
            showToast("未知的编排方式", true);
            return;
        }
        const parent = selectedTask();
        if (parent?.type === TASK_TYPES.PAUSE) {
            showToast(
                "PAUSE 不使用 tasks；恢复后的流程由同级后续 Task 和 Executor 推进",
                true,
            );
            return;
        }
        const reserved = new Set();
        const parallel = mode === "parallel";
        const parallelKey = parallel
            ? uniqueTaskKey("parallel-task", reserved)
            : "";
        if (parallelKey) {
            reserved.add(parallelKey);
        }
        const output = firstRouteOutput(parent)
            || { key: "decision", type: "STRING" };
        const values = approvalDecisionValues(output);
        const branchAKey = uniqueTaskKey(
            parallel ? "parallel-branch-a" : "route-approved",
            reserved,
        );
        reserved.add(branchAKey);
        const branchBKey = uniqueTaskKey(
            parallel ? "parallel-branch-b" : "route-rejected",
            reserved,
        );
        reserved.add(branchBKey);
        state.modal = {
            kind: "branch-builder",
            mode,
            parentPath: state.selectedPath,
            parentKey: parent.key,
            parallelKey,
            branchAKey,
            branchBKey,
            branchAType: parallel ? TASK_TYPES.PAUSE : TASK_TYPES.LOG,
            branchBType: parallel ? TASK_TYPES.PAUSE : TASK_TYPES.LOG,
            branchAValue: values.approve,
            branchBValue: values.reject,
            outputKey: output.key,
            createJoin: true,
            joinKey: uniqueTaskKey("parallel-join", reserved),
        };
        render();
        setTimeout(() => {
            document.querySelector(
                ".branch-builder-modal [data-modal-field]",
            )?.focus();
        }, 0);
    }

    function updateModalField(element) {
        if (!state.modal) {
            return;
        }
        const field = element.dataset.modalField;
        state.modal[field] =
            element.type === "checkbox"
                ? element.checked
                : element.value;
        if (
            field === "outputKey"
            && state.modal.kind === "branch-builder"
            && state.modal.mode === "route"
        ) {
            const parent = taskAtPath(state.modal.parentPath);
            const output = (parent?.outputs || []).find(
                (candidate) => candidate.key === element.value,
            ) || { key: element.value, type: "STRING" };
            const values = approvalDecisionValues(output);
            state.modal.branchAValue = values.approve;
            state.modal.branchBValue = values.reject;
        }
    }

    function createBranchStructure() {
        if (state.modal?.kind !== "branch-builder") {
            return;
        }
        const modal = state.modal;
        const parent = taskAtPath(modal.parentPath);
        if (!parent) {
            throw new Error("父任务已不存在，请重新选择");
        }
        const branchAKey = requireBuilderText(
            modal.branchAKey,
            "分支 A 的任务 key",
        );
        const branchBKey = requireBuilderText(
            modal.branchBKey,
            "分支 B 的任务 key",
        );
        const newKeys = [branchAKey, branchBKey];
        let parallelKey = null;
        if (modal.mode === "parallel") {
            parallelKey = requireBuilderText(
                modal.parallelKey,
                "并行节点 key",
            );
            newKeys.unshift(parallelKey);
        }
        let joinKey = null;
        if (modal.mode === "parallel" && modal.createJoin) {
            joinKey = requireBuilderText(
                modal.joinKey,
                "汇合任务 key",
            );
            newKeys.push(joinKey);
        }
        ensureNewTaskKeys(newKeys);

        const children = Array.isArray(parent.tasks)
            ? parent.tasks
            : [];
        const firstIndex = children.length;
        if (modal.mode === "parallel") {
            const parallelTask = taskDefinition(
                TASK_TYPES.PARALLEL,
                parallelKey,
            );
            parallelTask.tasks.push(
                taskDefinition(modal.branchAType, branchAKey),
                taskDefinition(modal.branchBType, branchBKey),
            );
            children.push(parallelTask);
            if (joinKey) {
                const join = taskDefinition(TASK_TYPES.LOG, joinKey);
                join.dependOn = [branchAKey, branchBKey];
                children.push(join);
            }
        } else {
            const outputKey = requireBuilderText(
                modal.outputKey,
                "父任务输出 key",
            );
            if (!/^[A-Za-z][A-Za-z0-9_-]*$/.test(outputKey)) {
                throw new Error(
                    "Route 输出 key 必须以英文字母开头，只能包含字母、数字、_ 或 -",
                );
            }
            const branchAValue = requireRouteValue(
                modal.branchAValue,
                "命中值 A",
            );
            const branchBValue = requireRouteValue(
                modal.branchBValue,
                "命中值 B",
            );
            if (branchAValue === branchBValue) {
                throw new Error("两个 Route 命中值必须不同");
            }
            const existingOutput = (parent.outputs || []).find(
                (output) => output.key === outputKey,
            );
            if (!existingOutput) {
                throw new Error(
                    `父任务必须先声明实际可产生的 STRING 输出 ${outputKey}`,
                );
            }
            if (
                existingOutput
                && !isStringOutput(existingOutput)
            ) {
                throw new Error(
                    `父任务输出 ${outputKey} 必须是 STRING，当前为 ${existingOutput.type}`,
                );
            }
            const branchA = taskDefinition(
                modal.branchAType,
                branchAKey,
            );
            branchA.route = routeExpression(outputKey, branchAValue);
            const branchB = taskDefinition(
                modal.branchBType,
                branchBKey,
            );
            branchB.route = routeExpression(outputKey, branchBValue);
            children.push(branchA, branchB);
        }
        parent.tasks = children;
        state.selectedPath = `${modal.parentPath}.${firstIndex}`;
        state.modal = null;
        markDefinitionChanged();
        render();
        showToast(
            modal.mode === "parallel"
                ? "已创建显式 PARALLEL 节点、两个分支和后续关系"
                : "已创建两个可视化 Route 条件分支",
        );
    }

    function requireBuilderText(value, label) {
        const normalized = String(value || "").trim();
        if (!normalized) {
            throw new Error(`请填写${label}`);
        }
        return normalized;
    }

    function requireRouteValue(value, label) {
        const normalized = requireBuilderText(value, label);
        if (normalized.includes('"')) {
            throw new Error(`${label}不能包含双引号`);
        }
        return normalized;
    }

    function ensureNewTaskKeys(keys) {
        if (new Set(keys).size !== keys.length) {
            throw new Error("新建分支和汇合节点的 task key 不能重复");
        }
        const existing = definitionTaskKeys();
        const duplicate = keys.find((key) => existing.has(key));
        if (duplicate) {
            throw new Error(`Task key 已存在：${duplicate}`);
        }
    }

    function ensureRouteOutput(parent, outputKey) {
        parent.outputs = Array.isArray(parent.outputs)
            ? parent.outputs
            : [];
        const existing = parent.outputs.find(
            (output) => output.key === outputKey,
        );
        if (
            existing
            && String(existing.type || "STRING").toUpperCase() !== "STRING"
        ) {
            throw new Error(
                `父任务输出 ${outputKey} 必须是 STRING，当前为 ${existing.type}`,
            );
        }
        if (!existing) {
            parent.outputs.push({ key: outputKey, type: "STRING" });
        }
    }

    function addParentRouteOutput() {
        const task = selectedTask();
        const parent = parentTaskForPath(state.selectedPath);
        if (!task || !parent) {
            return;
        }
        if (parent.type === TASK_TYPES.PAUSE) {
            showToast(
                "PAUSE 的恢复数据由 resume 定义，不能作为 tasks 的父路由输出",
                true,
            );
            return;
        }
        const outputKey = uniqueDataKey(parent.outputs, "decision");
        ensureRouteOutput(parent, outputKey);
        task.route = routeExpression(outputKey, "APPROVED");
        markDefinitionChanged();
        render();
        showToast(`已为父任务添加 ${outputKey} STRING 输出`);
    }

    function createLocalDraft(shouldRender = true) {
        const definition = defaultDefinition();
        state.current = {
            id: null,
            raw: "",
            createdAt: Date.now(),
            updatedAt: Date.now(),
            deployedFlow: null,
        };
        state.definition = definition;
        state.raw = stringifyYaml(definition);
        state.current.raw = state.raw;
        state.dirty = true;
        state.yamlError = null;
        state.selectedPath = null;
        state.selectedExecutionId = null;
        state.tab = "canvas";
        if (shouldRender) {
            render();
        }
    }

    async function openDraft(flowKey) {
        const draft = await api(
            `/flows/${encodeURIComponent(flowKey)}`,
        );
        state.current = draft;
        state.raw = draft.raw;
        state.dirty = false;
        state.yamlError = null;
        state.selectedPath = null;
        state.selectedExecutionId =
            state.executions.find(
                (item) => item.flowKey === (
                    draft.flowKey ||
                    draft.deployedFlow?.key
                    || extractKey(draft.raw)
                    || flowKey
                ),
            )?.id || null;
        await parseCurrentRaw(false);
    }

    async function saveDraft() {
        if (!state.current) {
            return;
        }
        const body = {
            key: currentFlowKey(),
            raw: state.raw,
            draft: true,
        };
        const saved = await api("/flows", {
            method: "POST",
            body,
        });
        state.current = saved;
        state.raw = saved.raw;
        state.dirty = false;
        mergeDraft(saved);
        showToast("草稿已写入 Flow Core");
    }

    async function deployFlow() {
        if (state.dirty) {
            await saveDraft();
        }
        const deployed = await api(
            `/flows/${encodeURIComponent(state.current.flowKey)}/deploy`,
            { method: "POST" },
        );
        state.current = deployed;
        state.raw = deployed.raw;
        state.dirty = false;
        mergeDraft(deployed);
        showToast(
            `发布成功：${deployed.deployedFlow.key} R${deployed.deployedFlow.reversion}`,
        );
    }

    async function startFlow() {
        if (!state.current?.deployedFlow) {
            throw new Error("请先发布流程");
        }
        const inputs = state.current.deployedFlow.inputs || [];
        if (inputs.length > 0) {
            openStartModal();
            return;
        }
        await startFlowWithInputs({});
    }

    function openStartModal() {
        const inputs = state.current?.deployedFlow?.inputs || [];
        state.modal = {
            kind: "start",
            inputs,
        };
        render();
        setTimeout(() => {
            document.querySelector(
                "#start-input-form [data-start-key]",
            )?.focus();
        }, 0);
    }

    function collectStartInputs() {
        const inputs = {};
        const definitions = state.current?.deployedFlow?.inputs || [];
        document
            .querySelectorAll("#start-input-form [data-start-key]")
            .forEach((field) => {
                const raw = field.value;
                const definition = definitions.find(
                    (item) => item.key === field.dataset.startKey,
                );
                if (raw === "") {
                    if (
                        definition?.required === true
                        && definition?.defaultValue === undefined
                    ) {
                        throw new Error(
                            `请输入必填 Flow Input：${field.dataset.startKey}`,
                        );
                    }
                    return;
                }
                inputs[field.dataset.startKey] = coerceApprovalValue(
                    raw,
                    field.dataset.startType,
                );
            });
        return inputs;
    }

    async function startFlowWithInputs(inputs) {
        if (!state.current?.deployedFlow) {
            throw new Error("请先发布流程");
        }
        const receipt = await api(
            `/flows/${encodeURIComponent(currentFlowKey())}/executions`,
            {
                method: "POST",
                body: { inputs },
            },
        );
        state.modal = null;
        state.selectedExecutionId = receipt.executionId;
        state.runCollapsed = false;
        showToast("Execution 已受理，后台开始运行");
        render();
        schedulePoll();
    }

    async function deleteFlow() {
        if (!state.current?.flowKey) {
            createLocalDraft();
            return;
        }
        const confirmed = window.confirm(
            `删除流程 ${currentDefinitionKey()}？草稿与最新部署都会进入删除状态。`,
        );
        if (!confirmed) {
            return;
        }
        const flowKey = currentFlowKey();
        if (state.current.deployedFlow) {
            await api(`/flows/${encodeURIComponent(flowKey)}?draft=false`, {
                method: "DELETE",
            });
        }
        await api(`/flows/${encodeURIComponent(flowKey)}?draft=true`, {
            method: "DELETE",
        });
        state.drafts = state.drafts.filter(
            (draft) => draft.flowKey !== flowKey,
        );
        state.executions = state.executions.filter(
            (execution) => execution.flowKey !== flowKey,
        );
        if (state.drafts.length) {
            await openDraft(state.drafts[0].flowKey);
        } else {
            createLocalDraft(false);
        }
        showToast("流程已删除");
    }

    async function cancelExecution(id) {
        const confirmed = window.confirm("取消这个 Execution？");
        if (!confirmed) {
            return;
        }
        const execution = await api(
            `/executions/${encodeURIComponent(id)}/cancel`,
            { method: "POST" },
        );
        mergeExecution(execution);
        state.selectedExecutionId = execution.id;
        showToast("Execution 已取消");
    }

    function openResumeModal(executionId, taskRunId) {
        const task = deployedTaskByRun(taskRunId);
        state.modal = {
            kind: "approval",
            executionId,
            taskRunId,
            taskKey: task?.key || "外部审批",
            inputs: task?.resume || [],
        };
        render();
        setTimeout(() => {
            document.querySelector(
                "#approval-form input, #approval-form textarea, "
                    + "#approval-form select",
            )?.focus();
        }, 0);
    }

    async function resumeExecution(outputs) {
        if (state.modal?.kind !== "approval") {
            return;
        }
        const { executionId, taskRunId } = state.modal;
        const execution = await api(
            `/executions/${encodeURIComponent(executionId)}`
                + `/task-runs/${encodeURIComponent(taskRunId)}/resume`,
            {
                method: "POST",
                body: { outputs },
            },
        );
        state.modal = null;
        mergeExecution(execution);
        state.selectedExecutionId = execution.id;
        showToast(
            `PAUSE 已恢复，Execution：${stateLabel(execution.state)}`,
        );
        replayExecution(execution);
        schedulePoll();
    }

    function collectApprovalOutputs(decisionValue) {
        const modal = state.modal;
        const outputs = {};
        const decision = approvalDecisionOutput(modal.inputs);
        if (decision && decisionValue !== undefined) {
            outputs[decision.key] = coerceApprovalValue(
                decisionValue,
                decision.type,
            );
        }
        document
            .querySelectorAll("#approval-form [data-output-key]")
            .forEach((field) => {
                const raw = field.value;
                if (raw === "") {
                    return;
                }
                outputs[field.dataset.outputKey] = coerceApprovalValue(
                    raw,
                    field.dataset.outputType,
                );
            });
        return outputs;
    }

    function coerceApprovalValue(value, type) {
        const normalized = String(type || "STRING").toUpperCase();
        if (normalized === "BOOLEAN") {
            return String(value).toLowerCase() === "true";
        }
        if (
            [
                "BYTE",
                "SHORT",
                "INTEGER",
                "LONG",
                "FLOAT",
                "DOUBLE",
            ]
                .includes(normalized)
        ) {
            return Number(value);
        }
        if (normalized === "CHARACTER") {
            return String(value).slice(0, 1);
        }
        return String(value);
    }

    function addTask(type, placement) {
        if (!state.definition) {
            return;
        }
        const task = newTask(type);
        if (placement === "after" && state.selectedPath !== null) {
            const location = taskLocation(state.selectedPath);
            const nextIndex = location.index + 1;
            location.tasks.splice(nextIndex, 0, task);
            const path = String(state.selectedPath).split(".");
            path[path.length - 1] = String(nextIndex);
            state.selectedPath = path.join(".");
        } else if (
            placement === "child"
            && state.selectedPath !== null
        ) {
            const selected = selectedTask();
            if (selected?.type === TASK_TYPES.PAUSE) {
                showToast(
                    "PAUSE 不使用 tasks；请添加同级后续 Task",
                    true,
                );
                return;
            }
            selected.tasks = Array.isArray(selected.tasks)
                ? selected.tasks
                : [];
            selected.tasks.push(task);
            state.selectedPath =
                `${state.selectedPath}.${selected.tasks.length - 1}`;
        } else {
            state.definition.tasks = Array.isArray(state.definition.tasks)
                ? state.definition.tasks
                : [];
            state.definition.tasks.push(task);
            state.selectedPath = String(
                state.definition.tasks.length - 1,
            );
        }
        markDefinitionChanged();
        render();
    }

    function toggleTaskDependency(key, enabled) {
        const task = selectedTask();
        if (!task || !key) {
            return;
        }
        const dependencies = new Set(task.dependOn || []);
        if (enabled) {
            dependencies.add(key);
        } else {
            dependencies.delete(key);
        }
        task.dependOn = [...dependencies];
        markDefinitionChanged();
    }

    function applyApprovalTemplate() {
        const task = selectedTask();
        if (!task || task.type !== TASK_TYPES.PAUSE) {
            return;
        }
        task.resume = approvalTemplateInputs();
        markDefinitionChanged();
        render();
        showToast("已应用审批决定和审批意见字段");
    }

    function deleteSelectedTask() {
        if (state.selectedPath === null) {
            return;
        }
        const task = selectedTask();
        if (
            !window.confirm(
                `删除节点 ${task?.key || ""} 以及它的所有子任务？`,
            )
        ) {
            return;
        }
        const location = taskLocation(state.selectedPath);
        location.tasks.splice(location.index, 1);
        state.selectedPath = null;
        markDefinitionChanged();
        render();
    }

    function moveSelectedTask(direction) {
        if (state.selectedPath === null) {
            return;
        }
        const location = taskLocation(state.selectedPath);
        const target = location.index + direction;
        if (target < 0 || target >= location.tasks.length) {
            return;
        }
        const [task] = location.tasks.splice(location.index, 1);
        location.tasks.splice(target, 0, task);
        const parts = state.selectedPath.split(".");
        parts[parts.length - 1] = String(target);
        state.selectedPath = parts.join(".");
        markDefinitionChanged();
        render();
    }

    function dataList(listName) {
        const task = selectedTask();
        switch (listName) {
            case "flow-inputs":
                state.definition.inputs = Array.isArray(
                    state.definition.inputs,
                ) ? state.definition.inputs : [];
                return state.definition.inputs;
            case "flow-outputs":
                state.definition.outputs = Array.isArray(
                    state.definition.outputs,
                ) ? state.definition.outputs : [];
                return state.definition.outputs;
            case "task-inputs":
                if (!task) {
                    return null;
                }
                task.inputs = Array.isArray(task.inputs) ? task.inputs : [];
                return task.inputs;
            case "task-outputs":
                if (!task) {
                    return null;
                }
                task.outputs = Array.isArray(task.outputs)
                    ? task.outputs
                    : [];
                return task.outputs;
            case "task-resume":
                if (!task || task.type !== TASK_TYPES.PAUSE) {
                    return null;
                }
                task.resume = Array.isArray(task.resume)
                    ? task.resume
                    : [];
                return task.resume;
            default:
                return null;
        }
    }

    function updateDataListItem(listName, index, field, value) {
        const allowedFields = [
            "key",
            "type",
            "displayName",
            "required",
            "defaultValue",
            "min",
            "max",
        ];
        if (!state.definition || !allowedFields.includes(field)) {
            return;
        }
        const items = dataList(listName);
        if (!items?.[index]) {
            return;
        }
        if (field === "type") {
            const nextType = String(value || "STRING").toUpperCase();
            if (items[index].type !== nextType) {
                delete items[index].defaultValue;
                delete items[index].min;
                delete items[index].max;
            }
            items[index].type = nextType;
        } else if (
            value === null
            && ["defaultValue", "min", "max"].includes(field)
        ) {
            delete items[index][field];
        } else {
            items[index][field] = value;
        }
        markDefinitionChanged(false);
    }

    function dataListControlValue(element) {
        if (element.type === "checkbox") {
            return element.checked;
        }
        const value = element.value;
        const field = element.dataset.listField;
        if (
            value === ""
            && ["defaultValue", "min", "max"].includes(field)
        ) {
            return null;
        }
        const items = dataList(element.dataset.listName);
        const item = items?.[Number(element.dataset.listIndex)];
        const type = String(item?.type || "STRING").toUpperCase();
        if (
            ["min", "max"].includes(field)
            || (
                field === "defaultValue"
                && [
                    "BYTE",
                    "SHORT",
                    "INTEGER",
                    "LONG",
                    "FLOAT",
                    "DOUBLE",
                ].includes(type)
            )
        ) {
            return Number(value);
        }
        if (field === "defaultValue" && type === "BOOLEAN") {
            return value === "true";
        }
        return value;
    }

    function addDataListItem(listName) {
        if (!state.definition) {
            return;
        }
        const items = dataList(listName);
        if (!items) {
            return;
        }
        items.push(
            listName.endsWith("-inputs") || listName === "task-resume"
                ? {
                    key: "",
                    type: "STRING",
                    displayName: "",
                    required: false,
                }
                : { key: "", type: "STRING" },
        );
        const index = items.length - 1;
        markDefinitionChanged();
        render();
        setTimeout(() => {
            document.querySelector(
                `[data-list-name="${listName}"]`
                    + `[data-list-index="${index}"]`
                    + '[data-list-field="key"]',
            )?.focus();
        }, 0);
    }

    function removeDataListItem(listName, index) {
        if (!state.definition) {
            return;
        }
        const items = dataList(listName);
        if (!items?.[index]) {
            return;
        }
        items.splice(index, 1);
        markDefinitionChanged();
        render();
    }

    function pluginSchemaProperties(type) {
        return state.pluginDetails.get(type)?.schema?.properties || {};
    }

    function pluginSpecificPropertyNames(type) {
        return Object.keys(pluginSchemaProperties(type))
            .filter((name) => !COMMON_TASK_FIELDS.has(name));
    }

    function removePluginDefinitionFields(task, type) {
        pluginSpecificPropertyNames(type).forEach((name) => {
            delete task[name];
        });
    }

    function applyPluginDefaults(task, type) {
        if (type === TASK_TYPES.LOG) {
            task.outputs = [];
            task.tasks = [];
            if (!Object.prototype.hasOwnProperty.call(task, "message")) {
                task.message = "流程步骤";
            }
        }
        if (type === TASK_TYPES.PAUSE) {
            task.outputs = [];
            task.tasks = [];
            if (!task.pause || typeof task.pause !== "object") {
                task.pause = taskDefinition(
                    TASK_TYPES.LOG,
                    uniqueTaskKey(`${task.key || "pause"}-action`),
                );
            }
            if (!Array.isArray(task.resume)) {
                task.resume = approvalTemplateInputs();
            }
        }
        Object.entries(pluginSchemaProperties(type))
            .filter(([name]) => !COMMON_TASK_FIELDS.has(name))
            .forEach(([name, schema]) => {
                if (
                    !Object.prototype.hasOwnProperty.call(task, name)
                    && Object.prototype.hasOwnProperty.call(
                        schema,
                        "default",
                    )
                ) {
                    task[name] = cloneDefinitionValue(schema.default);
                }
            });
    }

    function cloneDefinitionValue(value) {
        if (value === null || typeof value !== "object") {
            return value;
        }
        return JSON.parse(JSON.stringify(value));
    }

    function updatePluginDefinitionField(control) {
        const task = selectedTask();
        if (!task) {
            return;
        }
        const field = control.dataset.pluginField;
        const kind = control.dataset.pluginKind;
        const required = control.dataset.pluginRequired === "true";
        const raw = control.value;
        if (raw === "" && !required) {
            delete task[field];
            markDefinitionChanged(false);
            return;
        }
        if (kind === "boolean") {
            task[field] = raw === "true";
        } else if (kind === "integer" || kind === "number") {
            const value = Number(raw);
            if (!Number.isFinite(value)) {
                showToast(`${field} 必须是数字`, true);
                return;
            }
            task[field] = value;
        } else if (kind === "json") {
            try {
                task[field] = JSON.parse(raw);
            } catch {
                showToast(`${field} 必须是合法 JSON`, true);
                return;
            }
        } else {
            task[field] = raw;
        }
        markDefinitionChanged(false);
    }

    function updateDefinitionField(field, value) {
        if (!state.definition) {
            return;
        }
        const task = selectedTask();
        switch (field) {
            case "flow-key":
                state.definition.key = value;
                break;
            case "flow-description":
                state.definition.description = value;
                break;
            case "task-key":
                if (task) {
                    task.key = value;
                }
                break;
            case "task-type":
                if (task && task.type !== value) {
                    removePluginDefinitionFields(task, task.type);
                    task.type = value;
                    applyPluginDefaults(task, value);
                }
                break;
            case "task-route":
                if (task) {
                    task.route = value || "DIRECT";
                }
                break;
            case "task-route-mode":
                if (!task) {
                    break;
                }
                if (
                    value === "DIRECT"
                    || !parentTaskForPath(state.selectedPath)
                ) {
                    task.route = "DIRECT";
                    break;
                }
                {
                    const parent = parentTaskForPath(state.selectedPath);
                    const output = firstRouteOutput(parent);
                    if (!output) {
                        task.route = "DIRECT";
                        showToast(
                            "上一个直连 Task 没有 STRING output，请先在父节点声明输出",
                            true,
                        );
                        break;
                    }
                    const values = routeValueSelection(
                        parent,
                        task,
                        output.key,
                        "",
                    );
                    task.route = routeExpression(
                        output.key,
                        values.values[0] || "",
                    );
                }
                break;
            case "task-route-output":
                if (task && value) {
                    const parent = parentTaskForPath(
                        state.selectedPath,
                    );
                    const values = routeValueSelection(
                        parent,
                        task,
                        value,
                        "",
                    );
                    task.route = routeExpression(
                        value,
                        values.values[0] || "",
                    );
                }
                break;
            case "task-route-value":
                if (task) {
                    const parsed = parseRouteExpression(task.route);
                    const parent = parentTaskForPath(state.selectedPath);
                    const output = parsed?.outputKey
                        || firstRouteOutput(parent)?.key;
                    if (output) {
                        task.route = routeExpression(output, value);
                    }
                }
                break;
            case "task-depend-on":
                if (task) {
                    task.dependOn = value
                        .split(",")
                        .map((item) => item.trim())
                        .filter(Boolean);
                }
                break;
            default:
                return;
        }
        markDefinitionChanged(false);
    }

    function markDefinitionChanged(shouldUpdateUi = true) {
        state.raw = stringifyYaml(state.definition);
        if (state.current) {
            state.current.raw = state.raw;
        }
        state.dirty = true;
        state.yamlError = null;
        if (shouldUpdateUi) {
            updateSaveState();
        }
    }

    function handleYamlInput(textarea) {
        state.raw = textarea.value;
        if (state.current) {
            state.current.raw = state.raw;
        }
        state.dirty = true;
        updateSaveState();
        clearTimeout(previewTimer);
        previewTimer = setTimeout(() => {
            parseCurrentRaw(true);
        }, 380);
    }

    async function parseCurrentRaw(preserveEditor) {
        const sequence = ++previewSequence;
        const rawAtRequest = state.raw;
        try {
            const result = await api("/flows/preview", {
                method: "POST",
                body: { source: rawAtRequest },
            });
            if (
                sequence !== previewSequence ||
                rawAtRequest !== state.raw
            ) {
                return;
            }
            state.definition = result.definition;
            if (
                !preserveEditor
                && upgradeLegacyInputDefinitions(state.definition)
            ) {
                state.raw = stringifyYaml(state.definition);
                if (state.current) {
                    state.current.raw = state.raw;
                }
                state.dirty = true;
            }
            state.yamlError = null;
            if (!selectedTask()) {
                state.selectedPath = null;
            }
        } catch (error) {
            if (
                sequence !== previewSequence ||
                rawAtRequest !== state.raw
            ) {
                return;
            }
            state.yamlError = error.message;
            state.definition = null;
            state.selectedPath = null;
        }
        if (preserveEditor) {
            renderWithEditorSelection();
        }
    }

    function upgradeLegacyInputDefinitions(definition) {
        let changed = false;
        const upgradeOwner = (owner) => {
            if (!owner || typeof owner !== "object") {
                return;
            }
            [
                ...(Array.isArray(owner.inputs) ? owner.inputs : []),
                ...(Array.isArray(owner.resume) ? owner.resume : []),
            ]
                .forEach((input) => {
                    if (!input || typeof input !== "object") {
                        return;
                    }
                    if (
                        !Object.prototype.hasOwnProperty.call(
                            input,
                            "displayName",
                        )
                    ) {
                        input.displayName = input.key || "";
                        changed = true;
                    }
                    if (
                        !Object.prototype.hasOwnProperty.call(
                            input,
                            "required",
                        )
                    ) {
                        input.required = false;
                        changed = true;
                    }
                });
            if (owner.pause && typeof owner.pause === "object") {
                upgradeOwner(owner.pause);
            }
            (Array.isArray(owner.tasks) ? owner.tasks : [])
                .forEach(upgradeOwner);
        };
        upgradeOwner(definition);
        return changed;
    }

    function renderWithEditorSelection() {
        const editor = document.querySelector("#yaml-editor");
        const start = editor?.selectionStart ?? 0;
        const end = editor?.selectionEnd ?? start;
        const focused = document.activeElement === editor;
        render();
        if (focused) {
            const next = document.querySelector("#yaml-editor");
            next?.focus();
            next?.setSelectionRange(start, end);
        }
    }

    function updateSaveState() {
        const element = document.querySelector(".save-state");
        if (!element) {
            return;
        }
        element.classList.add("dirty");
        element.innerHTML = "<span>● 存在未保存修改</span>";
    }

    function filterDraftItems() {
        document.querySelectorAll(".draft-item").forEach((item) => {
            item.classList.toggle(
                "hidden",
                !item.dataset.search.includes(state.search.toLowerCase()),
            );
        });
    }

    function mergeDraft(draft) {
        const index = state.drafts.findIndex(
            (item) => item.flowKey === draft.flowKey,
        );
        if (index >= 0) {
            state.drafts.splice(index, 1, draft);
        } else {
            state.drafts.unshift(draft);
        }
        state.drafts.sort((left, right) =>
            right.updatedAt - left.updatedAt,
        );
    }

    function mergeExecution(execution) {
        const index = state.executions.findIndex(
            (item) => item.id === execution.id,
        );
        if (index >= 0) {
            state.executions.splice(index, 1, execution);
        } else {
            state.executions.unshift(execution);
        }
        state.executions.sort((left, right) =>
            right.createdAt - left.createdAt,
        );
    }

    function selectedFlowExecutions() {
        const flowKey = currentFlowKey();
        return state.executions.filter(
            (execution) => execution.flowKey === flowKey,
        );
    }

    function selectedExecution() {
        const executions = selectedFlowExecutions();
        if (state.selectedExecutionId) {
            return executions.find(
                (item) => item.id === state.selectedExecutionId,
            ) || null;
        }
        return executions[0] || null;
    }

    function selectedTask() {
        return taskAtPath(state.selectedPath);
    }

    function taskAtPath(path) {
        if (!state.definition || path === null || path === undefined) {
            return null;
        }
        let tasks = state.definition.tasks || [];
        let task = null;
        for (const part of String(path).split(".")) {
            task = tasks[Number(part)];
            if (!task) {
                return null;
            }
            tasks = task.tasks || [];
        }
        return task;
    }

    function parentTaskForPath(path) {
        if (path === null || path === undefined) {
            return null;
        }
        const parts = String(path).split(".");
        if (parts.length < 2) {
            return null;
        }
        parts.pop();
        return taskAtPath(parts.join("."));
    }

    function taskLocation(path) {
        const parts = path.split(".").map(Number);
        const index = parts.pop();
        let tasks = state.definition.tasks;
        for (const part of parts) {
            tasks = tasks[part].tasks;
        }
        return { tasks, index };
    }

    function flattenTasks(
        tasks,
        parentPath,
        depth,
        result,
    ) {
        (Array.isArray(tasks) ? tasks : []).forEach((task, index) => {
            const path =
                parentPath === null
                    ? String(index)
                    : `${parentPath}.${index}`;
            result.push({
                task,
                path,
                parentPath,
                depth,
                siblingIndex: index,
                siblingCount: Array.isArray(tasks) ? tasks.length : 0,
            });
            flattenTasks(task.tasks, path, depth + 1, result);
        });
    }

    function taskStructureLabel(node) {
        if (node.task.type === TASK_TYPES.PARALLEL) {
            return "显式并行流程";
        }
        if ((node.task.dependOn || []).length > 1) {
            return "依赖汇合";
        }
        const parsed = parseRouteExpression(node.task.route);
        if (parsed?.kind === "condition") {
            return "条件路径";
        }
        if ((node.task.tasks || []).length > 0) {
            return node.depth === 0
                ? "主流程中的子流程"
                : "嵌套子流程";
        }
        if (node.depth === 0) {
            return `主流程 · 串行 ${node.siblingIndex + 1}`;
        }
        const parent = taskAtPath(node.parentPath);
        return parent?.type === TASK_TYPES.PARALLEL
            ? `并行分支 ${node.siblingIndex + 1}`
            : `子流程 · 串行 ${node.siblingIndex + 1}`;
    }

    function availableTaskPlugins() {
        return state.pluginPackages.flatMap((plugin) =>
            (Array.isArray(plugin.tasks) ? plugin.tasks : []).map(
                (task) => ({
                    ...task,
                    pluginPackage: plugin.packageName,
                }),
            )
        );
    }

    function taskPluginMetadata(type) {
        return availableTaskPlugins().find(
            (plugin) => plugin.type === type,
        ) || null;
    }

    function renderTaskTypeOptions(selectedType) {
        return availableTaskPlugins().map((plugin) => `
            <option
                value="${escapeAttribute(plugin.type)}"
                ${plugin.type === selectedType ? "selected" : ""}
            >
                ${escapeHtml(taskTypeCode(plugin.type))}
                · ${escapeHtml(plugin.title)}
                · ${escapeHtml(plugin.pluginPackage)}
            </option>
        `).join("");
    }

    function renderRegisteredTaskOptions(selectedType) {
        const groups = state.pluginPackages.map((plugin) => {
            const tasks = Array.isArray(plugin.tasks) ? plugin.tasks : [];
            if (tasks.length === 0) {
                return "";
            }
            return `
                <optgroup label="${escapeAttribute(
                    plugin.packageName,
                )}">
                    ${tasks.map((task) => `
                        <option
                            value="${escapeAttribute(task.type)}"
                            ${task.type === selectedType ? "selected" : ""}
                        >
                            ${escapeHtml(task.title)}
                            · ${escapeHtml(taskTypeCode(task.type))}
                        </option>
                    `).join("")}
                </optgroup>
            `;
        }).join("");
        return groups || '<option value="">没有已注册 Task</option>';
    }

    function renderSelectedTaskPlugin(plugin) {
        const style = taskTypeClass(plugin.type);
        return `
            <div class="task-picker-selection ${style}">
                <span class="task-picker-selection-icon">
                    ${taskTypeIcon(plugin.type)}
                </span>
                <div>
                    <strong>${escapeHtml(plugin.title)}</strong>
                    <small>
                        ${escapeHtml(plugin.pluginPackage)}
                        · ${escapeHtml(plugin.type)}
                    </small>
                    <p>${escapeHtml(
                        plugin.description || "该 Task 未提供描述",
                    )}</p>
                </div>
            </div>
        `;
    }

    function renderPluginDefinitionFields(task) {
        const details = state.pluginDetails.get(task.type);
        if (!details?.schema?.properties) {
            return "";
        }
        const required = new Set(details.schema.required || []);
        const properties = Object.entries(details.schema.properties)
            .filter(([name]) =>
                !COMMON_TASK_FIELDS.has(name)
                && !(
                    task.type === TASK_TYPES.PAUSE
                    && name === "resume"
                )
            );
        if (properties.length === 0) {
            return "";
        }
        return `
            <div class="inspector-divider"></div>
            <div class="field-section-title">插件配置</div>
            ${properties.map(([name, schema]) =>
                renderPluginDefinitionField(
                    task,
                    name,
                    schema,
                    required.has(name),
                    details.schema,
                )
            ).join("")}
        `;
    }

    function renderPluginDefinitionField(
        task,
        name,
        schema,
        required,
        rootSchema,
    ) {
        schema = resolvedPluginPropertySchema(schema, rootSchema);
        const kind = pluginPropertyType(schema);
        const value = Object.prototype.hasOwnProperty.call(task, name)
            ? task[name]
            : schema.default;
        const label = schema.title || name;
        const description = schema.description || name;
        const requiredMark = required ? " · 必填" : "";
        let control;
        if (Array.isArray(schema.enum)) {
            control = `
                <select
                    data-plugin-field="${escapeAttribute(name)}"
                    data-plugin-kind="${escapeAttribute(kind)}"
                    data-plugin-required="${required}"
                >
                    ${required ? "" : '<option value="">未设置</option>'}
                    ${schema.enum.map((item) => `
                        <option
                            value="${escapeAttribute(item)}"
                            ${String(item) === String(value) ? "selected" : ""}
                        >${escapeHtml(item)}</option>
                    `).join("")}
                </select>
            `;
        } else if (kind === "boolean") {
            control = `
                <select
                    data-plugin-field="${escapeAttribute(name)}"
                    data-plugin-kind="boolean"
                    data-plugin-required="${required}"
                >
                    ${required ? "" : '<option value="">未设置</option>'}
                    <option value="true" ${value === true ? "selected" : ""}>是</option>
                    <option value="false" ${value === false ? "selected" : ""}>否</option>
                </select>
            `;
        } else if (kind === "array" || kind === "object") {
            const jsonValue = value === undefined
                ? ""
                : JSON.stringify(value, null, 2);
            control = `
                <textarea
                    data-plugin-field="${escapeAttribute(name)}"
                    data-plugin-kind="json"
                    data-plugin-required="${required}"
                    placeholder="JSON"
                >${escapeHtml(jsonValue)}</textarea>
            `;
        } else {
            const inputType = kind === "integer" || kind === "number"
                ? "number"
                : "text";
            control = `
                <input
                    type="${inputType}"
                    data-plugin-field="${escapeAttribute(name)}"
                    data-plugin-kind="${escapeAttribute(kind)}"
                    data-plugin-required="${required}"
                    value="${escapeAttribute(value ?? "")}"
                >
            `;
        }
        return `
            <div class="field">
                <label>${escapeHtml(label)}${requiredMark}</label>
                ${control}
                <small>${escapeHtml(description)}</small>
            </div>
        `;
    }

    function resolvedPluginPropertySchema(schema, rootSchema) {
        if (!schema?.$ref?.startsWith("#/")) {
            return schema || {};
        }
        const target = schema.$ref.slice(2)
            .split("/")
            .map((part) => part.replaceAll("~1", "/").replaceAll("~0", "~"))
            .reduce((value, part) => value?.[part], rootSchema);
        return target
            ? { ...target, ...schema, $ref: undefined }
            : schema;
    }

    function pluginPropertyType(schema) {
        if (typeof schema?.type === "string") {
            return schema.type;
        }
        if (Array.isArray(schema?.type)) {
            return schema.type.find((type) => type !== "null") || "string";
        }
        const alternative = (schema?.anyOf || schema?.oneOf || [])
            .find((candidate) => candidate?.type !== "null");
        return alternative ? pluginPropertyType(alternative) : "string";
    }

    function taskTypeCode(type) {
        const entry = Object.entries(TASK_TYPES).find(
            ([, className]) => className === type,
        );
        if (entry) {
            return entry[0];
        }
        const className = String(type || TASK_TYPES.LOG);
        return className.split(/[.$]/).at(-1);
    }

    function taskTypeClass(type) {
        if (type === TASK_TYPES.PAUSE) {
            return "pause";
        }
        if (type === TASK_TYPES.PARALLEL) {
            return "parallel";
        }
        return "log";
    }

    function taskTypeIcon(type) {
        if (type === TASK_TYPES.PAUSE) {
            return "Ⅱ";
        }
        if (type === TASK_TYPES.PARALLEL) {
            return "⑂";
        }
        return "⚡";
    }

    function parseRouteExpression(source) {
        const normalized = String(source || "DIRECT").trim();
        if (normalized === "DIRECT") {
            return { kind: "direct" };
        }
        const match = normalized.match(
            /^outputs\.([A-Za-z][A-Za-z0-9_-]*)\s*==\s*"([^"]*)"$/,
        );
        return match
            ? {
                kind: "condition",
                outputKey: match[1],
                expectedValue: match[2],
            }
            : null;
    }

    function routeExpression(outputKey, expectedValue) {
        return `outputs.${outputKey} == "${expectedValue}"`;
    }

    function firstRouteOutput(task) {
        if (!task) {
            return null;
        }
        const outputs = Array.isArray(task.outputs) ? task.outputs : [];
        return (
            approvalDecisionOutput(outputs.filter(isStringOutput))
            || outputs.find(isStringOutput)
            || null
        );
    }

    function routeValueSelection(
        parent,
        currentTask,
        outputKey,
        currentValue,
    ) {
        const output = (parent?.outputs || []).find(
            (candidate) => candidate.key === outputKey,
        );
        const decisionOutput =
            output && approvalDecisionOutput([output]) === output;
        const siblingValues = (parent?.tasks || [])
            .filter((candidate) => candidate !== currentTask)
            .map((candidate) => parseRouteExpression(candidate.route))
            .filter(
                (route) =>
                    route?.kind === "condition"
                    && route.outputKey === outputKey,
            )
            .map((route) => route.expectedValue);
        const values = [];
        if (decisionOutput) {
            const decisions = approvalDecisionValues(output);
            values.push(decisions.approve, decisions.reject);
        }
        values.push(...siblingValues);
        if (currentValue) {
            values.push(currentValue);
        }
        return {
            controlled: Boolean(decisionOutput || siblingValues.length),
            values: [...new Set(values)],
        };
    }

    function isStringOutput(output) {
        return String(output?.type || "STRING").toUpperCase() === "STRING";
    }

    function isRouteMissed(node) {
        const execution = selectedExecution();
        const deployed = state.current?.deployedFlow;
        if (
            !execution
            || !deployed
            || execution.flowVersion !== deployed.reversion
            || !node.parentPath
            || taskRunForKey(node.task.key)
        ) {
            return false;
        }
        const currentParent = taskAtPath(node.parentPath);
        const deployedParent = deployedTaskByKey(currentParent?.key);
        const deployedChild = deployedTaskByKey(node.task.key);
        const parsed = parseRouteExpression(deployedChild?.route);
        if (parsed?.kind !== "condition" || !deployedParent) {
            return false;
        }
        const parentRun = taskRunForKey(deployedParent.key);
        if (
            !parentRun
            || !["SUCCESS", "WARNING"].includes(parentRun.state)
        ) {
            return false;
        }
        return !(
            typeof parentRun.outputs?.[parsed.outputKey] === "string"
            && parentRun.outputs[parsed.outputKey]
                === parsed.expectedValue
        );
    }

    function definitionTaskKeys() {
        const keys = new Set();
        const visit = (tasks) => {
            (Array.isArray(tasks) ? tasks : []).forEach((task) => {
                if (task?.key) {
                    keys.add(task.key);
                }
                if (task?.pause && typeof task.pause === "object") {
                    visit([task.pause]);
                }
                visit(task?.tasks);
            });
        };
        visit(state.definition?.tasks);
        return keys;
    }

    function uniqueTaskKey(base, reserved = new Set()) {
        const keys = definitionTaskKeys();
        reserved.forEach((key) => keys.add(key));
        if (!keys.has(base)) {
            return base;
        }
        let index = 2;
        while (keys.has(`${base}-${index}`)) {
            index++;
        }
        return `${base}-${index}`;
    }

    function uniqueDataKey(items, base) {
        const keys = new Set(
            (Array.isArray(items) ? items : [])
                .map((item) => item.key)
                .filter(Boolean),
        );
        if (!keys.has(base)) {
            return base;
        }
        let index = 2;
        while (keys.has(`${base}${index}`)) {
            index++;
        }
        return `${base}${index}`;
    }

    function allDeployedTasks() {
        const result = [];
        function visit(tasks) {
            (tasks || []).forEach((task) => {
                result.push(task);
                if (task.pause && typeof task.pause === "object") {
                    visit([task.pause]);
                }
                visit(task.tasks);
            });
        }
        visit(state.current?.deployedFlow?.tasks);
        return result;
    }

    function deployedTaskKey(taskId) {
        return allDeployedTasks().find((task) => task.id === taskId)?.key;
    }

    function deployedTaskByKey(key) {
        return allDeployedTasks().find((task) => task.key === key) || null;
    }

    function deployedTaskByRun(taskRunId) {
        const run = selectedExecution()?.taskRuns.find(
            (item) => item.id === taskRunId,
        );
        return run
            ? allDeployedTasks().find((task) => task.id === run.taskId)
            : null;
    }

    function taskRunForKey(key) {
        const execution = selectedExecution();
        if (!execution || !state.current?.deployedFlow) {
            return null;
        }
        const task = allDeployedTasks().find((item) => item.key === key);
        return task
            ? execution.taskRuns
                .filter((run) => run.taskId === task.id)
                .at(-1) || null
            : null;
    }

    async function replayExecution(execution) {
        const sequence = ++replaySequence;
        for (const run of execution.taskRuns) {
            if (sequence !== replaySequence) {
                return;
            }
            state.replayTaskRunId = run.id;
            render();
            await delay(420);
        }
        if (sequence === replaySequence) {
            state.replayTaskRunId = null;
            render();
        }
    }

    function schedulePoll() {
        clearTimeout(pollTimer);
        const execution = selectedExecution();
        const executionId = state.selectedExecutionId;
        if (
            !executionId ||
            (execution && !["CREATED", "RUNNING"].includes(execution.state))
        ) {
            return;
        }
        pollTimer = setTimeout(async () => {
            try {
                const refreshed = await api(
                    `/executions/${encodeURIComponent(executionId)}`,
                );
                mergeExecution(refreshed);
                render();
            } catch (error) {
                if (error.status !== 404) {
                    showToast(error.message, true);
                }
            }
            schedulePoll();
        }, 1200);
    }

    function confirmDiscardChanges() {
        return (
            !state.dirty ||
            window.confirm("当前草稿有未保存修改，确定放弃并继续？")
        );
    }

    function currentDefinitionKey() {
        return (
            state.definition?.key ||
            state.current?.deployedFlow?.key ||
            extractKey(state.raw) ||
            "未命名流程"
        );
    }

    function currentFlowKey() {
        return (
            state.current?.deployedFlow?.key ||
            state.current?.flowKey ||
            state.definition?.key ||
            extractKey(state.raw) ||
            null
        );
    }

    function draftName(draft) {
        if (
            draft.flowKey === state.current?.flowKey
            && state.definition?.key
        ) {
            return state.definition.key;
        }
        return (
            draft.flowKey ||
            draft.deployedFlow?.key ||
            extractKey(draft.raw) ||
            "未命名草稿"
        );
    }

    function extractKey(raw) {
        const match = String(raw || "").match(
            /^key:\s*(?:"([^"]*)"|'([^']*)'|([^\n#]+))/m,
        );
        return match
            ? (match[1] || match[2] || match[3] || "").trim()
            : "";
    }

    function stateLabel(value) {
        return STATE_LABELS[value] || value || "未知";
    }

    function formatTime(epochMillis) {
        if (!epochMillis) {
            return "—";
        }
        return new Intl.DateTimeFormat("zh-CN", {
            month: "2-digit",
            day: "2-digit",
            hour: "2-digit",
            minute: "2-digit",
            hour12: false,
        }).format(new Date(epochMillis));
    }

    function approvalTemplateInputs() {
        return [
            {
                key: "decision",
                type: "STRING",
                displayName: "审批决定",
                required: false,
            },
            {
                key: "comment",
                type: "STRING",
                displayName: "审批意见",
                required: false,
            },
        ];
    }

    function approvalDecisionOutput(outputs) {
        const candidates = [
            "decision",
            "approved",
            "approval",
            "result",
            "status",
        ];
        return candidates
            .map((key) =>
                (Array.isArray(outputs) ? outputs : []).find(
                    (output) =>
                        String(output.key || "").toLowerCase() === key,
                ),
            )
            .find(Boolean) || null;
    }

    function approvalDecisionValues(output) {
        const key = String(output.key || "").toLowerCase();
        const type = String(output.type || "STRING").toUpperCase();
        if (["BOOLEAN", "BOOL"].includes(type)) {
            return { approve: "true", reject: "false" };
        }
        if (key === "approved" || key === "approval") {
            return { approve: "yes", reject: "no" };
        }
        return { approve: "APPROVED", reject: "REJECTED" };
    }

    function approvalFieldLabel(key) {
        const labels = {
            comment: "审批意见",
            opinion: "审批意见",
            remark: "备注",
            reason: "原因",
            note: "说明",
            message: "说明",
        };
        return labels[String(key || "").toLowerCase()] || key;
    }

    function isLongTextOutput(key) {
        return [
            "comment",
            "opinion",
            "remark",
            "reason",
            "note",
            "message",
        ].includes(String(key || "").toLowerCase());
    }

    function defaultDefinition() {
        const suffix = state.drafts.length + 1;
        return {
            key: `flow-${suffix}`,
            description: "请描述这个流程解决的业务问题",
            inputs: [],
            outputs: [],
            tasks: [],
        };
    }

    function newTask(type) {
        const base = type === TASK_TYPES.PAUSE
            ? "approval-task"
            : type === TASK_TYPES.PARALLEL
                ? "parallel-task"
                : "log-task";
        const keys = definitionTaskKeys();
        let index = 1;
        while (keys.has(`${base}-${index}`)) {
            index++;
        }
        const key = `${base}-${index}`;
        return taskDefinition(type, key);
    }

    function taskDefinition(type, key) {
        const task = {
            key,
            type,
            inputs: [],
            outputs: [],
            route: "DIRECT",
            dependOn: [],
            tasks: [],
        };
        applyPluginDefaults(task, type);
        return task;
    }

    function stringifyYaml(value) {
        return yamlLines(value, 0).join("\n") + "\n";
    }

    function yamlLines(value, indent) {
        const prefix = " ".repeat(indent);
        if (Array.isArray(value)) {
            if (value.length === 0) {
                return [`${prefix}[]`];
            }
            const lines = [];
            value.forEach((item) => {
                if (isScalar(item)) {
                    lines.push(`${prefix}- ${yamlScalar(item)}`);
                    return;
                }
                const entries = Object.entries(item);
                if (entries.length === 0) {
                    lines.push(`${prefix}- {}`);
                    return;
                }
                const [firstKey, firstValue] = entries[0];
                if (isScalar(firstValue)) {
                    lines.push(
                        `${prefix}- ${firstKey}: ${yamlScalar(firstValue)}`,
                    );
                } else if (isEmptyCollection(firstValue)) {
                    lines.push(
                        `${prefix}- ${firstKey}: ${emptyCollection(firstValue)}`,
                    );
                } else {
                    lines.push(`${prefix}- ${firstKey}:`);
                    lines.push(...yamlLines(firstValue, indent + 4));
                }
                entries.slice(1).forEach(([key, nested]) => {
                    appendYamlProperty(lines, key, nested, indent + 2);
                });
            });
            return lines;
        }
        if (value && typeof value === "object") {
            const lines = [];
            Object.entries(value).forEach(([key, nested]) => {
                appendYamlProperty(lines, key, nested, indent);
            });
            return lines;
        }
        return [`${prefix}${yamlScalar(value)}`];
    }

    function appendYamlProperty(lines, key, value, indent) {
        const prefix = " ".repeat(indent);
        if (isScalar(value)) {
            lines.push(`${prefix}${key}: ${yamlScalar(value)}`);
        } else if (isEmptyCollection(value)) {
            lines.push(`${prefix}${key}: ${emptyCollection(value)}`);
        } else {
            lines.push(`${prefix}${key}:`);
            lines.push(...yamlLines(value, indent + 2));
        }
    }

    function isScalar(value) {
        return (
            value === null ||
            ["string", "number", "boolean"].includes(typeof value)
        );
    }

    function isEmptyCollection(value) {
        return (
            (Array.isArray(value) && value.length === 0) ||
            (value &&
                typeof value === "object" &&
                !Array.isArray(value) &&
                Object.keys(value).length === 0)
        );
    }

    function emptyCollection(value) {
        return Array.isArray(value) ? "[]" : "{}";
    }

    function yamlScalar(value) {
        if (value === null) {
            return "null";
        }
        if (typeof value === "number" || typeof value === "boolean") {
            return String(value);
        }
        return JSON.stringify(String(value));
    }

    function showToast(message, error = false) {
        clearTimeout(toastTimer);
        const root = document.querySelector("#toast-root");
        root.innerHTML = `
            <div class="toast ${error ? "error" : ""}">
                ${escapeHtml(message)}
            </div>
        `;
        toastTimer = setTimeout(() => {
            root.innerHTML = "";
        }, 4200);
    }

    function delay(milliseconds) {
        return new Promise((resolve) =>
            setTimeout(resolve, milliseconds),
        );
    }

    initialize();
})();
