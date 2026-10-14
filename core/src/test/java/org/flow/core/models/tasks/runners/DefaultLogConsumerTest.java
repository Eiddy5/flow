package org.flow.core.models.tasks.runners;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.flow.core.context.TestRunContextFactory;
import org.flow.core.junit.annotations.FlowTest;
import org.flow.core.utils.IdUtils;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@FlowTest
class DefaultLogConsumerTest {
    @Inject
    private TestRunContextFactory runContextFactory;

    @Test
    void markerLinesAreSwallowedAndNotCounted() {
        var runContext = runContextFactory.of("id", "namespace", IdUtils.create());
        var consumer = new DefaultLogConsumer(runContext);

        consumer.accept("##flow:log:debug##", false);
        assertThat(consumer.getStdOutCount()).isZero();
        assertThat(consumer.getStdErrCount()).isZero();

        consumer.accept("##flow:log:info##", false);
        assertThat(consumer.getStdOutCount()).isZero();
        assertThat(consumer.getStdErrCount()).isZero();
    }

    @Test
    void regularLinesAreCounted() {
        var runContext = runContextFactory.of("id", "namespace", IdUtils.create());
        var consumer = new DefaultLogConsumer(runContext);

        consumer.accept("##flow:log:debug##", false);
        consumer.accept("some before-command output", false);
        consumer.accept("##flow:log:info##", false);
        consumer.accept("main output", false);

        assertThat(consumer.getStdOutCount()).isEqualTo(2);
        assertThat(consumer.getStdErrCount()).isZero();
    }

    @Test
    void stderrLinesAreCountedEvenInDebugMode() {
        var runContext = runContextFactory.of("id", "namespace", IdUtils.create());
        var consumer = new DefaultLogConsumer(runContext);

        consumer.accept("##flow:log:debug##", false);
        consumer.accept("stderr during beforeCommands", true);

        assertThat(consumer.getStdErrCount()).isEqualTo(1);
        assertThat(consumer.getStdOutCount()).isZero();
    }

    @Test
    void linesInDebugModeAreLoggedAtDebugLevel() {
        // Given
        var runContext = runContextFactory.of("id", "namespace", IdUtils.create());
        var consumer = new DefaultLogConsumer(runContext);

        var listAppender = new ListAppender<ILoggingEvent>();
        listAppender.start();
        var logger = (ch.qos.logback.classic.Logger) runContext.logger();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        logger.addAppender(listAppender);

        // When
        consumer.accept("##flow:log:debug##", false);
        consumer.accept("before-command output", false);
        consumer.accept("##flow:log:info##", false);
        consumer.accept("main output", false);

        // Then
        List<ILoggingEvent> events = listAppender.list;
        assertThat(events).anySatisfy(e ->
        {
            assertThat(e.getFormattedMessage()).isEqualTo("before-command output");
            assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.DEBUG);
        });
        assertThat(events).anySatisfy(e ->
        {
            assertThat(e.getFormattedMessage()).isEqualTo("main output");
            assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
        });
    }
}
