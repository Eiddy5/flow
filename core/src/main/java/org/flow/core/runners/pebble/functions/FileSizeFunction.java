package org.flow.core.runners.pebble.functions;

import java.io.IOException;
import java.net.URI;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

import org.flow.core.runners.LocalPath;
import org.flow.core.storages.FileAttributes;
import org.flow.core.storages.StorageContext;

import io.pebbletemplates.pebble.template.EvaluationContext;
import jakarta.inject.Singleton;

@Singleton
public class FileSizeFunction extends AbstractFileFunction {
    public static final String NAME = "fileSize";
    private static final String ERROR_MESSAGE = "The 'fileSize' function expects an argument 'path' that is a path to the internal storage URI.";

    @Override
    protected Object fileFunction(EvaluationContext context, URI path, String namespace, String tenantId, Map<String, Object> args) throws IOException {
        return switch (path.getScheme()) {
            case StorageContext.FLOW_SCHEME -> {
                FileAttributes fileAttributes = storageInterface.get().getAttributes(tenantId, namespace, path);
                yield fileAttributes.getSize();
            }
            case LocalPath.FILE_SCHEME -> {
                BasicFileAttributes fileAttributes = localPathFactory.get().createLocalPath().getAttributes(path);
                yield fileAttributes.size();
            }
            default -> throw new IllegalArgumentException(SCHEME_NOT_SUPPORTED_ERROR.formatted(path));
        };
    }

    @Override
    protected String getErrorMessage() {
        return ERROR_MESSAGE;
    }
}
