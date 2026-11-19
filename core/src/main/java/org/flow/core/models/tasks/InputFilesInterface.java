package org.flow.core.models.tasks;

import java.util.Map;

import org.flow.plugin.api.annotations.PluginProperty;

import io.swagger.v3.oas.annotations.media.Schema;

public interface InputFilesInterface {
    @Schema(
        title = "The files to create on the working. It can be a map or a JSON object.",
        description = """
            Each file can be defined:
            - Inline with its content
            - As a URI, supported schemes are `flow` for internal storage files and `file` for host local files.
            """,
        oneOf = { Map.class, String.class }
    )
    @PluginProperty(dynamic = true)
    Object getInputFiles();
}
