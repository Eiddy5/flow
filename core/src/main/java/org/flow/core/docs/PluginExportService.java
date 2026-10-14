package org.flow.core.docs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import org.flow.core.plugins.PluginRegistry;
import org.flow.core.plugins.RegisteredPlugin;
import org.flow.core.serializers.JacksonMapper;
import jakarta.inject.Singleton;

/** 提供本项目插件目录和 Task 文档、Schema、图标导出入口。 */
@Singleton
public class PluginExportService {
    private PluginRegistry registry;
    private DocumentationGenerator generator;

    /**
     * 使用运行时实际插件目录及上游生成器构建导出服务，不建立第二份类型注册表。
     *
     * @param registry 当前引擎的插件注册表；不能为 null
     * @param generator 同一 Context 中的文档和 Schema 生成器；不能为 null
     */
    public PluginExportService(PluginRegistry registry, DocumentationGenerator generator) {
        this.registry = Objects.requireNonNull(registry);
        this.generator = Objects.requireNonNull(generator);
    }

    /**
     * 返回当前已加载插件目录的列表快照；目录中的类型来自实际注册表。
     *
     * @return 不可修改的列表，元素为上游插件描述对象
     */
    public List<RegisteredPlugin> catalog() {
        return List.copyOf(registry.plugins());
    }

    /**
     * 将所选已加载插件的上游文档、Schema 和可选图标写入一个尚不存在的目录。
     * 生成失败会抛出异常；调用方可以检查并删除本次未完成的目录后重试。
     *
     * @param destination 新导出目录；不能为 null，必须尚不存在
     * @param includeCore 为 true 时包含内置插件，为 false 时只导出外部插件
     * @return 本次写出的文件绝对路径的不可修改列表
     * @throws Exception 生成失败、目录已存在或文件无法写入时抛出
     */
    public List<Path> export(Path destination, boolean includeCore) throws Exception {
        Path root = destination.toAbsolutePath().normalize();
        Files.createDirectory(root);
        List<Path> files = new ArrayList<>();
        List<RegisteredPlugin> plugins = includeCore ? registry.plugins() : registry.externalPlugins();
        for (RegisteredPlugin plugin : plugins) {
            for (Document document : generator.generate(plugin)) {
                String path = document.getPath();
                files.add(write(root, path, document.getBody().getBytes(StandardCharsets.UTF_8)));
                String base = path.lastIndexOf('.') >= 0 ? path.substring(0, path.lastIndexOf('.')) : path;
                if (document.getSchema() != null) {
                    files.add(write(root, base + ".json", JacksonMapper.ofJson().writeValueAsBytes(document.getSchema())));
                }
                if (document.getIcon() != null) {
                    files.add(write(root, base + ".svg", Base64.getDecoder().decode(document.getIcon())));
                }
            }
        }
        return List.copyOf(files);
    }

    /**
     * 在本次新建的导出目录内创建文件；拒绝越界路径及覆盖同名产物。
     *
     * @param root 本次导出的绝对根目录
     * @param relative 上游生成器提供的相对路径
     * @param content 要写出的字节；只读且不能为 null
     * @return 已写出文件的绝对路径
     * @throws IOException 路径越界、重复文件或写入失败时抛出
     */
    private Path write(Path root, String relative, byte[] content) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IOException("插件导出路径超出目标目录：" + relative);
        }
        Files.createDirectories(target.getParent());
        Files.write(target, content, StandardOpenOption.CREATE_NEW);
        return target;
    }
}
