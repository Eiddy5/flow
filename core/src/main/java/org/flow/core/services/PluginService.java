package org.flow.core.services;

import java.util.List;
import java.util.Optional;

import org.flow.core.plugins.PluginClassAndMetadata;
import org.flow.core.plugins.PluginRegistry;
import org.flow.core.plugins.RegisteredPlugin;
import org.flow.core.utils.SessionUtil;
import org.flow.plugin.api.Plugin;
import org.paas.session.Session;
import org.paas.session.SessionUser;

import jakarta.inject.Singleton;

/** 对外插件查询入口；校验 Session 后读取同一引擎的全局注册表。 */
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
     * 验证调用身份后返回注册插件列表快照，目录内容不按租户分区。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @return 不可修改的列表快照，目录为空时返回空列表；插件元数据对象仍来自注册表
     * @throws IllegalArgumentException 会话缺少企业或操作者标识
     */
    public <S extends Session<U>, U extends SessionUser> List<RegisteredPlugin> plugins(S session) {
        SessionUtil.user(session);
        return List.copyOf(registry.plugins());
    }

    /**
     * 验证调用身份后查询插件类型及元数据。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param identifier 插件类型标识，不能为 null 或空白
     * @return 注册表中的插件元数据，未知类型返回空值
     * @throws IllegalArgumentException 会话身份不完整或插件标识为空白
     */
    public <S extends Session<U>, U extends SessionUser> Optional<PluginClassAndMetadata<? extends Plugin>> plugin(
        S session, String identifier) {
        SessionUtil.user(session);
        return registry.findMetadataByIdentifier(SessionUtil.required(identifier, "identifier"));
    }

    /**
     * 验证调用身份后查询指定插件类型的所有已加载版本。
     * @param session 具有企业和操作者的宿主会话，不能为 null
     * @param type 插件类型标识，不能为 null 或空白
     * @return 不可修改的版本列表，未知类型返回空列表
     * @throws IllegalArgumentException 会话身份不完整或插件类型为空白
     */
    public <S extends Session<U>, U extends SessionUser> List<String> versions(S session, String type) {
        SessionUtil.user(session);
        return List.copyOf(registry.getAllVersionsForType(SessionUtil.required(type, "type")));
    }
}
