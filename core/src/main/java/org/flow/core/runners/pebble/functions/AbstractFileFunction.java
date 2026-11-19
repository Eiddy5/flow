package org.flow.core.runners.pebble.functions;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.cronutils.utils.VisibleForTesting;

import org.flow.core.runners.LocalPath;
import org.flow.core.runners.LocalPathFactory;
import org.flow.core.runners.configuration.LocalFilesConfiguration;
import org.flow.core.services.NamespaceService;
import org.flow.core.storages.*;
import org.flow.core.utils.Slugify;

import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

abstract class AbstractFileFunction implements FlowFunction {
    static final String SCHEME_NOT_SUPPORTED_ERROR = "Cannot process the URI %s: scheme not supported.";
    static final String FLOW_SCHEME = "flow:///";
    static final String TRIGGER = "trigger";
    static final String NAMESPACE = "namespace";
    static final String TENANT_ID = "tenantId";
    static final String ID = "id";
    static final String PATH = "path";

    private static final Pattern EXECUTION_FILE = Pattern.compile(".*/.*/executions/.*/tasks/.*/.*");

    @Inject
    protected Provider<NamespaceService> namespaceService;

    @Inject
    protected Provider<StorageInterface> storageInterface;

    @Inject
    protected Provider<LocalPathFactory> localPathFactory;

    @Inject
    protected LocalFilesConfiguration localFilesConfiguration;

    //    @Value("${flow.server-type:}") // default to empty as tests didn't set this property
    //    private String serverType;

    @SuppressWarnings("unchecked")
    @Override
    public Object execute(Map<String, Object> args, PebbleTemplate self, EvaluationContext context, int lineNumber) {
        // TODO it will be enabled on the next release so the code is kept commented out
        //  don't forget to also re-enabled the test
        //        if (!calledOnWorker()) {
        //            throw new PebbleException(null, "The 'read' function can only be used in the Worker as it access the internal storage.", lineNumber, self.getName());
        //        }

        if (!args.containsKey(PATH)) {
            throw new PebbleException(null, getErrorMessage(), lineNumber, self.getName());
        }

        Object path = args.get(PATH);

        try {
            URI fileUri;
            String namespace;
            Map<String, String> flow = (Map<String, String>) context.getVariable("flow");
            String tenantId = flow.get(TENANT_ID);

            if (path instanceof URI uri) {
                fileUri = uri;
            } else if (path instanceof String str) {
                fileUri = URI.create(str);
            } else {
                throw new PebbleException(null, "Unable to read the file " + path, lineNumber, self.getName());
            }

            if (StorageContext.FLOW_SCHEME.equals(fileUri.getScheme())) {
                namespace = checkAllowedFileAndReturnNamespace(context, fileUri);
            } else if (LocalPath.FILE_SCHEME.equals(fileUri.getScheme())) {
                if (!localFilesConfiguration.enableFileFunctions()) {
                    throw new SecurityException("The file:// protocol has been disabled inside the Flow configuration.");
                }
                namespace = flow.get(NAMESPACE);
            } else {
                throw new IllegalArgumentException(SCHEME_NOT_SUPPORTED_ERROR.formatted(path));
            }
            return fileFunction(context, fileUri, namespace, tenantId, args);
        } catch (IOException | IllegalArgumentException e) { // IllegalArgumentException may be thrown for path traversal, catch it to have proper error handling
            throw new PebbleException(e, e.getMessage(), lineNumber, self.getName());
        }
    }

    @Override
    public List<String> getArgumentNames() {
        return List.of(PATH);
    }

    @Override
    public Map<String, String> getArgumentDefaults() {
        return Map.of(
            PATH, "outputs.download.uri"
        );
    }

    protected abstract String getErrorMessage();

    protected abstract Object fileFunction(EvaluationContext context, URI path, String namespace, String tenantId, Map<String, Object> args) throws IOException;

    boolean isFileUriValid(String namespace, String flowId, String executionId, URI path) {
        // Internal storage URI should be: flow:///$namespace/$flowId/executions/$executionId/tasks/$taskName/$taskRunId/$random.ion or flow:///$namespace/$flowId/executions/$executionId/trigger/$triggerName/$random.ion
        // We check that the file is for the given flow execution
        if (namespace == null || flowId == null || executionId == null) {
            return false;
        }

        String executionAuthorizedBasePath = FLOW_SCHEME + namespace.replace(".", "/") + "/" + Slugify.of(flowId) + "/executions/" + executionId + "/";
        return path.toString().startsWith(executionAuthorizedBasePath);
    }

    @SuppressWarnings("unchecked")
    private String checkAllowedFileAndReturnNamespace(EvaluationContext context, URI path) {
        Map<String, String> flow = (Map<String, String>) context.getVariable("flow");
        Map<String, String> execution = (Map<String, String>) context.getVariable("execution");

        // check if the file is from the current execution, the parent execution or an allowed namespaces
        boolean isFileFromCurrentExecution = isFileUriValid(flow.get(NAMESPACE), flow.get(ID), execution.get(ID), path);
        if (isFileFromCurrentExecution) {
            return flow.get(NAMESPACE);
        } else {
            if (isFileFromParentExecution(context, path)) {
                Map<String, String> trigger = (Map<String, String>) context.getVariable(TRIGGER);
                return trigger.get(NAMESPACE);
            } else {
                return checkIfFileFromAllowedNamespaceAndReturnIt(path, flow.get(TENANT_ID), flow.get(NAMESPACE));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private boolean isFileFromParentExecution(EvaluationContext context, URI path) {
        if (context.getVariable(TRIGGER) != null) {
            // if there is a trigger of type execution, we also allow accessing a file from the parent execution
            Map<String, String> trigger = (Map<String, String>) context.getVariable(TRIGGER);

            return isFileUriValid(trigger.get(NAMESPACE), trigger.get("flowId"), trigger.get("executionId"), path);
        }
        return false;
    }

    private String checkIfFileFromAllowedNamespaceAndReturnIt(URI path, String tenantId, String fromNamespace) {

        String namespace = extractNamespace(path);
        namespaceService.get().checkAllowedNamespace(tenantId, namespace, tenantId, fromNamespace);
        return namespace;
    }

    @VisibleForTesting
    String extractNamespace(URI path) {
        // Extract namespace from the path, it should be of the form: flow:///{namespace}/{flowId}/executions/{executionId}/tasks/{taskId}/{taskRunId}/{fileName}'
        // To extract the namespace, we must do it step by step as namespace and taskId can contain the words 'executions' and 'tasks'
        String namespace = path.toString().substring(FLOW_SCHEME.length());
        if (!EXECUTION_FILE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Unable to read the file '" + path + "' as it is not an execution file");
        }
        // 1. remove everything after tasks
        namespace = namespace.substring(0, namespace.lastIndexOf("/tasks/"));
        // 2. remove everything after executions
        namespace = namespace.substring(0, namespace.lastIndexOf("/executions/"));
        // 3. remove the flowId
        namespace = namespace.substring(0, namespace.lastIndexOf('/'));
        // 4. replace '/' with '.'
        namespace = namespace.replace("/", ".");

        return namespace;
    }
}
