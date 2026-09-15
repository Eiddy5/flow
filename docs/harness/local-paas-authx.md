# Flow 本地 PAAS / AuthX 联调

## 构建

需要 JDK 25，以及 Flow 的三个同级仓库：`paas`、`paas-service`、`pass-auth`。
最后一个仓库的 Gradle 根项目名为 `pass-authx`，坐标为
`org.pass:pass-authx:0.1.0-SNAPSHOT`。

Flow 的 `gradle.properties` 默认启用：

```properties
useLocalPaas=true
useLocalPaasService=true
useLocalPassAuthx=true
localPaasPath=../paas
localPaasServicePath=../paas-service
localPassAuthxPath=../pass-auth
```

路径可使用 `-P` 覆盖；非同级工作树推荐绝对路径。开启后路径不存在会报错。

```bash
./gradlew :core:compileJava :server:compileJava
./gradlew :core:dependencyInsight --dependency paas-session --configuration compileClasspath
./gradlew :server:dependencyInsight --dependency pass-authx --configuration runtimeClasspath
./gradlew :server:dependencyInsight --dependency paas-service-sms --configuration runtimeClasspath
./gradlew :server:test
```

关闭本地替换以检查发布依赖：

```bash
./gradlew -PuseLocalPaas=false -PuseLocalPaasService=false -PuseLocalPassAuthx=false \
  :server:dependencies --configuration runtimeClasspath
```

关闭只切换来源，不提供旧 API 兼容；发布依赖必须包含新的 SessionUser 契约及 AuthX。
2026-09-15 实测关闭后 `org.pass:pass-authx:0.1.0-SNAPSHOT` 在当前 Maven 仓库中
不存在，依赖报告显示 `FAILED`（即使报告任务本身退出成功）。因此发布模式尚不可用，
需先发布对应认证包；本地模式已通过编译与 Server 测试。

## CSES 嵌入运行

从 CSES 的 Gradle Wrapper 运行，显式传递三个开关，避免各项目
`gradle.properties` 的默认值不同；路径统一使用同一套绝对路径。

```bash
./gradlew -PuseLocalFlow=true -PuseLocalPaas=true -PuseLocalPaasService=true \
  -PuseLocalPassAuthx=true :server:compileJava
```

独立 Flow Server 通过 `:server:run` 启动；嵌入方式由 CSES 启动 ApplicationContext。
两者都使用 AuthX 工厂和 PAAS Binder，不注入固定用户。

## 验证边界

- `AuthxSessionContractTest` 使用真实 AuthxSession/AuthxUser 类型验证租户提取、
  Core 命令与领域创建、无公司拒绝、异步恢复及响应过滤器运行时依赖。工厂测试隔离账号查询，
  不代表真实账号认证成功。
- UC-01/02 验证公开 Core Service、真实 PostgreSQL 与 Pulsar 的既有业务链路；
  不能代替 HTTP 登录和权限验收。
- 真实联调应使用已经配置好的 AuthX 宿主环境，完成登录、选择公司、查询
  `/api/session`、保存与发布 Flow、启动执行，再验证未选公司和跨公司资源访问。
- AuthX 默认 DataPilot 数据源键是 `postgresql.cses`。独立环境必须提供相应数据源
  与认证表，或通过 AuthX 的数据源提供器配置真实认证库；不能把默认 Flow 库
  当成已经初始化的认证库。
- 不要使用业务 Pulsar 验证测试 fixture；它会清理测试订阅。
