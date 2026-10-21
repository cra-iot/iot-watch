package org.openremote.manager.iotwatch;

import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;

/** Registers the fleet search endpoint; mirrors IotWatchIngestService's lifecycle. */
public class IotWatchFleetService implements ContainerService {

    @Override
    public void init(Container container) throws Exception {
        container.getService(ManagerWebService.class).addApiSingleton(
                new FleetResourceImpl(
                        container.getService(TimerService.class),
                        container.getService(ManagerIdentityService.class),
                        container.getService(AssetStorageService.class),
                        FleetColumnResolver.fromConfig(container)));
    }

    @Override
    public void start(Container container) throws Exception {
        // No-op: the resource is stateless.
    }

    @Override
    public void stop(Container container) throws Exception {
        // No-op.
    }
}
