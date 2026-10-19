package org.flow.queue.jdbc.queue;

import org.flow.queue.AbstractBroadcastQueueTest;
import org.flow.queue.AbstractDispatchQueueTest;
import org.flow.queue.AbstractKeyedDispatchQueueTest;
import org.flow.queue.AbstractQueueCacheTest;
import org.flow.queue.AbstractVNodeDispatchQueueTest;
import org.flow.queue.QueueBean;
import org.flow.queue.jdbc.JdbcBroadcastQueue;
import org.flow.queue.jdbc.JdbcDependencies;
import org.flow.queue.jdbc.JdbcDispatchQueue;
import org.flow.queue.jdbc.JdbcKeyedDispatchQueue;
import org.flow.queue.jdbc.JdbcVNodeDispatchQueue;

import org.flow.core.queues.BroadcastQueueInterface;
import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.queues.KeyedDispatchQueueInterface;
import org.flow.core.queues.VNodeDispatchQueueInterface;
import org.flow.queue.*;
import org.flow.queue.jdbc.*;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;

@Factory
@Requires(classes = AbstractDispatchQueueTest.class)
public class JdbcTestQueueFactory {

    @QueueBean
    public BroadcastQueueInterface<AbstractBroadcastQueueTest.TestBroadcast> broadCastQueue(JdbcDependencies dependencies) {
        return new JdbcBroadcastQueue<>(
            AbstractBroadcastQueueTest.TestBroadcast.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    public DispatchQueueInterface<AbstractDispatchQueueTest.TestDispatch> dispatchQueue(JdbcDependencies dependencies) {
        return new JdbcDispatchQueue<>(
            AbstractDispatchQueueTest.TestDispatch.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    public KeyedDispatchQueueInterface<AbstractKeyedDispatchQueueTest.TestKeyedDispatch> keyDispatchQueue(JdbcDependencies dependencies) {
        return new JdbcKeyedDispatchQueue<>(
            AbstractKeyedDispatchQueueTest.TestKeyedDispatch.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    public VNodeDispatchQueueInterface<AbstractVNodeDispatchQueueTest.TestVNodeDispatchDispatch> vNodeDispatchQueue(JdbcDependencies dependencies) {
        return new JdbcVNodeDispatchQueue<>(
            AbstractVNodeDispatchQueueTest.TestVNodeDispatchDispatch.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(),
            dependencies.metricRegistry(), dependencies.ignoreExecutionService()
        );
    }

    @QueueBean
    public BroadcastQueueInterface<AbstractQueueCacheTest.DeletableBroadcastTestEvent> deletableBroadcastTestEventDispatchQueue(JdbcDependencies dependencies) {
        return new JdbcBroadcastQueue<>(
            AbstractQueueCacheTest.DeletableBroadcastTestEvent.class, dependencies.queueService(), dependencies.jdbcQueueClient(), dependencies.executorsUtils(), dependencies.metricRegistry(),
            dependencies.ignoreExecutionService()
        );
    }
}
