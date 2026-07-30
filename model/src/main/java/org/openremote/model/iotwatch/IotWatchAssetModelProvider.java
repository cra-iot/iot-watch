package org.openremote.model.iotwatch;

import org.openremote.model.AssetModelProvider;

/**
 * Registers the IoT Watch asset model with the manager; auto-scan discovers all
 * {@link org.openremote.model.asset.Asset} subclasses in this extension JAR.
 * Registered via {@code META-INF/services/org.openremote.model.AssetModelProvider}.
 */
public class IotWatchAssetModelProvider implements AssetModelProvider {

    @Override
    public boolean useAutoScan() {
        return true;
    }
}
