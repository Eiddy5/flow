package org.cses.flow.core.plugins;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.BeanContext;
import jakarta.inject.Inject;
import org.cses.flow.core.domains.flows.Input;
import io.swagger.v3.oas.annotations.media.Schema;
import org.cses.flow.core.domains.tasks.OrchestrationTask;
import org.cses.flow.core.domains.tasks.RunnableTask;
import org.cses.flow.core.domains.tasks.Task;
import org.cses.flow.core.plugins.annotations.Example;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Immutable registry assembled from Micronaut-discovered plugin classes on
 * the application classpath and grouped by their real Java packages.
 */
@Context
public class DefaultPluginRegistry implements PluginRegistry {

    private List<RegisteredPlugin> registeredPlugins;
    private Map<String, PluginMetadata<? extends Plugin>>
            metadataByType;

    /**
     * 从 Task Bean 与宿主编译产物中的业务 Input 类创建只读注册表，不实例化 Input。
     * @param plugins 已发现的非空 Task Bean 集合，只读
     * @param context 当前宿主 BeanContext，用于选择应用 ClassLoader
     * @throws IllegalStateException 当注册类或能力非法时抛出
     */
    @Inject
    public DefaultPluginRegistry(Collection<Plugin> plugins, BeanContext context) {
        this(plugins, InputTypes.discover(context.getClassLoader()));
    }

    /**
     * 创建显式 Task 与当前应用索引中的 Input 注册表，供无容器调用方使用。
     * @param plugins 非空且不含 null 的 Task 集合，只读
     * @throws IllegalStateException 当注册非法时抛出
     */
    public DefaultPluginRegistry(Collection<Plugin> plugins) {
        this(plugins, InputTypes.discover(Thread.currentThread().getContextClassLoader()));
    }

    /**
     * 注册显式 Task 实例与业务 Input 类；业务 Input 不会被实例化。
     * @param plugins 非空 Task 集合，只读
     * @param inputTypes 非空业务 Input 类集合，只读，重复或能力错误拒绝
     * @throws IllegalStateException 当注册类、注解或类型重复时抛出
     */
    public DefaultPluginRegistry(Collection<Plugin> plugins, Collection<Class<?>> inputTypes) {
        if (plugins == null) {
            throw new IllegalStateException(
                    "Plugin registrations must not be null"
            );
        }

        List<Plugin> orderedPlugins = new ArrayList<>();
        for (Plugin plugin : plugins) {
            if (plugin == null) {
                throw new IllegalStateException(
                        "Plugin registration must not be null"
                );
            }
            orderedPlugins.add(plugin);
        }
        orderedPlugins.sort(Comparator.comparing(
                DefaultPluginRegistry::orderingName
        ));

        Map<String, PluginMetadata<? extends Plugin>> registrations =
                new LinkedHashMap<>();
        Map<String, List<PluginMetadata<Task>>> tasksByPackage =
                new LinkedHashMap<>();
        orderedPlugins.forEach(plugin -> register(
                registrations,
                tasksByPackage,
                plugin
        ));

        Map<String, List<PluginMetadata<Input>>> inputsByPackage = new LinkedHashMap<>();
        new InputTypes(inputTypes);
        inputTypes.stream()
            .sorted(Comparator.comparing(Class::getName))
            .forEach(type -> registerInput(registrations, inputsByPackage, type));
        this.metadataByType = Map.copyOf(registrations);
        Set<String> packages = new TreeSet<>(tasksByPackage.keySet());
        packages.addAll(inputsByPackage.keySet());
        this.registeredPlugins = packages.stream()
            .map(packageName -> RegisteredPlugin.from(packageName,
                tasksByPackage.getOrDefault(packageName, List.of()),
                inputsByPackage.getOrDefault(packageName, List.of())))
            .toList();
    }

    @Override
    public List<RegisteredPlugin> plugins() {
        return registeredPlugins;
    }

