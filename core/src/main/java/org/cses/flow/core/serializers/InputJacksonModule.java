package org.cses.flow.core.serializers;

import jakarta.inject.Singleton;
import org.cses.flow.core.domains.flows.Input;
import org.cses.flow.core.plugins.InputTypes;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.jsontype.NamedType;
import tools.jackson.databind.jsontype.TypeIdResolver;
import tools.jackson.databind.module.SimpleModule;

/** Installs the same Input index into the Jackson 3 mapper used by PAAS and HTTP. */
@Singleton
public class InputJacksonModule extends SimpleModule {
    /**
     * Creates the reusable module for Jackson SPI and Micronaut discovery.
     */
    public InputJacksonModule() {
        super("flow-inputs");
    }

    /**
     * Adds native name binding and the historical built-in code compatibility rule.
     * @param context the Jackson 3 mapper's module setup context
     * @throws IllegalStateException if indexed classes or names are invalid
     */
    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        ClassLoader loader = context.typeFactory().getClassLoader();
        if (loader == null) {
            loader = Thread.currentThread().getContextClassLoader();
        }
        InputTypes inputTypes = new InputTypes(InputTypes.discover(
            loader == null ? InputJacksonModule.class.getClassLoader() : loader));
        inputTypes.bindings().forEach((name, type) -> context.registerSubtypes(new NamedType(type, name)));
        context.addHandler(new DeserializationProblemHandler() {
            /**
             * Resolves only an unrecognized legacy built-in code through the shared index.
             * @param context current binding context
             * @param baseType declared target type
             * @param id unrecognized textual identifier
             * @param resolver native Jackson name resolver
             * @param failure original error description
             * @return registered built-in type, or null to retain Jackson's original failure
             */
            @Override
            public JavaType handleUnknownTypeId(DeserializationContext context, JavaType baseType,
                                               String id, TypeIdResolver resolver, String failure) {
                Class<?> type = baseType.hasRawClass(Input.class) ? inputTypes.legacyType(id) : null;
                return type == null ? null : context.constructType(type);
            }
        });
    }
}
