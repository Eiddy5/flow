package org.flow.core.executor.command;

import com.fasterxml.jackson.annotation.JsonCreator;

import org.flow.core.utils.Enums;

public enum ExecutionCommandType {
    SESSION_COMMAND,
    CREATE,
    REPLAY,
    CHANGE_TASK_RUN_STATE,
    FORCE_RUN,
    PAUSE,
    RESTART,
    RESUME,
    RESUME_FROM_BREAKPOINT,
    UNQUEUE,
    UPDATE_LABELS,
    UPDATE_STATUS,
    // ERROR
    INVALID;

    @JsonCreator
    static ExecutionCommandType from(final String s) {
        return Enums.getForNameIgnoreCase(s, ExecutionCommandType.class, INVALID);
    }
}
