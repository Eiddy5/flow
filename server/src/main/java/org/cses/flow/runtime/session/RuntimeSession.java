package org.cses.flow.runtime.session;

import org.cses.flow.runtime.context.ResumeTarget;
import org.cses.flow.runtime.model.Activity;
import org.cses.flow.runtime.model.Process;
import org.cses.flow.runtime.model.Task;

public interface RuntimeSession {

    ResumeTarget loadResumeTarget(String taskId);

    void insert(Process process);

    void insert(Activity activity);

    void insert(Task task);

    void update(Process process);

    void update(Activity activity);

    void update(Task task);
}
