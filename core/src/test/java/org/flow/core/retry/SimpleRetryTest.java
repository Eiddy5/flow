package org.flow.core.retry;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SimpleRetryTest {
    @Test
    void stopsAtAttemptLimitWithExponentialDelays() {
        SimpleRetry retry = new SimpleRetry(3, 2, Duration.ofMillis(10), null, error -> true, Throwable.class, 0);
        for (long delay : new long[] {10, 20, 40}) {
            assertThat(retry.canRetry(new AssertionError("retryable"))).isTrue();
            assertThat(retry.nextDelay()).isEqualTo(delay);
        }
        assertThat(retry.canRetry(new AssertionError("exhausted"))).isFalse();
        assertThat(retry.getOverallDelay()).isEqualTo(Duration.ofMillis(70));
    }

    @Test
    void respectsDelayBudgetAndExceptionPredicate() {
        SimpleRetry retry = new SimpleRetry(5, 2, Duration.ofMillis(10), Duration.ofMillis(10),
            error -> error instanceof IllegalStateException, Throwable.class, 0);
        assertThat(retry.canRetry(new IllegalArgumentException())).isFalse();
        assertThat(retry.currentAttempt()).isZero();
        assertThat(retry.canRetry(new IllegalStateException())).isTrue();
        assertThat(retry.nextDelay()).isEqualTo(10);
        assertThat(retry.canRetry(new IllegalStateException())).isFalse();
    }
}