    @Override
    public Optional<PluginMetadata<? extends Plugin>> findMetadata(
            String type
    ) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(metadataByType.get(type));
    }

    @Override
    public <P extends Plugin> Class<? extends P> resolve(
            String type,
            Class<P> expectedBaseClass
    ) {
        Objects.requireNonNull(
                expectedBaseClass,
                "Expected plugin base class"
        );
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException(
                    "Plugin type must not be blank"
            );
        }
        PluginMetadata<? extends Plugin> metadata =
                metadataByType.get(type);
        if (metadata == null) {
            throw new IllegalArgumentException(
                    "No plugin registered for type: " + type
            );
        }
        Class<? extends Plugin> pluginClass = metadata.type();
        if (!expectedBaseClass.isAssignableFrom(pluginClass)) {
            throw new IllegalArgumentException(
                    "Plugin " + type + " is not a "
                            + expectedBaseClass.getName()
            );
        }
        return pluginClass.asSubclass(expectedBaseClass);
    }

    private static void register(
            Map<String, PluginMetadata<? extends Plugin>> registrations,
            Map<String, List<PluginMetadata<Task>>> tasksByPackage,
            Plugin plugin
    ) {
        Class<? extends Plugin> pluginClass = plugin.getClass()
                .asSubclass(Plugin.class);
        org.cses.flow.core.plugins.annotations.Plugin annotation =
                pluginClass.getDeclaredAnnotation(
                        org.cses.flow.core.plugins.annotations.Plugin.class
                );
        if (annotation == null) {
            throw new IllegalStateException(
                    "Plugin is missing @Plugin: " + pluginClass.getName()
            );
        }
        if (!Modifier.isPublic(pluginClass.getModifiers())
                || Modifier.isAbstract(pluginClass.getModifiers())) {
            throw new IllegalStateException(
                    "Plugin must be a public concrete class: "
                            + pluginClass.getName()
            );
        }
        requirePublicNoArgsConstructor(pluginClass);
        if (!Task.class.isAssignableFrom(pluginClass)) {
            throw new IllegalStateException(
                    "Unsupported plugin capability: " + pluginClass.getName()
            );
        }
        requireTaskCapability(pluginClass);

        String canonicalName = pluginClass.getCanonicalName();
        if (canonicalName == null || canonicalName.isBlank()) {
            throw new IllegalStateException(
                    "Plugin must have a canonical class name: "
                            + pluginClass.getName()
            );
        }
        if (!canonicalName.equals(plugin.getType())) {
            throw new IllegalStateException(
                    "Plugin type must equal its canonical class name: "
                            + pluginClass.getName()
            );
        }
        String packageName = pluginClass.getPackageName();
        if (packageName.isBlank()) {
            throw new IllegalStateException(
                    "Plugin must belong to a named Java package: "
                            + pluginClass.getName()
            );
        }

        Class<? extends Task> taskClass = pluginClass.asSubclass(Task.class);
        PluginMetadata<Task> metadata = PluginMetadata.from(
                taskClass,
                Task.class,
                annotation.title(),
                annotation.description(),
                examples(annotation.examples()),
                List.of(annotation.capabilities())
        );
        PluginMetadata<? extends Plugin> existing =
                registrations.putIfAbsent(canonicalName, metadata);
        if (existing != null) {
            throw new IllegalStateException(
                    "Duplicate plugin type '" + canonicalName + "': "
                            + existing.type().getName() + " and "
                            + pluginClass.getName()
            );
        }
        tasksByPackage.computeIfAbsent(
                packageName,
                ignored -> new ArrayList<>()
        ).add(metadata);
    }

    /**
     * 校验并登记一个业务 Input 类，不调用其构造或创建方法。
     * @param registrations 可修改的精确类型索引
     * @param inputsByPackage 可修改的包目录
     * @param type 非空公共具体 Input 类，必须直接标注 JsonTypeName
     * @throws IllegalStateException 当类型、注解、能力或重复注册非法时抛出
     */
    private static void registerInput(
        Map<String, PluginMetadata<? extends Plugin>> registrations,
        Map<String, List<PluginMetadata<Input>>> inputsByPackage,
        Class<?> type
    ) {
        if (type == null || !Modifier.isPublic(type.getModifiers())
            || Modifier.isAbstract(type.getModifiers())
            || !Input.class.isAssignableFrom(type)
            || type.getCanonicalName() == null || type.getPackageName().isBlank()) {
            throw new IllegalStateException("Input plugin must be a public concrete Input class: " + type);
        }
        Schema annotation = type.getDeclaredAnnotation(Schema.class);
        PluginMetadata<Input> metadata = PluginMetadata.from(
            type.asSubclass(Input.class), Input.class,
            annotation == null ? null : annotation.title(),
            annotation == null ? null : annotation.description());
        for (String name : new LinkedHashSet<>(List.of(metadata.typeName(), metadata.canonicalType()))) {
            if (registrations.putIfAbsent(name, metadata) != null) {
                throw new IllegalStateException("Duplicate plugin type '" + name + "'");
            }
        }
        inputsByPackage.computeIfAbsent(type.getPackageName(), ignored -> new ArrayList<>()).add(metadata);
    }

    private static List<PluginExample> examples(Example[] declarations) {
        return Arrays.stream(declarations)
                .map(declaration -> PluginExample.from(
                        declaration.title(),
                        List.copyOf(Arrays.asList(declaration.code())),
                        declaration.lang(),
                        declaration.full()
                ))
                .toList();
    }

    private static String orderingName(Plugin plugin) {
        String canonicalName = plugin.getClass().getCanonicalName();
        return canonicalName == null
                ? plugin.getClass().getName()
                : canonicalName;
    }

    private static void requirePublicNoArgsConstructor(
            Class<? extends Plugin> pluginClass
    ) {
        try {
            Constructor<?> constructor = pluginClass.getConstructor();
            if (!Modifier.isPublic(constructor.getModifiers())) {
                throw new NoSuchMethodException();
            }
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(
                    "Plugin requires a public no-args constructor: "
                            + pluginClass.getName(),
                    exception
            );
        }
    }

    private static void requireTaskCapability(
            Class<? extends Plugin> pluginClass
    ) {
        boolean runnable = RunnableTask.class.isAssignableFrom(pluginClass);
        boolean orchestration = OrchestrationTask.class.isAssignableFrom(
                pluginClass
        );
        if (runnable == orchestration) {
            throw new IllegalStateException(
                    "Task plugin must implement exactly one runtime capability: "
                            + pluginClass.getName()
            );
        }
    }
}
