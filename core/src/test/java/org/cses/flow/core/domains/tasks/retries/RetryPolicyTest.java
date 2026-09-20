package org.cses.flow.core.domains.tasks.retries;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 重试计算的数值边界技术测试，不替代 UC 中的真实调用证据。 */
class RetryPolicyTest {
    /** 次数包含首次，时间预算包含边界且溢出不会形成过去的计划。 */
    @Test
    void constantHonorsAttemptAndDurationBoundaries() {
        AbstractRetry retry = ConstantRetry.create(3, 1000L, false, 500L);
        assertEquals(1500, retry.nextRetryAt(1, 1000, 1000).orElseThrow());
        assertEquals(2000, retry.nextRetryAt(2, 1000, 1500).orElseThrow());
        assertTrue(retry.nextRetryAt(3, 1000, 1500).isEmpty());
        assertTrue(retry.nextRetryAt(2, 1000, 1501).isEmpty());
        assertTrue(retry.permitsStart(1000, 2000));
        assertFalse(retry.permitsStart(1000, 2001));
        assertFalse(retry.permitsStart(1000, 999));
        assertTrue(ConstantRetry.create(2, null, false, 1L).nextRetryAt(1, 0, Long.MAX_VALUE).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> retry.nextRetryAt(0, 1000, 1000));
        assertThrows(IllegalArgumentException.class, () -> retry.nextRetryAt(1, 1000, 999));
    }
    /** 高次数指数不会溢出间隔上限。 */
    @Test
    void exponentialCapsEvenAtLargeAttemptCounts() {
        AbstractRetry retry = ExponentialRetry.create(Integer.MAX_VALUE, null, false, 1000L, 3000L, 2d);
        assertEquals(11000, retry.nextRetryAt(1, 10000, 10000).orElseThrow());
        assertEquals(12000, retry.nextRetryAt(2, 10000, 10000).orElseThrow());
        assertEquals(13000, retry.nextRetryAt(3, 10000, 10000).orElseThrow());
        assertEquals(13000, retry.nextRetryAt(100000, 10000, 10000).orElseThrow());
    }
    /** 随机区间只含左端点，非法边界在构造时拒绝。 */
    @Test
    void randomRangeAndConfigurationValidation() {
        AbstractRetry retry = RandomRetry.create(2, null, true, 0L, 1L);
        assertEquals(1000, retry.nextRetryAt(1, 1000, 1000).orElseThrow());
        assertTrue(retry.warningOnRetry());
        assertThrows(IllegalArgumentException.class, () -> ConstantRetry.create(0, null, false, 1L));
        assertThrows(IllegalArgumentException.class, () -> ConstantRetry.create(2, null, false, -1L));
        assertThrows(IllegalArgumentException.class, () -> ConstantRetry.create(2, 0L, false, 1L));
        assertThrows(IllegalArgumentException.class, () -> ExponentialRetry.create(2, null, false, 100L, 99L, 2d));
        assertThrows(IllegalArgumentException.class, () -> ExponentialRetry.create(2, null, false, 100L, 100L, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> RandomRetry.create(2, null, false, 1L, 1L));
    }
}
