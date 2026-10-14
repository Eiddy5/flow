package org.flow.executor.testkit;

import java.util.ArrayList;
import java.util.List;

import org.flow.core.queues.BroadcastQueueInterface;
import org.flow.core.queues.event.BroadcastEvent;

public class RecordingBroadcastQueue<T extends BroadcastEvent> extends RecordingQueue<T> implements BroadcastQueueInterface<T> {
    public RecordingBroadcastQueue(String name) {
        this(name, new ArrayList<>());
    }

    public RecordingBroadcastQueue(String name, List<Trace.Emission> journal) {
        super(name, journal);
    }
}
