package org.flow.core.runners;

import java.util.Collections;
import java.util.List;

import org.flow.core.models.assets.Asset;
import org.flow.core.models.assets.AssetIdentifier;

public record AssetEmit(List<AssetIdentifier> inputs, List<Asset> outputs) {
    public AssetEmit {
        if (inputs == null) {
            inputs = Collections.emptyList();
        }
        if (outputs == null) {
            outputs = Collections.emptyList();
        }
    }
}
