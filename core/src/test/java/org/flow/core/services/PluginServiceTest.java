package org.flow.core.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.flow.core.plugins.PluginClassAndMetadata;
import org.flow.core.plugins.PluginRegistry;
import org.flow.core.plugins.RegisteredPlugin;
import org.flow.plugin.core.log.Log;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PluginServiceTest {
    /** 验证三个查询直接读取全局目录，查询标识去除两端空白，列表结果为不可修改的快照。 */
    @Test
    void queriesGlobalDirectory() {
        var registry = mock(PluginRegistry.class);
        var registered = mock(RegisteredPlugin.class);
        var metadata = mock(PluginClassAndMetadata.class);
        String type = Log.class.getName();
        var entries = new ArrayList<>(List.of(registered));
        var versions = new ArrayList<>(List.of("1.0.0"));
        when(registry.plugins()).thenReturn(entries);
        doReturn(Optional.of(metadata)).when(registry).findMetadataByIdentifier(type);
        when(registry.getAllVersionsForType(type)).thenReturn(versions);
        var service = new PluginService(registry);

        var plugins = service.plugins();
        var loadedVersions = service.versions(" " + type + " ");
        entries.clear();
        versions.clear();

        assertEquals(List.of(registered), plugins);
        assertThrows(UnsupportedOperationException.class, plugins::clear);
        assertEquals(Optional.of(metadata), service.plugin(" " + type + " "));
        assertEquals(List.of("1.0.0"), loadedVersions);
        assertThrows(UnsupportedOperationException.class, loadedVersions::clear);
        assertTrue(service.plugin("missing").isEmpty());
        assertTrue(service.versions("missing").isEmpty());
    }

    /** 验证空标识和空类型在访问注册表前被拒绝。 */
    @Test
    void rejectsMissingQueryKeys() {
        var registry = mock(PluginRegistry.class);
        var service = new PluginService(registry);

        assertThrows(IllegalArgumentException.class, () -> service.plugin(null));
        assertThrows(IllegalArgumentException.class, () -> service.plugin(" "));
        assertThrows(IllegalArgumentException.class, () -> service.versions(null));
        assertThrows(IllegalArgumentException.class, () -> service.versions(" "));
        verifyNoInteractions(registry);
    }
}
