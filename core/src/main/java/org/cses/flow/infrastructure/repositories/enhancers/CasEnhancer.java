package org.cses.flow.infrastructure.repositories.enhancers;

import jakarta.inject.Singleton;
import org.cses.flow.core.exceptions.WorkflowException;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.InsertSetMoreStep;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.ResultQuery;
import org.jooq.UpdateSetMoreStep;
import org.jooq.exception.DataAccessException;
import org.jooq.exception.DataChangedException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 对调用方构造的根语句增加 CAS；无领域依赖、连接所有权或请求状态。 */
@Singleton
public class CasEnhancer {
    /** 完整字段 Map 的参数名称，仅本增强器使用。 */
    private static final String FIELDS = "fields";
    /** 技术版本字段的参数名称，仅本增强器使用。 */
    private static final String LOCK = "lock";
    /**
     * 在构造根语句前复制 Entry 字段并移除版本列，避免字符串键与类型化字段重复写列。
     * @param fields 非空完整 Entry 字段 Map，不修改输入；显式 NULL 原样保留
     * @param lock 非空且由增强器独占写入的版本字段
     * @return 不包含版本列的新字段 Map，交给 BaseRepository 构造未执行语句
     * @throws NullPointerException 字段 Map 或版本元数据为空时抛出
     */
    public Map<String, Object> fields(Map<String, Object> fields, Field<Long> lock) {
        Map<String, Object> values = new LinkedHashMap<>(Objects.requireNonNull(fields, FIELDS));
        values.remove(Objects.requireNonNull(lock, LOCK).getName());
        return values;
    }

    /**
     * 将根插入增强为初始版本为零的只插入语句；身份冲突不覆盖已有记录。
     * @param statement 非空未执行插入，使用 fields(...) 处理后的完整字段构造，不包含版本列
     * @param companyId 非空租户字段
     * @param id 非空技术身份字段
     * @param lock 非空技术版本字段
     * @return 成功返回根身份与版本、身份冲突不返回行的未执行语句
     */
    public ResultQuery<Record2<String, Long>> save(InsertSetMoreStep<?> statement,
            Field<String> companyId, Field<String> id, Field<Long> lock) {
        return statement.set(lock, 0L).onConflict(companyId, id).doNothing().returningResult(id, lock);
    }

    /**
     * 将根更新增强为比较加载版本并原子递增版本的语句，不回退为插入。
     * @param statement 非空未执行更新，使用 fields(...) 处理后的根更新字段构造，不包含版本列
     * @param id 非空技术身份字段
     * @param lock 非空技术版本字段
     * @param identity 非空完整租户与根身份条件
     * @param expected 非负加载版本，不能使用保存前重新读取的版本
     * @return 成功返回根身份与新版本、版本冲突不返回行的未执行语句
     * @throws IllegalArgumentException 加载版本为负数时抛出
     * @throws DataChangedException 版本已达 Long 最大值时抛出
     */
    public ResultQuery<Record2<String, Long>> save(UpdateSetMoreStep<?> statement,
            Field<String> id, Field<Long> lock, Condition identity, long expected) {
        if (expected < 0) throw new IllegalArgumentException(LOCK + " must not be negative");
        if (expected == Long.MAX_VALUE) throw conflict();
        return statement.set(lock, lock.add(1L)).where(identity.and(lock.eq(expected)))
                .returningResult(id, lock);
    }

    /**
     * 执行业务实现已经组装完成的原子 SQL，并返回可回填版本；不修改领域对象。
     * @param statement 包含根、所属子记录及 outbox 的完整未执行 SQL，成功返回一行根版本
     * @return SQL 成功返回的新版本；外层事务回滚后调用方必须丢弃已回填对象并重载
     * @throws DataChangedException 根写入未成功返回版本时抛出
     * @throws WorkflowException 数据库拒绝整条 SQL 时抛出
     */
    public long save(ResultQuery<Record1<Long>> statement) {
        try {
            Long version = statement.fetchOne(0, Long.class);
            if (version == null) throw conflict();
            return version;
        } catch (DataChangedException conflict) {
            throw conflict;
        } catch (DataAccessException failure) {
            throw new WorkflowException("聚合保存失败", failure);
        }
    }

    /**
     * 构造可被现有冲突处理识别的异常，不读取数据库或暴露技术版本。
     * @return 新的版本冲突异常
     */
    public DataChangedException conflict() {
        return new DataChangedException("数据已发生变化，请刷新后重试。");
    }
}
