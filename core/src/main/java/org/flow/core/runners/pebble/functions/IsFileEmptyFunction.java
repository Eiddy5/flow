package org.flow.core.runners.pebble.functions;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;

import org.flow.core.runners.LocalPath;
import org.flow.core.storages.StorageContext;

import io.pebbletemplates.pebble.template.EvaluationContext;
import jakarta.inject.Singleton;

@Singleton
public class IsFileEmptyFunction extends AbstractFileFunction {
    public static final String NAME = "isFileEmpty";
    private static final String ERROR_MESSAGE = "The 'isFileEmpty' function expects an argument 'path' that is an execution storage or local file URI.";

    @Override
    protected Object fileFunction(EvaluationContext context, URI path, String namespace, String tenantId, Map<String, Object> args) throws IOException {
        return switch (path.getScheme()) {
            case StorageContext.FLOW_SCHEME -> {
                try (InputStream inputStream = storageInterface.get().get(tenantId, namespace, path)) {
                    byte[] buffer = new byte[1];
                    yield inputStream.read(buffer, 0, 1) <= 0;
                }
            }
            case LocalPath.FILE_SCHEME -> {
                try (InputStream inputStream = localPathFactory.get().createLocalPath().get(path)) {
                    byte[] buffer = new byte[1];
                    yield inputStream.read(buffer, 0, 1) <= 0;
                }
            }
            default -> throw new IllegalArgumentException(SCHEME_NOT_SUPPORTED_ERROR.formatted(path));
        };
    }

    @Override
    protected String getErrorMessage() {
        return ERROR_MESSAGE;
    }
}
