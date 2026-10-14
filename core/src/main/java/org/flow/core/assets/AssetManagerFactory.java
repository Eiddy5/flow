package org.flow.core.assets;

import java.util.ArrayList;
import java.util.List;

import org.flow.core.runners.AssetEmit;
import org.flow.core.runners.AssetEmitter;

import jakarta.inject.Singleton;

@Singleton
public class AssetManagerFactory {
    public AssetEmitter of(boolean enabled) {
        return new AssetEmitter() {
            @Override
            public void emit(AssetEmit assetEmit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<AssetEmit> emitted() {
                return new ArrayList<>();
            }
        };
    }
}
