package org.flow.core.runners.pebble.functions;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

import org.flow.core.runners.LocalPath;
import org.flow.core.storages.Namespace;
import org.flow.core.storages.NamespaceFile;
import org.flow.core.storages.StorageContext;

import io.pebbletemplates.pebble.template.EvaluationContext;
import jakarta.inject.Singleton;

@Singleton
public class FileExistsFunction extends AbstractFileFunction {
    public static final String NAME = "fileExists";
    private static final String ERROR_MESSAGE = "The 'fileExists' function expects an argument 'path' that is a path to the internal storage URI.";

    @Override
    protected Object fileFunction(EvaluationContext context, URI path, String namespace, String tenantId, Map<String, Object> args) throws IOException {
        return switch (path.getScheme()) {
            case StorageContext.FLOW_SCHEME -> storageInterface.get().exists(tenantId, namespace, path);
            case LocalPath.FILE_SCHEME -> localPathFactory.get().createLocalPath().exists(path);
            case Namespace.NAMESPACE_FILE_SCHEME -> {
                Namespace namespaceStorage = namespaceFactory.get().of(tenantId, namespace);
                yield namespaceStorage.exists(NamespaceFile.normalize(Path.of(path.getPath())));
            }
            default -> throw new IllegalArgumentException(SCHEME_NOT_SUPPORTED_ERROR.formatted(path));
        };
    }

    @Override
    protected String getErrorMessage() {
        return ERROR_MESSAGE;
    }
}
