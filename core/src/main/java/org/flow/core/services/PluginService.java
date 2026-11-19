package org.flow.core.services;

import java.util.List;
import java.util.Optional;

import org.flow.core.plugins.PluginClassAndMetadata;
import org.flow.core.plugins.PluginRegistry;
import org.flow.core.plugins.RegisteredPlugin;
import org.flow.plugin.api.Plugin;

import jakarta.inject.Singleton;

/** 读取当前引擎全局插件注册表的公开查询入口。 */
@Singleton
public class PluginService {
    private PluginRegistry registry;

    /**
     * 使用引擎已加载的注册表，不创建第二份插件目录。
     * @param registry 同一容器中的插件注册表，不能为 null
     */
    public PluginService(PluginRegistry registry) {
        this.registry = registry;
    }

    /**
     * 返回当前引擎的全局注册插件列表快照。
     * @return 不可修改的列表快照，目录为空时返回空列表；插件元数据对象仍来自注册表
     */
    public List<RegisteredPlugin> plugins() {
        return List.copyOf(registry.plugins());
    }

    /**
     * 按标识查询全局注册表中的插件类型及元数据。
     * @param identifier 插件类型标识，不能为 null 或空白
     * @return 注册表中的插件元数据，未知类型返回空值
     * @throws IllegalArgumentException 插件标识为 null 或空白
     */
    public Optional<PluginClassAndMetadata<? extends Plugin>> plugin(String identifier) {
        return registry.findMetadataByIdentifier(requireQueryKey(identifier, "identifier"));
    }

    /**
     * 查询全局注册表中指定插件类型的已加载版本。
     * @param type 插件类型标识，不能为 null 或空白
     * @return 不可修改的版本列表，未知类型返回空列表
     * @throws IllegalArgumentException 插件类型为 null 或空白
     */
    public List<String> versions(String type) {
        return List.copyOf(registry.getAllVersionsForType(requireQueryKey(type, "type")));
    }

    /**
     * 验证插件目录查询参数并去除两端空白。
     * @param value 待查询的插件标识或类型
     * @param name 校验失败时使用的参数名
     * @return 去除两端空白后的查询值
     * @throws IllegalArgumentException 查询值为 null 或空白
     */
    private static String requireQueryKey(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }
}
