package org.flow.plugin.core.purge;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import com.google.common.annotations.VisibleForTesting;

import org.flow.core.exceptions.IllegalVariableEvaluationException;
import org.flow.core.exceptions.ValidationErrorException;
import org.flow.core.models.property.Property;
import org.flow.core.models.tasks.SystemTask;
import org.flow.core.repositories.FlowRepositoryInterface;
import org.flow.core.runners.DefaultRunContext;
import org.flow.core.runners.RunContext;
import org.flow.core.utils.ListUtils;

/**
 * Base contract for the namespace-scoped purge tasks.
 *
 * <p>
 * Extends {@link SystemTask} because {@link #findNamespaces(RunContext)} resolves internal
 * Flow services, which are unreachable from a dedicated Worker process.
 * </p>
 */
public interface PurgeTask<T> extends SystemTask {
    Property<List<String>> getNamespaces();

    Property<String> getNamespacePattern();

    Property<Boolean> getIncludeChildNamespaces();

    Property<String> filterPattern();

    String filterTargetExtractor(T item);

    @VisibleForTesting
    default List<String> findNamespaces(RunContext runContext) throws IllegalVariableEvaluationException {
        String tenantId = runContext.flowInfo().tenantId();
        FlowRepositoryInterface flowRepositoryInterface = ((DefaultRunContext) runContext).services().additionalService(FlowRepositoryInterface.class);
        List<String> distinctNamespaces = flowRepositoryInterface.findDistinctNamespace(tenantId);
        List<String> renderedNamespaces = runContext.render(getNamespaces()).asList(String.class);
        String renderedNamespacePattern = runContext.render(getNamespacePattern()).as(String.class).orElse(null);

        if (!ListUtils.isEmpty(renderedNamespaces) && StringUtils.isNotBlank(renderedNamespacePattern)) {
            throw new ValidationErrorException(List.of("Properties `namespaces` and `namespacePattern` can't be used at the same time — use one or the other."));
        }

        List<String> filesNamespaces = new ArrayList<>();
        if (StringUtils.isNotBlank(renderedNamespacePattern)) {
            filesNamespaces.addAll(
                distinctNamespaces.stream()
                    .filter(ns -> FilenameUtils.wildcardMatch(ns, renderedNamespacePattern))
                    .toList()
            );
        } else if (!renderedNamespaces.isEmpty()) {
            if (runContext.render(getIncludeChildNamespaces()).as(Boolean.class).orElse(true)) {
                filesNamespaces.addAll(
                    distinctNamespaces.stream()
                        .filter(ns ->
                        {
                            for (String renderedNamespace : renderedNamespaces) {
                                if (ns.startsWith(renderedNamespace)) {
                                    return true;
                                }
                            }
                            return false;
                        }).toList()
                );
            } else {
                filesNamespaces.addAll(
                    distinctNamespaces.stream()
                        .filter(ns ->
                        {
                            for (String renderedNamespace : renderedNamespaces) {
                                if (ns.equals(renderedNamespace)) {
                                    return true;
                                }
                            }
                            return false;
                        }).toList()
                );
            }

            // add the rendered namespace if not already present, this can happen it's a parent namespace with no flow
            filesNamespaces.addAll(renderedNamespaces.stream().filter(ns -> !filesNamespaces.contains(ns)).toList());

        } else {
            filesNamespaces.addAll(distinctNamespaces);
        }

        for (String ns : filesNamespaces) {
            runContext.acl().allowNamespace(ns).check();
        }
        return filesNamespaces;
    }

    default List<T> filterItems(RunContext runContext, List<T> items) throws IllegalVariableEvaluationException {
        String renderedFilterPattern = runContext.render(this.filterPattern()).as(String.class).orElse(null);
        if (StringUtils.isNotBlank(renderedFilterPattern)) {
            return items.stream()
                .filter(item -> FilenameUtils.wildcardMatch(filterTargetExtractor(item), renderedFilterPattern))
                .toList();
        }
        return items;
    }
}
