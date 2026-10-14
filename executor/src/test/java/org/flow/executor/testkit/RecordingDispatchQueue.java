package org.flow.executor.testkit;

import java.util.ArrayList;
import java.util.List;

import org.flow.core.queues.DispatchQueueInterface;
import org.flow.core.queues.event.DispatchEvent;

public class RecordingDispatchQueue<T extends DispatchEvent> extends RecordingQueue<T> implements DispatchQueueInterface<T> {
    public RecordingDispatchQueue(String name) {
        this(name, new ArrayList<>());
    }

    public RecordingDispatchQueue(String name, List<Trace.Emission> journal) {
        super(name, journal);
    }
}
