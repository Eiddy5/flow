# AOT 配置资源与 XML 解析器验证

## 已确认的故障与修复

2026-10-12 使用同级 PAAS 联合构建、JDK 25 验证：

- Micronaut 5.0.4.2 将宿主 `application.yml` 和依赖 JAR 中的 `application.yaml`
  视为重复全局配置资源。JIT / Native AOT 分析均在环境初始化阶段失败，
  `convertYamlToJava=false` 不能绕过该检查。
- PAAS Diagnostic 的管理页面默认值改由 `@ContextConfigurer` 注册，移除模块内
  全局配置资源，运行时和 AOT 均可发现。PropertySource 按顺序升序处理，后者
  覆盖前者；默认源使用 `Integer.MIN_VALUE`，宿主 YAML、环境变量和显式属性可覆盖。
- SMS SDK 的 XML 依赖链传递引入 `pull-parser:pull-parser:2`，其 JAXP SPI 接管
  SAXParserFactory，却不支持 Logback 使用的 `external-general-entities` 特性。
  Flow 共享构建约定排除这一个模块，使 JDK 解析器正常加载 `logback.xml`。

不要通过放宽所有配置资源的重复策略、关闭 AOT、删除 Diagnostic 依赖或关闭 XML
安全特性来规避这两类故障。Diagnostic 包仍需要提供可被宿主覆盖的管理页默认值。

## 可重复检查

从 Flow wrapper 运行，按本机配置设置 JDK 25。测试前先按
[本地 PAAS / AuthX 联调](local-paas-authx.md) 准备联合构建。纯技术验证可以禁用
Consul / 配置中心；这些开关不能代替真实服务或 UC 的环境配置。

```bash
JAVA_HOME=/path/to/jdk-25 \
MICRONAUT_CONFIG_CLIENT_ENABLED=false \
CONSUL_CLIENT_ENABLED=false \
CONSUL_CLIENT_CONFIG_ENABLED=false \
CONSUL_CLIENT_REGISTRATION_ENABLED=false \
CONSUL_CLIENT_WATCH_SERVICE_ENABLED=false \
./gradlew :paas:paas-diagnostic:test --tests '*DiagnosticDefaultsTest' \
  :server:test --tests '*LogbackXmlCompatibilityTest' \
  --no-parallel --max-workers=1

./gradlew :server:dependencyInsight --dependency pull-parser --configuration runtimeClasspath

JAVA_HOME=/path/to/jdk-25 \
MICRONAUT_CONFIG_CLIENT_ENABLED=false \
CONSUL_CLIENT_ENABLED=false \
CONSUL_CLIENT_CONFIG_ENABLED=false \
CONSUL_CLIENT_REGISTRATION_ENABLED=false \
CONSUL_CLIENT_WATCH_SERVICE_ENABLED=false \
./gradlew :server:prepareJitOptimizations :server:prepareNativeOptimizations \
  --no-parallel --max-workers=1
```

默认 Gradle 堆在联合构建中若出现 `garbage collector is thrashing`，可仅在当前命令
增加 `'-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m'`；本次未修改项目堆配置。

必要断言：Diagnostic 无全局 application YAML，JAR 中含
`META-INF/micronaut/io.micronaut.context.ApplicationContextConfigurer/` 注册；默认值
和宿主覆盖测试均通过；运行依赖查不到 pull-parser；实际 Logback XML 加载无 ERROR；
两项 AOT 准备均成功。只看普通编译成功不足以证明 AOT 已修复。

## 本轮证据与边界

本轮证据位于忽略目录 `build/failure-repair/20261012/`：

- `original-red.log`：原 State 失败及两项 AOT 重复资源失败。
- `regression-red.log`、`xml-red.log`：修复前的历史校验、默认配置及 XML 回归失败。
- `technical-green-recheck.log`：Core 27、Diagnostic 2、Server 6 项均通过。
- `aot-green.log`：两项 AOT 准备均实际执行成功，运行依赖查不到 pull-parser。

技术报告 XML 按阶段隔离，完整 UC 验证及报告由 Test Agent 生成。AOT 准备不等于
GraalVM native binary 构建或部署；本次未执行这些步骤，也未验证真实短信调用。
PAAS 修复位于同级仓库，发布依赖模式需使用包含该修复的 Diagnostic 包；Flow 的
本地验证不表示修复已发布至 Maven，宿主自身的依赖策略也需要排除不兼容 XML SPI。
该 PAAS 修复的本地提交为 `c827bf1`（`pass-2.0-neworg`）。

Micronaut 的 AOT 配置入口说明：
[ApplicationContextConfigurer](https://micronaut-projects.github.io/micronaut-aot/latest/guide/#_the_role_of_the_application_context_configurers)。
