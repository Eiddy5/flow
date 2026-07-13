package org.cses.flow.infrastructure.memory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.repository.ActivityRepository;

public final class InMemoryActivityRepository implements ActivityRepository {

    private final ConcurrentMap<String, Activity> activities = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> insertionOrder = new CopyOnWriteArrayList<>();

    @Override
    public Activity save(Activity activity) {
        if (activities.put(activity.id(), activity) == null) {
            insertionOrder.add(activity.id());
        }
        return activity;
    }

    @Override
    public Optional<Activity> findById(String activityId) {
        return Optional.ofNullable(activities.get(activityId));
    }

    @Override
    public List<Activity> findByProcessId(String processId) {
        return insertionOrder.stream()
                .map(activities::get)
                .filter(activity -> activity.processId().equals(processId))
                .toList();
    }
}
