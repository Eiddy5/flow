package org.flow.tooling.schema;

import java.nio.file.Files;
import java.nio.file.Path;

import org.flow.core.plugins.DefaultPluginRegistry;

/** 离线构建入口；复用原生注册表与 Schema 算法，不启动 Micronaut 容器。 */
public class SchemaExports {
    /**
     * 加载指定目录中的插件并导出聚合或配置 Schema；出错时让 Java 进程以失败退出。
     * @param args 依次为 plugins 或 configuration、已存在的插件目录、输出文件
     * @throws Exception 参数无效、插件加载或导出失败时抛出
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[0].equals("plugins") || args[0].equals("configuration"))) {
            throw new IllegalArgumentException("Usage: <plugins|configuration> <plugins-directory> <output-file>");
        }
        Path plugins = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isDirectory(plugins)) {
            throw new IllegalArgumentException("Plugin directory does not exist: " + plugins);
        }
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        var registry = DefaultPluginRegistry.getOrCreate();
        registry.registerIfAbsent(plugins);
        if (args[0].equals("plugins")) {
            new PluginSchemaBundleGenerator().write(registry, output.toFile());
        } else {
            var schema = new ConfigurationSchemaGenerator().generate(registry);
            Files.createDirectories(output.getParent());
            ConfigurationSchemaGenerator.write(schema, output.toFile());
        }
    }
}
