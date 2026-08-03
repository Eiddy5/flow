# Flow Studio 画布设计原型

本目录是一次性设计原型，不是生产代码。它回答的问题是：

> 自上而下的流程编排画布，应该怎样同时清楚表达串行、并行和子流程？

三种结构方案通过同一路径的 `?variant=` 参数切换：

- `A`：语义容器（推荐），当前使用 29 个 Task 的复杂企业授信流程做压力测试
- `B`：网关流程图
- `C`：阶段编排

静态预览位于：

- `previews/variant-a.png`
- `previews/variant-a-complex.png`
- `previews/variant-b.png`
- `previews/variant-c.png`

在仓库根目录运行：

```bash
python3 -m http.server 4173 --directory .prototype/flow-studio
```

然后访问 `http://127.0.0.1:4173/?variant=A`。页面底部按钮或键盘左右方向键可切换
方案。确认方案后，应把选中的设计重新实现到正式 Flow Demo 页面，并删除或归档
本原型，不能直接将原型代码作为生产实现。
