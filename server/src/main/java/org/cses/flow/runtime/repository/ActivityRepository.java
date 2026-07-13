package org.cses.flow.runtime.repository;

import java.util.List;
import java.util.Optional;
import org.cses.flow.runtime.model.Activity;

public interface ActivityRepository {

    Activity save(Activity activity);

    Optional<Activity> findById(String activityId);

    List<Activity> findByProcessId(String processId);
}
