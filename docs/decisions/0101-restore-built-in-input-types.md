# ADR 0101：撤回宿主 Input 扩展并恢复内置类型

## 状态

Accepted（2026-09-14）。用户明确撤回 Input 扩展需求，恢复固定内置类型。
取代 ADR 0090、0093 的宿主 Input 扩展和编译期索引决策。

## 背景

Input 扩展引入了独立处理器、宿主构建接入和运行时索引装配。
宿主自定义 Input 的需求已撤回，不再需要这些发现机制。

## 备选方案

1. 继续维护自定义处理器：服务于已撤回的需求。
2. 改用 Task 的 @Plugin 发现业务 Input：仍保留已撤回的扩展能力。
3. 恢复固定内置类型表：采用。

## 决策

- InputTypes 仅列出 STRING、BOOLEAN、BYTE、SHORT、INTEGER、LONG、FLOAT、DOUBLE、CHARACTER。
- 删除 processor 模块、annotationProcessor 依赖及 Shadow 索引合并配置。
- 注册中心不再接受外部 Input 类集合，也不读取 classpath 索引。
- JSON/YAML、PAAS JSON、目录和 Schema 共用固定内置类型表。
  保留现有内置类型目录和查询；Task 的 @Plugin 发现方式不变。
- 类型协议只使用上述短代码，保留原有大小写和首尾空白解析规则，拒绝自定义名称和完整类名。
- 保留 ADR 0088 的字段绑定和 ADR 0089 的创建即校验，不恢复无参/Setter 半成品。
- Maven 发布仅包含 server、core、gen，不再发布或要求宿主配置 processor。

## 理由

固定类型表即可覆盖已确认需求，无需扫描、生成索引或维护业务类型别名。
内置类型既有校验、持久化和目录行为继续使用，不受扩展撤回影响。

## 后果

曾使用自定义 Input 的定义不再支持读取或发布；本次不增加兼容或迁移路径。
验证覆盖固定类型、未知类型拒绝、JSON/YAML/PAAS 绑定、持久化及 UC-12 内置输入流程。
