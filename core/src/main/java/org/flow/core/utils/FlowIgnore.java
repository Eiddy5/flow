package org.flow.core.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import nl.basjes.gitignore.GitIgnore;

public class FlowIgnore {
    public static final String FLOW_IGNORE_FILE_NAME = ".flowignore";

    private GitIgnore gitIgnore;
    private Path rootFolderPath;

    public FlowIgnore(Path rootFolderPath) throws IOException {
        this.rootFolderPath = rootFolderPath;
        File flowIgnoreFile = rootFolderPath.resolve(FLOW_IGNORE_FILE_NAME).toFile();
        gitIgnore = flowIgnoreFile.exists()
            ? new GitIgnore(flowIgnoreFile)
            : new GitIgnore("");
    }

    public boolean isIgnoredFile(String path, boolean ignoreFlowIgnoreFile) {
        if (path.equals(this.rootFolderPath.resolve(FLOW_IGNORE_FILE_NAME).toString()) && ignoreFlowIgnoreFile) {
            return true;
        }

        return Boolean.TRUE.equals(gitIgnore.isIgnoredFile(path));
    }
}