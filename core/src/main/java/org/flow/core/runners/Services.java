package org.flow.core.runners;

import java.util.Optional;

import org.flow.core.docs.JsonSchemaGenerator;
import org.flow.core.models.ServerType;
import org.flow.core.models.tasks.runners.TaskLogLineMatcher;
import org.flow.core.trace.TracerFactory;
import org.flow.core.utils.UriProvider;

import io.micrometer.observation.ObservationRegistry;
import io.micronaut.context.ApplicationContext;

/**
 * Provides access to Flow internal services beans.
 * Must only be called when absolutely necessary.
 * Not part of the official plugin API, so it may be changed without notice.
 */
public class Services {
    private final ApplicationContext applicationContext;
    private final boolean isWorker;

    Services(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        this.isWorker = applicationContext.getProperty("flow.server-type", String.class).map(p -> p.equals(ServerType.WORKER.name())).orElse(false);
    }

    /**
     * Provides access to the {@link TracerFactory} bean.
     */
    public TracerFactory tracerFactory() {
        return applicationContext.getBean(TracerFactory.class);
    }

    /**
     * Provides access to the {@link UriProvider} bean from.
     */
    public UriProvider uriProvider() {
        return applicationContext.getBean(UriProvider.class);
    }

    /**
     * Provides access to the {@link TaskLogLineMatcher} bean.
     */
    public TaskLogLineMatcher taskLogLineMatcher() {
        return applicationContext.getBean(TaskLogLineMatcher.class);
    }

    /**
     * Provides access to the {@link ObservationRegistry} bean.
     */
    public Optional<ObservationRegistry> observationRegistry() {
        return applicationContext.findBean(ObservationRegistry.class);
    }

    /**
     * Provides access to the {@link JsonSchemaGenerator} bean.
     */
    public JsonSchemaGenerator jsonSchemaGenerator() {
        return applicationContext.getBean(JsonSchemaGenerator.class);
    }

    /**
     * Provides access to additional services which are not covered by individual methods.
     * WARNING: this method throws inside the Worker and should only be used if nothing else could be done.
     * CRITICAL: This method may be removed in 2.0 and is considered as a transition API.
     */
    public <T> T additionalService(Class<T> clazz) {
        if (isWorker) {
            throw new RuntimeException("Services.additionalService() cannot be used inside the Worker");
        }
        return this.applicationContext.getBean(clazz);
    }
}
