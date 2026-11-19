package org.flow.core.runners.pebble.functions;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.amazon.ion.util.IonStreamUtils;

import org.flow.core.runners.LocalPath;
import org.flow.core.storages.StorageContext;

import io.pebbletemplates.pebble.template.EvaluationContext;
import jakarta.inject.Singleton;

@Singleton
public class ReadFileFunction extends AbstractFileFunction {
    public static final String NAME = "read";

    private static final String ERROR_MESSAGE = "The 'read' function expects an argument 'path' that is an execution storage or local file URI.";

    @Override
    protected Object fileFunction(EvaluationContext context, URI path, String namespace, String tenantId, Map<String, Object> args) throws IOException {
        return switch (path.getScheme()) {
            case StorageContext.FLOW_SCHEME -> {
                try (InputStream inputStream = storageInterface.get().get(tenantId, namespace, path)) {
                    yield readContent(inputStream);
                }
            }
            case LocalPath.FILE_SCHEME -> {
                try (InputStream inputStream = localPathFactory.get().createLocalPath().get(path)) {
                    yield readContent(inputStream);
                }
            }
            default -> throw new IllegalArgumentException(SCHEME_NOT_SUPPORTED_ERROR.formatted(path));
        };
    }

    // Returns byte[] for binary ION (preserves fidelity for fromIon()), String for everything else
    private static Object readContent(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        if (IonStreamUtils.isIonBinary(bytes)) {
            return bytes;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    protected String getErrorMessage() {
        return ERROR_MESSAGE;
    }
}
