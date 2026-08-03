(() => {
    "use strict";

    // Three variants of the Flow Studio canvas, switchable via ?variant=.
    const variants = {
        A: {
            name: "语义容器（推荐）",
            title: "复杂流程压力测试：主链仍保持纵向",
            description:
                "29 个 Task、3 处并行、2 个子流程和 1 个条件路由；复杂度只在语义容器内部展开。",
        },
        B: {
            name: "网关流程图",
            title: "显式 Split / Join 网关",
            description:
                "采用接近 BPMN 的节点与网关表达，连线就是流程语义，适合复杂依赖和熟悉流程图的用户。",
        },
        C: {
            name: "阶段编排",
            title: "按阶段组织，不做自由画布",
            description:
                "将流程拆成可排序阶段；并行与子流程成为特殊阶段，结构稳定，长流程的编辑和扫描成本最低。",
        },
    };
    const order = Object.keys(variants);
    const icons = {
        flow:
            '<svg viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="6" rx="2"/><rect x="14" y="15" width="7" height="6" rx="2"/><path d="M10 6h4a3 3 0 0 1 3 3v6"/></svg>',
        pulse:
            '<svg viewBox="0 0 24 24"><path d="M3 12h4l2.2-6 4.1 12 2.1-6H21"/></svg>',
        plus:
            '<svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>',
        save:
            '<svg viewBox="0 0 24 24"><path d="M5 3h12l3 3v15H4V3Z"/><path d="M8 3v6h8V3M8 21v-7h8v7"/></svg>',
        play:
            '<svg viewBox="0 0 24 24"><path d="m8 5 11 7-11 7Z"/></svg>',
        rocket:
            '<svg viewBox="0 0 24 24"><path d="M14 5c3-3 6-3 6-3s0 3-3 6l-5 5-5-5Z"/><path d="m9 11-4 1-2 3 6 1M13 15l-1 4 3 2 2-6"/></svg>',
        search:
            '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="m16 16 5 5"/></svg>',
        fit:
            '<svg viewBox="0 0 24 24"><path d="M8 3H3v5M16 3h5v5M8 21H3v-5M16 21h5v-5"/></svg>',
        undo:
            '<svg viewBox="0 0 24 24"><path d="m9 7-5 5 5 5"/><path d="M5 12h9a5 5 0 0 1 5 5"/></svg>',
        redo:
            '<svg viewBox="0 0 24 24"><path d="m15 7 5 5-5 5"/><path d="M19 12h-9a5 5 0 0 0-5 5"/></svg>',
        chevron:
            '<svg viewBox="0 0 24 24"><path d="m8 10 4 4 4-4"/></svg>',
        more:
            '<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/></svg>',
        drag:
            '<svg viewBox="0 0 24 24"><circle cx="8" cy="7" r="1"/><circle cx="16" cy="7" r="1"/><circle cx="8" cy="12" r="1"/><circle cx="16" cy="12" r="1"/><circle cx="8" cy="17" r="1"/><circle cx="16" cy="17" r="1"/></svg>',
    };

    function icon(name) {
        return `<span class="icon">${icons[name] || icons.flow}</span>`;
    }

    function renderRail() {
        return `
            <aside class="rail">
                <div class="brand-mark">F</div>
                <button class="rail-button active" type="button" title="流程编排">
                    ${icon("flow")}
                </button>
                <button class="rail-button" type="button" title="运行事实">
                    ${icon("pulse")}
                </button>
                <div class="rail-spacer"></div>
                <button class="rail-button" type="button" title="帮助">?</button>
                <button class="rail-button" type="button" title="设置">⚙</button>
                <div class="avatar">FD</div>
            </aside>
        `;
    }

    function renderTopbar() {
        return `
            <header class="topbar">
                <div class="flow-heading">
                    <div class="crumbs">Flow Studio / flow-demo</div>
                    <div class="title-row">
                        <h1>企业授信、签约与服务开通</h1>
                        <span class="badge badge-live">已部署 R12</span>
                    </div>
                </div>
                <span class="save-state">✓ 草稿已保存</span>
                <button class="button button-quiet" type="button">
                    ${icon("save")} 保存
                </button>
                <button class="button button-primary" type="button">
                    ${icon("rocket")} 发布
                </button>
                <button class="button button-run" type="button">
                    ${icon("play")} 启动 R12
                </button>
            </header>
        `;
    }

    function renderFlowList() {
        return `
            <aside class="flow-list">
                <div class="panel-title">
                    <strong>流程草稿</strong>
                    <span>4</span>
                </div>
                <label class="search-box">
                    ${icon("search")}
                    <input value="" placeholder="搜索流程">
                </label>
                <div class="drafts">
                    <button class="draft active" type="button">
                        <span class="draft-name">
                            <strong>enterprise-credit-onboarding</strong>
                            <span class="badge badge-live">R12</span>
                        </span>
                        <small>v37 · 刚刚更新</small>
                    </button>
                    <button class="draft" type="button">
                        <span class="draft-name">
                            <strong>invoice-approval</strong>
                            <span class="badge badge-draft">草稿</span>
                        </span>
                        <small>v4 · 12 分钟前</small>
                    </button>
                    <button class="draft" type="button">
                        <span class="draft-name">
                            <strong>vendor-review</strong>
                            <span class="badge badge-live">R3</span>
                        </span>
                        <small>v9 · 昨天</small>
                    </button>
                </div>
                <div class="palette">
                    <div class="panel-title">
                        <strong>添加节点</strong>
                        <span>拖入画布</span>
                    </div>
                    <p class="palette-label">任务</p>
                    <div class="palette-grid">
                        <button type="button">
                            <span class="palette-icon auto">⚡</span>
                            <span><strong>自动任务</strong><small>AUTO</small></span>
                        </button>
                        <button type="button">
                            <span class="palette-icon pause">Ⅱ</span>
                            <span><strong>等待任务</strong><small>PAUSE</small></span>
                        </button>
                    </div>
                    <p class="palette-label">结构</p>
                    <div class="structure-list">
                        <button type="button">
                            <span class="structure-symbol serial"></span>
                            <span><strong>串行步骤</strong><small>沿主线继续</small></span>
                        </button>
                        <button type="button">
                            <span class="structure-symbol parallel">Ⅱ</span>
                            <span><strong>并行组</strong><small>同时开始，汇合后继续</small></span>
                        </button>
                        <button type="button">
                            <span class="structure-symbol route">◇</span>
                            <span><strong>条件分支</strong><small>根据输出选择路径</small></span>
                        </button>
                        <button type="button">
                            <span class="structure-symbol subflow">▣</span>
                            <span><strong>子流程</strong><small>封装一段独立流程</small></span>
                        </button>
                    </div>
                </div>
            </aside>
        `;
    }

    function renderCanvasHeader() {
        return `
            <div class="canvas-header">
                <div class="canvas-tabs">
                    <button class="active" type="button">画布</button>
                    <button type="button">YAML</button>
                    <button type="button">运行记录</button>
                </div>
                <div class="canvas-tools">
                    <button type="button" title="撤销">${icon("undo")}</button>
                    <button type="button" title="重做">${icon("redo")}</button>
                    <span class="tool-divider"></span>
                    <button type="button">−</button>
                    <span>86%</span>
                    <button type="button">＋</button>
                    <button type="button" title="适应画布">${icon("fit")}</button>
                </div>
            </div>
        `;
    }

    function simpleTask({
        key,
        title,
        type = "AUTO",
        meta = "",
        selected = false,
        tone = "",
        compact = false,
    }) {
        return `
            <article class="task-card ${selected ? "selected" : ""} ${tone} ${compact ? "compact" : ""}">
                <span class="task-handle">${icon("drag")}</span>
                <span class="task-icon ${type.toLowerCase()}">${type === "PAUSE" ? "Ⅱ" : "⚡"}</span>
                <span class="task-copy">
                    <strong>${title}</strong>
                    <small>${key} · ${type}${meta ? ` · ${meta}` : ""}</small>
                </span>
                <button class="task-menu" type="button" aria-label="更多操作">
                    ${icon("more")}
                </button>
            </article>
        `;
    }

    function addButton(label = "添加下一步") {
        return `
            <button class="inline-add" type="button">
                <span>＋</span>${label}
            </button>
        `;
    }

    // Baseline kept inside the throwaway prototype for visual comparison.
    function renderVariantABaseline() {
        return `
            <section class="design-variant variant-a" data-variant-panel="A">
                <div class="variant-summary">
                    <span class="variant-letter">A</span>
                    <span>
                        <strong>语义容器</strong>
                        <small>先识别主干，再识别成组结构</small>
                    </span>
                    <span class="recommended">推荐</span>
                </div>
                <div class="semantic-stage">
                    <div class="start-end start"><span></span>开始</div>
                    <div class="vertical-link"><span>串行</span></div>
                    ${simpleTask({
                        key: "receive-request",
                        title: "接收客户申请",
                        meta: "步骤 01",
                    })}
                    <div class="vertical-link"></div>
                    ${simpleTask({
                        key: "validate-profile",
                        title: "资料完整性校验",
                        meta: "步骤 02",
                    })}
                    <div class="vertical-link"></div>

                    <section class="parallel-container">
                        <header>
                            <span class="container-icon parallel">Ⅱ</span>
                            <span>
                                <strong>并行组 · 背景核验</strong>
                                <small>3 条分支同时开始，全部完成后继续</small>
                            </span>
                            <span class="semantic-chip parallel">PARALLEL</span>
                            <button type="button">${icon("chevron")}</button>
                        </header>
                        <div class="split-gateway">
                            <span></span>
                            <strong>同时开始</strong>
                            <span></span>
                        </div>
                        <div class="parallel-branches">
                            <div class="branch-lane">
                                <span class="branch-index">分支 1</span>
                                ${simpleTask({
                                    key: "credit-check",
                                    title: "企业征信核验",
                                    compact: true,
                                })}
                            </div>
                            <div class="branch-lane">
                                <span class="branch-index">分支 2</span>
                                ${simpleTask({
                                    key: "compliance-review",
                                    title: "合规人工复核",
                                    type: "PAUSE",
                                    compact: true,
                                })}
                            </div>
                            <div class="branch-lane">
                                <span class="branch-index">分支 3</span>
                                ${simpleTask({
                                    key: "risk-score",
                                    title: "风险评分",
                                    compact: true,
                                })}
                            </div>
                        </div>
                        <div class="join-gateway">
                            <span></span>
                            <strong>全部完成后汇合</strong>
                            <span></span>
                        </div>
                        ${addButton("添加并行分支")}
                    </section>

                    <div class="vertical-link"></div>
                    ${simpleTask({
                        key: "approve-customer",
                        title: "客户准入审批",
                        type: "PAUSE",
                        meta: "步骤 04",
                    })}
                    <div class="vertical-link"></div>

                    <section class="subflow-container">
                        <header>
                            <span class="container-icon subflow">▣</span>
                            <span>
                                <strong>子流程 · 合同签署</strong>
                                <small>独立输入 / 输出 · 展开显示 3 个内部步骤</small>
                            </span>
                            <span class="semantic-chip subflow">SUBFLOW</span>
                            <button type="button">${icon("chevron")}</button>
                        </header>
                        <div class="subflow-input">
                            <span>输入</span>
                            <code>customerId</code>
                            <code>contractTemplate</code>
                        </div>
                        <div class="nested-spine">
                            ${simpleTask({
                                key: "generate-contract",
                                title: "生成合同",
                                compact: true,
                            })}
                            <div class="mini-link"></div>
                            <div class="nested-parallel">
                                <span class="nested-parallel-label">并行复核</span>
                                ${simpleTask({
                                    key: "legal-review",
                                    title: "法务复核",
                                    type: "PAUSE",
                                    compact: true,
                                })}
                                ${simpleTask({
                                    key: "finance-review",
                                    title: "财务复核",
                                    type: "PAUSE",
                                    compact: true,
                                })}
                            </div>
                            <div class="mini-link"></div>
                            ${simpleTask({
                                key: "collect-signature",
                                title: "收集电子签名",
                                type: "PAUSE",
                                compact: true,
                                selected: true,
                            })}
                        </div>
                        <div class="subflow-output">
                            <span>输出</span>
                            <code>contractId</code>
                            <code>signedAt</code>
                        </div>
                        ${addButton("进入子流程编辑")}
                    </section>

                    <div class="vertical-link"></div>
                    ${simpleTask({
                        key: "activate-customer",
                        title: "开通客户账号并通知",
                        meta: "步骤 06",
                    })}
                    <div class="vertical-link"></div>
                    <div class="start-end end"><span></span>结束</div>
                    ${addButton()}
                </div>
            </section>
        `;
    }

    function renderVariantA() {
        return `
            <section class="design-variant variant-a" data-variant-panel="A">
                <div class="variant-summary">
                    <span class="variant-letter">A</span>
                    <span>
                        <strong>语义容器 · 复杂流程</strong>
                        <small>纵向主干 + 组合结构，最大嵌套深度 3 层</small>
                    </span>
                    <span class="recommended">压力测试</span>
                </div>
                <div class="semantic-stage">
                    <div class="complexity-strip">
                        <span><strong>29</strong><small>Task</small></span>
                        <span><strong>3</strong><small>并行结构</small></span>
                        <span><strong>2</strong><small>子流程</small></span>
                        <span><strong>1</strong><small>条件路由</small></span>
                        <span><strong>3 层</strong><small>最大深度</small></span>
                    </div>

                    <div class="start-end start"><span></span>开始</div>
                    <div class="vertical-link"><span>串行</span></div>
                    ${simpleTask({
                        key: "receive-credit-application",
                        title: "受理企业授信申请",
                        meta: "步骤 01",
                    })}
                    <div class="vertical-link"></div>
                    ${simpleTask({
                        key: "normalize-enterprise-profile",
                        title: "企业资料标准化与完整性校验",
                        meta: "步骤 02",
                    })}
                    <div class="vertical-link"></div>
                    ${simpleTask({
                        key: "duplicate-application-check",
                        title: "重复申请与关联关系检查",
                        meta: "步骤 03",
                    })}
                    <div class="vertical-link"></div>

                    <section class="parallel-container">
                        <header>
                            <span class="container-icon parallel">Ⅱ</span>
                            <span>
                                <strong>并行组 · 企业尽职调查</strong>
                                <small>
                                    4 条分支同时开始；每条分支内部按顺序执行，
                                    全部完成后汇合
                                </small>
                            </span>
                            <span class="semantic-chip parallel">PARALLEL</span>
                            <button type="button">${icon("chevron")}</button>
                        </header>
                        <div class="split-gateway">
                            <span></span>
                            <strong>同时开始</strong>
                            <span></span>
                        </div>
                        <div class="parallel-branches four">
                            <div class="branch-lane">
                                <span class="branch-index">分支 A · 主体穿透</span>
                                <div class="branch-stack">
                                    ${simpleTask({
                                        key: "registry-profile-fetch",
                                        title: "工商信息拉取",
                                        compact: true,
                                    })}
                                    <div class="branch-link"><span>顺序</span></div>
                                    ${simpleTask({
                                        key: "beneficial-owner-identification",
                                        title: "最终受益人识别",
                                        compact: true,
                                    })}
                                </div>
                            </div>
                            <div class="branch-lane">
                                <span class="branch-index">分支 B · 信用风险</span>
                                <div class="branch-stack">
                                    ${simpleTask({
                                        key: "enterprise-credit-report",
                                        title: "企业征信查询",
                                        compact: true,
                                    })}
                                    <div class="branch-link"><span>顺序</span></div>
                                    ${simpleTask({
                                        key: "credit-risk-score",
                                        title: "信用风险评分",
                                        compact: true,
                                    })}
                                </div>
                            </div>
                            <div class="branch-lane">
                                <span class="branch-index">分支 C · 财务能力</span>
                                <div class="branch-stack">
                                    ${simpleTask({
                                        key: "financial-statement-parse",
                                        title: "财报数据解析",
                                        compact: true,
                                    })}
                                    <div class="branch-link"><span>顺序</span></div>
                                    ${simpleTask({
                                        key: "financial-manual-review",
                                        title: "财务人工复核",
                                        type: "PAUSE",
                                        compact: true,
                                    })}
                                </div>
                            </div>
                            <div class="branch-lane">
                                <span class="branch-index">分支 D · KYC / AML</span>
                                <div class="branch-stack">
                                    ${simpleTask({
                                        key: "aml-sanction-screening",
                                        title: "制裁名单筛查",
                                        compact: true,
                                    })}
                                    <div class="branch-link"><span>顺序</span></div>
                                    ${simpleTask({
                                        key: "compliance-manual-review",
                                        title: "合规人工复核",
                                        type: "PAUSE",
                                        compact: true,
                                    })}
                                </div>
                            </div>
                        </div>
                        <div class="join-gateway">
                            <span></span>
                            <strong>8 个 Task 全部完成后汇合</strong>
                            <span></span>
                        </div>
                        ${addButton("添加并行分支")}
                    </section>

                    <div class="vertical-link"></div>
                    ${simpleTask({
                        key: "calculate-credit-profile",
                        title: "合并尽调结果并计算授信画像",
                        meta: "步骤 05",
                    })}
                    <div class="vertical-link"></div>

                    <section class="route-container">
                        <header>
                            <span class="container-icon route">◇</span>
                            <span>
                                <strong>条件路由 · 授信决策</strong>
                                <small>
                                    低、中风险回到主线；高风险发送拒绝通知后就地终止
                                </small>
                            </span>
                            <span class="semantic-chip route">ROUTE</span>
                            <button type="button">${icon("chevron")}</button>
                        </header>
                        <div class="route-source">
                            <span>判断字段</span>
                            <code>outputs.riskLevel</code>
                            <small>来自 calculate-credit-profile</small>
                        </div>
                        <div class="route-branches-a">
                            <div class="route-lane-a approved">
                                <div class="route-lane-head">
                                    <code>riskLevel = LOW</code>
                                    <span class="route-result continue">继续主流程</span>
                                </div>
                                ${simpleTask({
                                    key: "auto-credit-approval",
                                    title: "低风险自动授信",
                                    meta: "额度 ≤ 500 万",
                                    compact: true,
                                })}
                            </div>
                            <div class="route-lane-a review">
                                <div class="route-lane-head">
                                    <code>riskLevel = MEDIUM</code>
                                    <span class="route-result continue">继续主流程</span>
                                </div>
                                <article class="compact-subflow-card">
                                    <span class="task-icon subflow">▣</span>
                                    <span>
                                        <strong>子流程 · 授信委员会复核</strong>
                                        <small>材料复核 → 委员会表决</small>
                                    </span>
                                    <em>2 步</em>
                                </article>
                            </div>
                            <div class="route-lane-a rejected">
                                <div class="route-lane-head">
                                    <code>riskLevel = HIGH</code>
                                    <span class="route-result terminate">终止路径</span>
                                </div>
                                ${simpleTask({
                                    key: "reject-credit-application",
                                    title: "发送拒绝通知并归档",
                                    compact: true,
                                })}
                                <span class="local-end"><i></i>此路径结束</span>
                            </div>
                        </div>
                        <div class="route-join-a">
                            <span></span>
                            <strong>已批准路径汇入主流程</strong>
                            <span></span>
                        </div>
                    </section>

                    <div class="vertical-link"></div>
                    <section class="subflow-container">
                        <header>
                            <span class="container-icon subflow">▣</span>
                            <span>
                                <strong>子流程 · 合同生成、复核与签署</strong>
                                <small>
                                    独立输入 / 输出；内部包含一个三分支并行复核
                                </small>
                            </span>
                            <span class="semantic-chip subflow">SUBFLOW</span>
                            <button type="button">${icon("chevron")}</button>
                        </header>
                        <div class="subflow-input">
                            <span>输入</span>
                            <code>enterpriseId</code>
                            <code>approvedLimit</code>
                            <code>contractTemplate</code>
                        </div>
                        <div class="nested-spine">
                            ${simpleTask({
                                key: "generate-contract",
                                title: "生成授信与服务合同",
                                compact: true,
                            })}
                            <div class="mini-link"></div>
                            <div class="nested-parallel three">
                                <span class="nested-parallel-label">
                                    内嵌并行 · 三方全部通过后继续
                                </span>
                                ${simpleTask({
                                    key: "legal-review",
                                    title: "法务复核",
                                    type: "PAUSE",
                                    compact: true,
                                })}
                                ${simpleTask({
                                    key: "finance-review",
                                    title: "财务复核",
                                    type: "PAUSE",
                                    compact: true,
                                })}
                                ${simpleTask({
                                    key: "contract-compliance-review",
                                    title: "合规终审",
                                    type: "PAUSE",
                                    compact: true,
                                })}
                            </div>
                            <div class="mini-link"></div>
                            ${simpleTask({
                                key: "corporate-seal",
                                title: "内部用印",
                                type: "PAUSE",
                                compact: true,
                            })}
                            <div class="mini-link"></div>
                            ${simpleTask({
                                key: "collect-signature",
                                title: "收集客户电子签名",
                                type: "PAUSE",
                                compact: true,
                                selected: true,
                            })}
                        </div>
                        <div class="subflow-output">
                            <span>输出</span>
                            <code>contractId</code>
                            <code>signedAt</code>
                            <code>effectiveAt</code>
                        </div>
                        ${addButton("进入子流程编辑")}
                    </section>

                    <div class="vertical-link"></div>
                    <section class="parallel-container activation">
                        <header>
                            <span class="container-icon parallel">Ⅱ</span>
                            <span>
                                <strong>并行组 · 账户与服务开通</strong>
                                <small>
                                    合同生效后同时开通核心账户、业务系统和客户门户
                                </small>
                            </span>
                            <span class="semantic-chip parallel">PARALLEL</span>
                            <button type="button">${icon("chevron")}</button>
                        </header>
                        <div class="split-gateway">
                            <span></span>
                            <strong>合同生效后同时开始</strong>
                            <span></span>
                        </div>
                        <div class="parallel-branches">
                            <div class="branch-lane">
                                <span class="branch-index">分支 A · 核心账户</span>
                                <div class="branch-stack">
                                    ${simpleTask({
                                        key: "create-core-account",
                                        title: "创建核心账户",
                                        compact: true,
                                    })}
                                    <div class="branch-link"><span>顺序</span></div>
                                    ${simpleTask({
                                        key: "grant-account-permission",
                                        title: "授予账户权限",
                                        compact: true,
                                    })}
                                </div>
                            </div>
                            <div class="branch-lane">
                                <span class="branch-index">分支 B · 业务系统</span>
                                <div class="branch-stack">
                                    ${simpleTask({
                                        key: "sync-enterprise-crm",
                                        title: "同步 CRM 客户",
                                        compact: true,
                                    })}
                                    <div class="branch-link"><span>顺序</span></div>
                                    ${simpleTask({
                                        key: "create-service-ticket",
                                        title: "生成交付工单",
                                        compact: true,
                                    })}
                                </div>
                            </div>
                            <div class="branch-lane">
                                <span class="branch-index">分支 C · 客户触点</span>
                                <div class="branch-stack">
                                    ${simpleTask({
                                        key: "configure-notification",
                                        title: "配置通知策略",
                                        compact: true,
                                    })}
                                    <div class="branch-link"><span>顺序</span></div>
                                    ${simpleTask({
                                        key: "create-portal-workspace",
                                        title: "创建客户门户空间",
                                        compact: true,
                                    })}
                                </div>
                            </div>
                        </div>
                        <div class="join-gateway">
                            <span></span>
                            <strong>6 个 Task 全部完成后汇合</strong>
                            <span></span>
                        </div>
                        ${addButton("添加并行分支")}
                    </section>

                    <div class="vertical-link"></div>
                    ${simpleTask({
                        key: "verify-enterprise-activation",
                        title: "验证开通结果并发送欢迎通知",
                        meta: "步骤 09",
                    })}
                    <div class="vertical-link"></div>
                    <div class="start-end end"><span></span>结束</div>
                    ${addButton()}
                </div>
            </section>
        `;
    }

    function bNode(title, key, type = "AUTO", selected = false) {
        return `
            <article class="b-node ${selected ? "selected" : ""}">
                <span class="b-node-icon ${type.toLowerCase()}">${type === "PAUSE" ? "Ⅱ" : "⚡"}</span>
                <span><strong>${title}</strong><small>${key}</small></span>
                <em>${type}</em>
            </article>
        `;
    }

    function renderVariantB() {
        return `
            <section class="design-variant variant-b" data-variant-panel="B">
                <div class="variant-summary">
                    <span class="variant-letter">B</span>
                    <span>
                        <strong>网关流程图</strong>
                        <small>用节点、网关和连线精确表达执行关系</small>
                    </span>
                    <span class="power-user">专业</span>
                </div>
                <div class="bpmn-stage">
                    <div class="b-start-end start">开始</div>
                    <div class="b-connector"><span>顺序</span></div>
                    ${bNode("接收客户申请", "receive-request")}
                    <div class="b-connector"></div>
                    ${bNode("资料完整性校验", "validate-profile")}
                    <div class="b-connector blue"></div>
                    <div class="gateway parallel" title="并行拆分">
                        <span>＋</span>
                        <small>并行拆分</small>
                    </div>
                    <div class="gateway-fanout">
                        <span></span><span></span><span></span>
                    </div>
                    <div class="b-branches">
                        <div>
                            <span class="branch-caption">分支 A</span>
                            ${bNode("企业征信核验", "credit-check")}
                        </div>
                        <div>
                            <span class="branch-caption">分支 B</span>
                            ${bNode("合规人工复核", "compliance-review", "PAUSE")}
                        </div>
                        <div>
                            <span class="branch-caption">分支 C</span>
                            ${bNode("风险评分", "risk-score")}
                        </div>
                    </div>
                    <div class="gateway-fanin">
                        <span></span><span></span><span></span>
                    </div>
                    <div class="gateway parallel" title="并行汇合">
                        <span>＋</span>
                        <small>等待全部分支</small>
                    </div>
                    <div class="b-connector"></div>
                    ${bNode("客户准入审批", "approve-customer", "PAUSE")}
                    <div class="b-connector amber"></div>
                    <div class="gateway route" title="条件路由">
                        <span>◇</span>
                        <small>审批结果</small>
                    </div>
                    <div class="route-fanout">
                        <div class="route-path approve">
                            <span>decision = APPROVED</span>
                            <article class="b-subprocess selected">
                                <span class="subprocess-edge"></span>
                                <span class="b-node-icon subflow">▣</span>
                                <span>
                                    <strong>合同签署</strong>
                                    <small>contract-signing · 3 个步骤</small>
                                </span>
                                <em>＋</em>
                            </article>
                        </div>
                        <div class="route-path reject">
                            <span>decision = REJECTED</span>
                            ${bNode("发送驳回通知", "reject-notification")}
                        </div>
                    </div>
                    <div class="route-merge">
                        <span></span>
                        <small>任一路径完成后继续</small>
                    </div>
                    ${bNode("归档准入结果", "archive-result")}
                    <div class="b-connector"></div>
                    <div class="b-start-end end">结束</div>
                    <div class="b-legend">
                        <span><i class="solid"></i> 顺序</span>
                        <span><i class="blue"></i> 并行</span>
                        <span><i class="amber"></i> 条件</span>
                        <span><i class="double"></i> 子流程</span>
                    </div>
                </div>
            </section>
        `;
    }

    function phaseTask(index, title, key, type = "AUTO", tag = "") {
        return `
            <article class="phase-task">
                <span class="phase-drag">${icon("drag")}</span>
                <span class="step-number">${index}</span>
                <span class="task-icon ${type.toLowerCase()}">${type === "PAUSE" ? "Ⅱ" : "⚡"}</span>
                <span class="task-copy">
                    <strong>${title}</strong>
                    <small>${key}</small>
                </span>
                ${tag ? `<span class="row-tag">${tag}</span>` : ""}
                <em>${type}</em>
                <button type="button">${icon("more")}</button>
            </article>
        `;
    }

    function renderVariantC() {
        return `
            <section class="design-variant variant-c" data-variant-panel="C">
                <div class="variant-summary">
                    <span class="variant-letter">C</span>
                    <span>
                        <strong>阶段编排</strong>
                        <small>用可排序阶段替代无限画布</small>
                    </span>
                    <span class="stable-layout">长流程友好</span>
                </div>
                <div class="phase-stage">
                    <section class="phase-block serial">
                        <header>
                            <span class="phase-index">01</span>
                            <span>
                                <strong>接入与校验</strong>
                                <small>串行阶段 · 2 个步骤</small>
                            </span>
                            <span class="phase-type serial">SERIAL</span>
                            <button type="button">${icon("more")}</button>
                        </header>
                        <div class="phase-body">
                            ${phaseTask("1.1", "接收客户申请", "receive-request")}
                            <div class="row-link"></div>
                            ${phaseTask("1.2", "资料完整性校验", "validate-profile")}
                            ${addButton("添加串行步骤")}
                        </div>
                    </section>
                    <div class="phase-link"><span>完成后进入下一阶段</span></div>

                    <section class="phase-block parallel">
                        <header>
                            <span class="phase-index">02</span>
                            <span>
                                <strong>背景核验</strong>
                                <small>并行阶段 · 3 条分支 · 全部完成后继续</small>
                            </span>
                            <span class="phase-type parallel">PARALLEL</span>
                            <button type="button">${icon("more")}</button>
                        </header>
                        <div class="phase-body parallel-rows">
                            <div class="parallel-row">
                                <span class="branch-rail">分支 A</span>
                                ${phaseTask("A1", "企业征信核验", "credit-check", "AUTO", "系统")}
                            </div>
                            <div class="parallel-row">
                                <span class="branch-rail">分支 B</span>
                                ${phaseTask("B1", "合规人工复核", "compliance-review", "PAUSE", "合规组")}
                            </div>
                            <div class="parallel-row">
                                <span class="branch-rail">分支 C</span>
                                ${phaseTask("C1", "风险评分", "risk-score", "AUTO", "系统")}
                            </div>
                            <div class="parallel-summary">
                                <span>Ⅱ</span>
                                <strong>3 / 3 分支完成后，阶段 03 才会开始</strong>
                                <button type="button">＋ 添加分支</button>
                            </div>
                        </div>
                    </section>
                    <div class="phase-link"><span>汇合</span></div>

                    <section class="phase-block serial">
                        <header>
                            <span class="phase-index">03</span>
                            <span>
                                <strong>准入审批</strong>
                                <small>串行阶段 · 1 个等待任务</small>
                            </span>
                            <span class="phase-type serial">SERIAL</span>
                            <button type="button">${icon("more")}</button>
                        </header>
                        <div class="phase-body">
                            ${phaseTask("3.1", "客户准入审批", "approve-customer", "PAUSE", "业务负责人")}
                        </div>
                    </section>
                    <div class="phase-link"><span>decision = APPROVED</span></div>

                    <section class="phase-block subflow selected">
                        <header>
                            <span class="phase-index">04</span>
                            <span>
                                <strong>合同签署</strong>
                                <small>子流程阶段 · 独立输入 / 输出 · 3 个步骤</small>
                            </span>
                            <span class="phase-type subflow">SUBFLOW</span>
                            <button type="button">${icon("chevron")}</button>
                        </header>
                        <div class="phase-body subflow-preview">
                            <div class="subflow-port input">
                                <span>输入</span><code>customerId</code><code>contractTemplate</code>
                            </div>
                            <div class="subflow-steps">
                                <span><b>1</b> 生成合同</span>
                                <i></i>
                                <span><b>2</b> 法务 / 财务并行复核</span>
                                <i></i>
                                <span><b>3</b> 收集电子签名</span>
                            </div>
                            <div class="subflow-port output">
                                <span>输出</span><code>contractId</code><code>signedAt</code>
                            </div>
                            <button class="enter-subflow" type="button">进入子流程画布 →</button>
                        </div>
                    </section>
                    <div class="phase-link"><span>子流程完成</span></div>

                    <section class="phase-block serial compact">
                        <header>
                            <span class="phase-index">05</span>
                            <span>
                                <strong>开通与通知</strong>
                                <small>串行阶段 · 1 个步骤</small>
                            </span>
                            <span class="phase-type serial">SERIAL</span>
                            <button type="button">${icon("more")}</button>
                        </header>
                        <div class="phase-body">
                            ${phaseTask("5.1", "开通客户账号并通知", "activate-customer")}
                        </div>
                    </section>
                    ${addButton("添加下一阶段")}
                </div>
            </section>
        `;
    }

    function renderInspector() {
        return `
            <aside class="inspector">
                <div class="inspector-header">
                    <span>
                        <strong>节点属性</strong>
                        <small>已选择子流程</small>
                    </span>
                    <button type="button">${icon("more")}</button>
                </div>
                <div class="selection-preview">
                    <span class="selection-icon">▣</span>
                    <span>
                        <strong>合同生成、复核与签署</strong>
                        <small>SUBFLOW · contract-signing</small>
                    </span>
                </div>
                <label class="field">
                    <span>显示名称</span>
                    <input value="合同生成、复核与签署">
                </label>
                <label class="field">
                    <span>Task Key</span>
                    <input value="contract-signing">
                </label>
                <label class="field">
                    <span>执行方式</span>
                    <button class="select" type="button">
                        <span>子流程</span>${icon("chevron")}
                    </button>
                </label>
                <div class="field-group">
                    <div class="field-group-title">
                        <span>
                            <strong>输入映射</strong>
                            <small>3 项</small>
                        </span>
                        <button type="button">＋</button>
                    </div>
                    <div class="mapping-row">
                        <code>enterpriseId</code><span>←</span><code>flow.enterpriseId</code>
                    </div>
                    <div class="mapping-row">
                        <code>approvedLimit</code><span>←</span><code>credit.limit</code>
                    </div>
                    <div class="mapping-row">
                        <code>template</code><span>←</span><code>contractTemplate</code>
                    </div>
                </div>
                <div class="field-group">
                    <div class="field-group-title">
                        <span>
                            <strong>输出映射</strong>
                            <small>3 项</small>
                        </span>
                        <button type="button">＋</button>
                    </div>
                    <div class="mapping-row">
                        <code>contractId</code><span>→</span><code>contractId</code>
                    </div>
                    <div class="mapping-row">
                        <code>signedAt</code><span>→</span><code>signedAt</code>
                    </div>
                    <div class="mapping-row">
                        <code>effectiveAt</code><span>→</span><code>effectiveAt</code>
                    </div>
                </div>
                <div class="field-group">
                    <div class="field-group-title">
                        <span>
                            <strong>异常策略</strong>
                            <small>继承流程默认值</small>
                        </span>
                    </div>
                    <button class="select" type="button">
                        <span>失败后终止主流程</span>${icon("chevron")}
                    </button>
                </div>
                <div class="inspector-footer">
                    <button type="button">删除节点</button>
                    <span>修改自动保存到草稿</span>
                </div>
            </aside>
        `;
    }

    function render() {
        document.querySelector("#app").innerHTML = `
            <main class="shell">
                ${renderRail()}
                <section class="workspace">
                    ${renderTopbar()}
                    <div class="content">
                        ${renderFlowList()}
                        <section class="editor">
                            ${renderCanvasHeader()}
                            <div class="design-context">
                                <span class="context-kicker">当前方案</span>
                                <span>
                                    <strong id="design-title"></strong>
                                    <small id="design-description"></small>
                                </span>
                                <div class="semantic-legend">
                                    <span><i class="legend-line"></i>串行</span>
                                    <span><i class="legend-box parallel"></i>并行</span>
                                    <span><i class="legend-box subflow"></i>子流程</span>
                                </div>
                            </div>
                            <div class="canvas-scroll">
                                ${renderVariantA()}
                                ${renderVariantB()}
                                ${renderVariantC()}
                            </div>
                        </section>
                        ${renderInspector()}
                    </div>
                </section>
            </main>
        `;
    }

    function requestedVariant() {
        const key = new URLSearchParams(window.location.search)
            .get("variant")
            ?.toUpperCase();
        return variants[key] ? key : "A";
    }

    let current = requestedVariant();

    function setVariant(key, updateUrl = true) {
        if (!variants[key]) {
            return;
        }
        current = key;
        document.querySelectorAll("[data-variant-panel]").forEach((panel) => {
            panel.hidden = panel.dataset.variantPanel !== key;
        });
        document.querySelectorAll("[data-variant]").forEach((button) => {
            const active = button.dataset.variant === key;
            button.classList.toggle("active", active);
            button.setAttribute("aria-current", active ? "true" : "false");
        });
        document.querySelector("#variant-key").textContent = key;
        document.querySelector("#variant-name").textContent = variants[key].name;
        document.querySelector("#design-title").textContent = variants[key].title;
        document.querySelector("#design-description").textContent =
            variants[key].description;
        document.body.dataset.variant = key;

        if (updateUrl) {
            const url = new URL(window.location.href);
            url.searchParams.set("variant", key);
            window.history.replaceState({ variant: key }, "", url);
        }
        document.querySelector(".canvas-scroll").scrollTo(0, 0);
    }

    function cycle(offset) {
        const index = order.indexOf(current);
        setVariant(order[(index + offset + order.length) % order.length]);
    }

    function bindEvents() {
        document.querySelector("#previous-variant").addEventListener(
            "click",
            () => cycle(-1),
        );
        document.querySelector("#next-variant").addEventListener(
            "click",
            () => cycle(1),
        );
        document.querySelectorAll("[data-variant]").forEach((button) => {
            button.addEventListener("click", () => {
                setVariant(button.dataset.variant);
            });
        });
        window.addEventListener("keydown", (event) => {
            const target = event.target;
            if (
                target instanceof HTMLInputElement
                || target instanceof HTMLTextAreaElement
                || target instanceof HTMLSelectElement
                || target?.isContentEditable
            ) {
                return;
            }
            if (event.key === "ArrowLeft") {
                cycle(-1);
            } else if (event.key === "ArrowRight") {
                cycle(1);
            }
        });
        window.addEventListener("popstate", () => {
            setVariant(requestedVariant(), false);
        });
    }

    render();
    bindEvents();
    setVariant(current, false);
})();
