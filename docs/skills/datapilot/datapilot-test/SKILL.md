---
name: datapilot-test
description: |
  测试规范与执行指南。Use when the user asks to "run test", "test coverage",
  "测试规范", "单元测试", "集成测试", "how to test", "test command", "执行测试",
  "测试覆盖", "write test case", "测试用例", or works with files under
  src/test/**. Scope: 单元测试、集成测试、Docker 环境依赖、Gradle 测试任务。
argument-hint: "[test-category|test-name|package]"
allowed-tools: Read Grep Glob
paths: "src/test/**,build.gradle,settings.gradle,gradle.properties"
version: 0.1.0
---

# DataPilot 测试规范与执行指南

面向测试工程师与开发者的测试体系完整指南，涵盖单元测试（UnitTest）、集成测试（IntegrationTest）的组织结构、执行命令、环境依赖与覆盖检查。

## When to Use

- "运行单元测试" / "run unit test"
- "执行集成测试需要什么环境" / "how to run integration test"
- "帮我写一个测试用例" / "write a test case for X"
- "测试覆盖率怎么查" / "check test coverage"
- "集成测试失败怎么排查" / "integration test debugging"
- "测试规范是什么" / "what is the test specification"
- 编辑路径 `src/test/**` 下的测试文件时

## Test Tiers Overview

| Tier | 标签 | 环境依赖 | 执行命令 | 典型耗时 |
|------|------|----------|----------|----------|
| **UnitTest** | `@Tag("UnitTest")` | 无（纯 JVM） | `./gradlew test` | < 30s |
| **IntegrationTest** | `@Tag("IntegrationTest")` | Docker（Postgres + Redis + Pulsar） | `./gradlew integrationTest` | 2-5min |
| **SmokeTest** | `@Tag("SmokeTest")` | Docker | `./gradlew smokeTest` | < 1min |

## Test Organization

### 目录结构

```
src/test/
├── java/org/dataPilot/
│   ├── cache/                      # Tier 1 单元测试（@Tag UnitTest）
│   │   ├── CacheConditionFlagTest.java
│   │   ├── DefaultLocalCacheFieldInvalidateTest.java
│   │   ├── CacheInvalidateConsumerTest.java
│   │   └── DefaultServerCacheManagerAggregationTest.java
│   │
│   ├── testsupport/
│   │   ├── UnitTest.java          # 空标签接口（标记用）
│   │   ├── IntegrationTest.java   # 空标签接口
│   │   └── SmokeTest.java
│   │
│   └── test/
│       └── cache/
│           └── QueryCacheInvalidationTest.java  # Tier 1 端到端测试
│
├── unit/                           # Tier 1 测试（同 @Tag UnitTest）
│   ├── cache/
│   │   ├── CacheCoreTest.java
│   │   ├── TenantAwareKeyTest.java
│   │   └── QueryCacheInvalidationTest.java
│   ├── handler/
│   │   ├── DataQueryTest.java
│   │   ├── DataUpdaterTest.java
│   │   └── ... (20+ 个 handler 测试)
│   ├── db/
│   ├── filter/
│   ├── aviator/
│   └── ...
│
└── integration/                    # Tier 2 测试（@Tag IntegrationTest）
    ├── cache/
    │   └── CacheIntegrationTest.java
    ├── lock/
    ├── collaboration/
    └── ...
```

### 测试类命名规范

| 后缀 | 含义 | 示例 |
|------|------|------|
| `*Test.java` | JUnit 5 测试类 | `CacheConditionFlagTest.java` |
| `*IT.java` | Integration Test（可选） | `CacheIntegrationIT.java` |
| `@Tag("UnitTest")` | 标记单元测试 | `@Tag("UnitTest")` |
| `@Tag("IntegrationTest")` | 标记集成测试 | `@Tag("IntegrationTest")` |

## Gradle Test Tasks

| Task | 说明 | 前置条件 |
|------|------|----------|
| `test` | 执行全部 `@Tag("UnitTest")` | 无 |
| `integrationTest` | 执行全部 `@Tag("IntegrationTest")` | Docker 服务运行 |
| `smokeTest` | 冒烟测试（快速验证） | Docker 服务运行 |
| `compileUnitTestJava` | 编译单元测试代码 | 无 |
| `compileIntegrationTestJava` | 编译集成测试代码 | 无 |

### Gradle 配置

```groovy
// build.gradle 测试配置
test {
    useJUnitPlatform()
    tags 'UnitTest'  // 默认只跑单元测试
    maxParallelForks = 4
    forkEvery = 100
}

tasks.withType(Test).configureEach {
    systemProperty 'org.gradle.jvmargs', '-Xmx2g'
}
```

## Unit Test Execution

### 基本命令

```bash
# 执行全部单元测试
./gradlew test

# 执行指定测试类
./gradlew test --tests "org.dataPilot.cache.CacheConditionFlagTest"

# 执行指定包
./gradlew test --tests "org.dataPilot.cache.*"

# 执行包含某名称的测试
./gradlew test --tests "*Cache*Test"

# 并行执行
./gradlew test --parallel

# 查看测试报告
open build/reports/tests/test/index.html
```

### 本地 Gradle 缓存（避免污染全局）

```bash
# 使用项目本地 .gradle-local 目录
GRADLE_USER_HOME=$PWD/.gradle-local ./gradlew test

# 清理本地 Gradle 缓存
rm -rf .gradle-local
```

### 编译验证

```bash
# 仅编译单元测试代码（不执行）
GRADLE_USER_HOME=$PWD/.gradle-local ./gradlew compileUnitTestJava

# 查看编译错误
GRADLE_USER_HOME=$PWD/.gradle-local ./gradlew compileUnitTestJava 2>&1 | grep "错误:"
```

## Integration Test Execution

### Docker 环境依赖

集成测试依赖以下 Docker 服务：

| 服务 | 端口 | 用途 | 必需性 |
|------|------|------|--------|
| `postgres` | 5432 | 主数据库 | 必须 |
| `redis` | 6379 | 分布式缓存 | 必须 |
| `pulsar` | 6650/8080 | 消息队列 | 部分测试必须 |

#### 启动 Docker 环境

```bash
# 启动所有必需服务
docker compose up postgres redis pulsar -d

# 仅启动数据库和缓存（部分集成测试足够）
docker compose up postgres redis -d

# 等待服务就绪
sleep 10

# 验证服务
docker compose ps
```

#### 停止环境

```bash
docker compose down
```

### 执行命令

```bash
# 编译集成测试代码
GRADLE_USER_HOME=$PWD/.gradle-local ./gradlew compileIntegrationTestJava

# 执行全部集成测试
GRADLE_USER_HOME=$PWD/.gradle-local ./gradlew integrationTest

# 执行指定集成测试类
GRADLE_USER_HOME=$PWD/.gradle-local ./gradlew integrationTest \
  --tests "org.dataPilot.test.cache.CacheIntegrationTest"

# 执行指定包
GRADLE_USER_HOME=$PWD/.gradle-local ./gradlew integrationTest \
  --tests "org.dataPilot.integration.cache.*"

# 查看集成测试报告
open build/reports/tests/integrationTest/index.html
```

### 常见问题排查

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| `Connection refused: postgres` | Docker 未启动 | `docker compose up postgres -d` |
| `Connection refused: redis` | Redis 未启动 | `docker compose up redis -d` |
| `Connection refused: pulsar` | Pulsar 未启动 | `docker compose up pulsar -d` |
| 测试挂起不返回 | Docker 服务未就绪 | `sleep 15` 后重试 |
| 编译错误 `cannot find symbol` | 缺少 import | 检查 import 语句 |

## Writing Test Cases

### 单元测试模板

```java
package org.dataPilot.xxx;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;

@DisplayName("功能模块单元测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("UnitTest")
class XxxServiceTest {

    private XxxService service;

    @BeforeEach
    void setUp() {
        service = new XxxService();
    }

    @Test
    @Order(1)
    @DisplayName("正常场景：输入 X 应返回 Y")
    void testNormalCase() {
        // given
        var input = createInput();

        // when
        var result = service.execute(input);

        // then
        assertNotNull(result);
        assertEquals("expected", result.getValue());
    }

    @Test
    @Order(2)
    @DisplayName("异常场景：空输入应抛异常")
    void testNullInput() {
        assertThrows(IllegalArgumentException.class, () -> service.execute(null));
    }
}
```

### 集成测试模板

```java
package org.dataPilot.integration.xxx;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DisplayName("功能模块集成测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("IntegrationTest")
@Testcontainers
class XxxIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test");

    @BeforeAll
    static void setUp() {
        // 初始化测试数据
    }

    @Test
    @Order(1)
    @DisplayName("端到端：完整流程验证")
    void testEndToEnd() {
        // given
        var request = createRequest();

        // when
        var response = executeRequest(request);

        // then
        assertTrue(response.isSuccess());
    }
}
```

### 使用 `@Nested` 组织测试

```java
@Nested
@DisplayName("XXX 功能测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class XxxFeatureTest {

    @Test
    @Order(1)
    @DisplayName("场景 1：基础功能")
    void testBasicFeature() { }

    @Test
    @Order(2)
    @DisplayName("场景 2：边界条件")
    void testEdgeCase() { }

    @Nested
    @DisplayName("错误处理")
    class ErrorHandlingTest {

        @Test
        @Order(1)
        @DisplayName("空输入应抛出 IllegalArgumentException")
        void testNullInput() {
            assertThrows(IllegalArgumentException.class, () -> service.execute(null));
        }
    }
}
```

## Test Coverage

### 查看覆盖率报告

```bash
# 生成覆盖率报告（需要 jacoco 插件）
./gradlew test jacocoTestReport

# 查看 HTML 报告
open build/reports/jacoco/test/html/index.html
```

### 关键覆盖指标

| 模块 | 覆盖目标 | 说明 |
|------|----------|------|
| `cache/` | invalidateByFields、trimConditionFlags、Aggregation | 字段级失效核心逻辑 |
| `handler/` | DataQuery、DataUpdater、DataDeleter | CRUD 处理器 |
| `filter/` | QueryCondition translate | 查询条件翻译 |
| `operator/` | NumberOperator、ArrayOperator | 运算符 |

## Common Test Patterns

### 1. Mock 对象（使用 Mockito）

```java
import org.mockito.Mockito;
import static org.mockito.Mockito.*;

@Test
void testWithMock() {
    Repository repo = mock(Repository.class);
    when(repo.findById(1)).thenReturn(createModel("1"));

    Service service = new Service(repo);
    var result = service.getById(1);

    assertNotNull(result);
    verify(repo).findById(1);
}
```

### 2. 反射注入（测试私有方法）

```java
private void injectConditionFlag(DefaultLocalCache cache, String key, CacheConditionFlag flag) {
    try {
        var field = DefaultLocalCache.class.getDeclaredField("conditionFlags");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, CacheConditionFlag> conditionFlags =
            (Map<String, CacheConditionFlag>) field.get(cache);
        conditionFlags.put(key, flag);
    } catch (Exception e) {
        throw new RuntimeException("注入 conditionFlag 失败", e);
    }
}
```

### 3. 时间测试（使用 Clock 或 TestClock）

```java
@Test
void testTtlExpiry() {
    // 修改 createAt 为过去时间
    var createAtField = CacheConditionFlag.class.getDeclaredField("createAt");
    createAtField.setAccessible(true);
    createAtField.setLong(flag, System.currentTimeMillis() - 31 * 60 * 1000L);

    // 验证过期逻辑
    cache.periodicTrim(null);
    assertNull(conditionFlags.get("key1"));
}
```

### 4. 并发测试

```java
@Test
void testConcurrentAccess() {
    AtomicBoolean error = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(2);

    Thread t1 = new Thread(() -> {
        for (int i = 0; i < 100; i++) cache.put("key", value);
        latch.countDown();
    });

    Thread t2 = new Thread(() -> {
        for (int i = 0; i < 100; i++) cache.get("key");
        latch.countDown();
    });

    t1.start();
    t2.start();
    latch.await();

    assertFalse(error.get());
}
```

## Test Checklist

### 提交前检查

- [ ] `./gradlew test` 全部通过（无 skipped / failed）
- [ ] `./gradlew compileUnitTestJava` 无编译错误
- [ ] 新增测试覆盖了核心路径（happy path + 边界条件）
- [ ] 测试名称清晰描述场景（`testXxx_ShouldYyy` 格式）
- [ ] 无硬编码时间依赖（使用 `TestClock` 或 `Clock.fixed`）
- [ ] 并发测试验证了线程安全
- [ ] 集成测试有 Docker 依赖说明注释

### 集成测试额外检查

- [ ] `docker compose up postgres redis pulsar -d` 成功
- [ ] `docker compose ps` 显示所有服务 Running
- [ ] 测试报告 `build/reports/tests/integrationTest/` 无 Error
- [ ] 测试间无顺序依赖（除非显式使用 `@Order`）

## Examples

### Example 1: 运行 cache 模块单元测试

```bash
# 进入项目目录
cd /path/to/cloud-datapilot

# 设置本地 Gradle 缓存
export GRADLE_USER_HOME=$PWD/.gradle-local

# 编译验证
./gradlew compileUnitTestJava 2>&1 | grep -E "(错误:|BUILD)"

# 执行 cache 模块单元测试
./gradlew test --tests "org.dataPilot.cache.*"

# 查看报告
open build/reports/tests/test/index.html
```

### Example 2: 调试失败的集成测试

```bash
# 1. 检查 Docker 状态
docker compose ps

# 2. 如果未运行，启动
docker compose up postgres redis -d
sleep 15

# 3. 单独运行失败的测试类
./gradlew integrationTest \
  --tests "org.dataPilot.test.cache.CacheIntegrationTest" \
  --info 2>&1 | tail -50

# 4. 查看详细日志
open build/reports/tests/integrationTest/classes/...CacheIntegrationTest.html
```

### Example 3: 编写字段级失效测试

```java
@Test
@Order(1)
@DisplayName("更新字段与缓存条件有交集 → 失效")
void testFieldIntersection() {
    // 注册 conditionFlag：依赖 {status, type}
    CacheConditionFlag flag = new CacheConditionFlag(Set.of("status", "type"));
    injectConditionFlag(cache, "filter:status=1", flag);
    flag.validate();

    // 更新 status 字段 → 触发失效
    cache.invalidateByFields(Set.of("status"));

    assertFalse(flag.isValid(), "更新字段与条件有交集，应失效");
}
```

## Common Mistakes

| Mistake | Fix |
|--------|-----|
| 集成测试不加 `@Tag("IntegrationTest")` | 确保 gradle 任务正确筛选 |
| 硬编码时间导致测试不稳定 | 使用 `Clock` 注入或反射修改时间 |
| 测试间有隐藏依赖未用 `@Order` | 明确排序或拆分测试 |
| `docker compose` 未启动就跑集成测试 | 添加前置条件注释 `@BeforeAll` |
| 测试名称含糊（`test1`） | 使用描述性名称 `testUpdateStatus_ShouldInvalidateCache` |
| 未清理测试数据导致互相污染 | `@BeforeEach` 清理或使用独立表/键 |

## Related Skills

| 相关 Skill | 用途 |
|-----------|------|
| [datapilot-cache](../datapilot-cache/SKILL.md) | 缓存相关功能测试（字段级失效、trimConditionFlags） |
| [datapilot-filter](../datapilot-filter/SKILL.md) | 查询条件翻译测试 |
| [datapilot-crud](../datapilot-crud/SKILL.md) | CRUD 操作测试 |

## Workflow

1. **确定测试类型**：单元测试（纯 JVM）还是集成测试（需要 Docker）
2. **确认目录位置**：`src/test/java/org/dataPilot/xxx/` 或 `src/test/unit/xxx/`
3. **编写测试类**：使用 `@Tag` 标记，按 `@Nested` 组织测试场景
4. **执行验证**：先 `compileUnitTestJava`，再 `./gradlew test`
5. **提交前检查**：完成 `Test Checklist` 所有项目
