package org.flow.core.runners;

import java.util.List;

import org.flow.core.queues.QueueException;

public interface AssetEmitter {
    void emit(AssetEmit assetEmit) throws QueueException;

    List<AssetEmit> emitted();
}
