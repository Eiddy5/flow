package org.flow.core.retry;

import java.nio.channels.AlreadyBoundException;

import org.junit.jupiter.api.Test;

import org.flow.core.annotations.Retryable;
import org.flow.core.junit.annotations.FlowTest;

import io.micronaut.retry.event.RetryEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@FlowTest
class OverrideRetryInterceptorTest {
    @Inject
    RetryEvents retryEvents;

    @Inject
    TestRetry retry;

    @Test
    void test() {
        assertThrows(AlreadyBoundException.class, retry::failedMethod);

        assertThat(retryEvents.count).isEqualTo(5);
    }

    @Singleton
    public static class TestRetry {
        @Retryable(delay = "10ms", multiplier = "2.0")
        public String failedMethod() {
            throw new AlreadyBoundException();
        }
    }

    @Singleton
    @Slf4j
    public static class RetryEvents {
        public int count = 0;

        @EventListener
        void onRetry(final RetryEvent event) {
            this.count++;
        }
    }
}