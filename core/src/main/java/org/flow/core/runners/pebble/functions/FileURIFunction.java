package org.flow.core.runners.pebble.functions;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import org.flow.core.runners.LocalPath;
import org.flow.core.storages.StorageContext;

import io.pebbletemplates.pebble.template.EvaluationContext;
import jakarta.inject.Singleton;

@Singleton
public class FileURIFunction extends AbstractFileFunction {
    public static final String NAME = "fileURI";

    private static final String ERROR_MESSAGE = "The 'fileURI' function expects an argument 'path' that is an execution storage or local file URI.";

    @Override
    protected Object fileFunction(EvaluationContext context, URI path, String namespace, String tenantId, Map<String, Object> args) throws IOException {
        return switch (path.getScheme()) {
            case StorageContext.FLOW_SCHEME -> path.toString();
            case LocalPath.FILE_SCHEME -> path.toString();
            default -> throw new IllegalArgumentException(SCHEME_NOT_SUPPORTED_ERROR.formatted(path));
        };
    }

    @Override
    protected String getErrorMessage() {
        return ERROR_MESSAGE;
    }
}
