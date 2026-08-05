---
name: datapilot-aviator
description: |
  aviator, expression, evaluate, dynamic expression, compile, rule script,
  表达式, 规则脚本, AviatorExpression, datapilot, condition. Use when the user
  asks to "evaluate an expression", "run a rule", "filter records by formula",
  "computed field", "register custom function", or works with files under
  src/main/java/org/dataPilot/expression/aviator/**. Scope: Aviator expression
  compile/eval, VariableContainer population, AviatorExpressionCondition
  predicates, custom function registration, integration with datapilot-crud
  computed/virtual columns. Does NOT cover SQL filter parsing (see ElasticSearchParser).
argument-hint: "[expression-source]"
allowed-tools: Read Grep Glob
paths: "src/main/java/org/dataPilot/expression/aviator/**"
version: 0.1.0
---

# DataPilot Aviator Expression Skill

Compile and evaluate dynamic Aviator expressions against record contexts, use them as boolean conditions/filters, and wire them into datapilot-crud as computed or virtual fields.

## When to Use

- "评估一个动态表达式 / 计算公式"
- "用表达式做记录过滤 / 规则判断"
- "为 Model 增加一个计算字段（computed field）"
- "注册自定义 Aviator 函数"
- 编辑路径 `src/main/java/org/dataPilot/expression/aviator/**` 下的文件时

## Public API (FQN)

包：`org.dataPilot.expression.aviator`

### `org.dataPilot.expression.aviator.AviatorExpression`
```java
public class AviatorExpression {
    public AviatorExpression(String expression);            // 编译表达式
    public Object eval(VariableContainer vars);             // 用上下文求值
    public Object eval(Map<String, Object> env);            // 用 Map 求值
}
```

### `org.dataPilot.expression.aviator.AviatorExpressionCondition`
```java
public class AviatorExpressionCondition {
    public AviatorExpressionCondition(String expression);
    public boolean evaluate(VariableContainer vars);        // 布尔判定
}
```

### `org.dataPilot.expression.aviator.VariableContainer`
```java
public class VariableContainer {
    public void set(String name, Object value);
    public Object get(String name);
    public Map<String, Object> toMap();
}
```

## Supported Syntax

| 类别 | 运算符 / 方法 |
|---|---|
| 算术 | `+`  `-`  `*`  `/`  `%` |
| 比较 | `==`  `!=`  `<`  `>`  `<=`  `>=` |
| 逻辑 | `&&`  `\|\|`  `!` |
| 字符串 | `+` 拼接、`.contains()`、`.startsWith()`、`.endsWith()` |
| 集合 | `.length`、`.size()`、`list[i]` 索引 |
| 三元 | `cond ? a : b` |

自定义函数注册（底层走 Aviator `AviatorEvaluator.addFunction`）：
```java
import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.runtime.function.AbstractFunction;
import com.googlecode.aviator.runtime.function.FunctionUtils;
import com.googlecode.aviator.runtime.type.AviatorObject;

AviatorEvaluator.addFunction(new AbstractFunction() {
    @Override public String getName() { return "upper"; }
    @Override
    public AviatorObject call(Map<String, Object> env, AviatorObject arg1) {
        String s = FunctionUtils.getStringValue(arg1, env);
        return FunctionUtils.wrapReturn(s == null ? null : s.toUpperCase());
    }
});
```

## Workflow

1. 根据本文件确认 `AviatorExpression` / `VariableContainer` 契约
2. 复用全局编译缓存（见 Pitfalls），切勿在循环里 `new AviatorExpression(src)`
3. 求值前用 `VariableContainer.set` 显式注入所有引用变量
6. 与 datapilot-crud 集成时，在 Model 层暴露 computed getter，调用编译好的表达式

## Examples

### 1. 编译并求值简单算术表达式
```java
// 编译一次
AviatorExpression expr = new AviatorExpression("price * (1 - discount)");

VariableContainer vars = new VariableContainer();
vars.set("price", 100);
vars.set("discount", 0.1);

Object result = expr.eval(vars);   // 90.0
```

### 2. 针对 Model 记录求值（VariableContainer 由记录填充）
```java
// 假设 OrderModel 是 datapilot-crud 的实体
OrderModel order = orderService.findById(id);

VariableContainer vars = new VariableContainer();
vars.set("price",    order.getPrice());
vars.set("quantity", order.getQuantity());
vars.set("vipLevel", order.getCustomer().getVipLevel());

AviatorExpression total = new AviatorExpression(
    "price * quantity * (vipLevel >= 3 ? 0.85 : 1.0)"
);
BigDecimal amount = new BigDecimal(total.eval(vars).toString());
```

### 3. 用 AviatorExpressionCondition 做过滤谓词
```java
AviatorExpressionCondition cond = new AviatorExpressionCondition(
    "status == 'ACTIVE' && score >= 60"
);

List<UserModel> passed = users.stream().filter(u -> {
    VariableContainer v = new VariableContainer();
    v.set("status", u.getStatus());
    v.set("score",  u.getScore());
    return cond.evaluate(v);
}).toList();
```

### 4. 注册自定义函数并在表达式中调用
```java
AviatorEvaluator.addFunction(new AbstractFunction() {
    @Override public String getName() { return "daysBetween"; }
    @Override
    public AviatorObject call(Map<String,Object> env, AviatorObject a, AviatorObject b) {
        LocalDate d1 = (LocalDate) a.getValue(env);
        LocalDate d2 = (LocalDate) b.getValue(env);
        return FunctionUtils.wrapReturn(ChronoUnit.DAYS.between(d1, d2));
    }
});

AviatorExpression expr = new AviatorExpression(
    "daysBetween(startDate, endDate) > 30"
);
VariableContainer v = new VariableContainer();
v.set("startDate", LocalDate.of(2026, 1, 1));
v.set("endDate",   LocalDate.of(2026, 4, 7));
boolean longRange = (Boolean) expr.eval(v);   // true
```

### 5. 处理未解析变量 / 类型不匹配
```java
try {
    AviatorExpression expr = new AviatorExpression("price * qty");
    VariableContainer v = new VariableContainer();
    v.set("price", 10);
    // 故意不 set("qty", ...)
    Object r = expr.eval(v);
} catch (com.googlecode.aviator.exception.ExpressionRuntimeException e) {
    // 变量未注入：variable 'qty' not found
    log.warn("缺少变量: {}", e.getMessage());
} catch (ClassCastException e) {
    // 类型不匹配（如把字符串当数字相乘）
    log.warn("类型不兼容: {}", e.getMessage());
}
```

排查清单：
- 未解析变量 → 通过 `vars.toMap().keySet()` 校验所有引用名
- 类型错误 → 在 `set` 时显式转 `Number` / `String` / `Boolean`
- 编译期语法错 → 构造 `AviatorExpression` 时即抛 `ExpressionSyntaxErrorException`，应在启动期 fail-fast

## Integration with datapilot-crud

将 Aviator 表达式作为 **computed field / virtual column** 接入 Model：

1. 在 Model 元数据初始化阶段编译表达式一次，存入静态 `Map<String, AviatorExpression>`
2. 为字段定义 getter，内部构造 `VariableContainer`，从同一 Model 的其它字段取值
3. 持久化层不写该字段；查询投影时按需调用 getter
4. 若需在过滤条件中使用，包装为 `AviatorExpressionCondition`，在内存层做二次过滤（DB 层不下推）

```java
public class InvoiceModel {
    private static final AviatorExpression TOTAL_EXPR =
        new AviatorExpression("amount * (1 + taxRate)");

    private BigDecimal amount;
    private BigDecimal taxRate;

    // virtual column
    public BigDecimal getTotal() {
        VariableContainer v = new VariableContainer();
        v.set("amount",  amount);
        v.set("taxRate", taxRate);
        return new BigDecimal(TOTAL_EXPR.eval(v).toString());
    }
}
```

> 注意：虚拟列无法参与 datapilot-crud 的 SQL `WHERE` 下推，只能作为返回投影或内存过滤。

## Pitfalls

| 陷阱 | 说明 / 修正 |
|---|---|
| 重复编译 | `new AviatorExpression(src)` 代价高；务必缓存编译结果（按 src 字符串做 key 的 `ConcurrentHashMap`） |
| 线程安全 | 编译后的 `AviatorExpression` 实例求值线程安全；`VariableContainer` **非**线程安全，每次调用新建一个 |
| 自定义函数注册时机 | `AviatorEvaluator.addFunction` 是全局状态，必须在应用启动期注册一次，避免运行时反复添加 |
| 沙箱 | Aviator 默认允许反射/属性访问，处理用户输入表达式时应启用 `AviatorEvaluator` 的安全选项（禁用 `seq.*`、关闭属性反射），并对脚本来源做白名单 |
| 数值类型 | Aviator 内部用 `Long` / `Double` / `BigDecimal` 自动提升；与 `BigDecimal` 字段交互时，统一通过 `new BigDecimal(result.toString())` 转换避免精度丢失 |
| 未解析变量异常 | 默认抛 `ExpressionRuntimeException`；如希望返回 null，需在编译时配置 `Options.NIL_WHEN_PROPERTY_NOT_FOUND` |
| 与 crud SQL 下推 | 表达式仅在内存层生效，不要期望出现在 `WHERE` 子句里；需要 SQL 过滤请走 `ElasticSearchParser` |

## Checklist

- [ ] 已确认表达式入口和变量集合
- [ ] 入口符号 `AviatorExpression` / `AviatorExpressionCondition` / `VariableContainer` 已定位
- [ ] 编译结果已缓存复用
- [ ] 所有引用变量均已 `set`
- [ ] 自定义函数注册仅在启动期执行一次
- [ ] 用户来源脚本已做沙箱与白名单
- [ ] 引用全部使用类名/方法名，未出现行号

## Common Mistakes

| Mistake | Fix |
|---|---|
| 在循环内重新 `new AviatorExpression` | 启动期编译，运行期复用 |
| 共用同一个 `VariableContainer` 跨线程 | 每次调用新建 `VariableContainer` |
| 期望表达式条件下推到 SQL `WHERE` | Aviator 仅在内存层执行，需 SQL 过滤改用 ElasticSearchParser |
| 用 `==` 比较 `BigDecimal` | 转为 `Double` 或先 `.compareTo` 后再传入 |
| 未注入变量直接求值 | 求值前用 `vars.toMap().keySet()` 校验引用 |
