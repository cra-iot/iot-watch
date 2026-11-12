package org.openremote.manager.iotwatch;

import java.time.Duration;

import org.openremote.container.persistence.PersistenceService;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.datapoint.AssetDatapointService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.util.MapAccess;

/** Registers the fleet search endpoint; mirrors IotWatchIngestService's lifecycle. */
public class IotWatchFleetService implements ContainerService {

    @Override
    public void init(Container container) throws Exception {
        var config = container.getConfig();
        FleetExporter exporter = new FleetExporter(
                new DailyValueRepository(container.getService(PersistenceService.class),
                        MapAccess.getInteger(config, AssetDatapointService.OR_DATA_POINTS_MAX_AGE_WEEKS, -1)),
                MapAccess.getInteger(config, FleetExporter.MAX_ROWS, 1_000_000),
                MapAccess.getInteger(config, FleetExporter.MAX_CONCURRENT, 2),
                Duration.ofMinutes(MapAccess.getInteger(config, FleetExporter.PERMIT_TIMEOUT_MINUTES,
                        FleetExporter.DEFAULT_PERMIT_TIMEOUT_MINUTES)),
                System::nanoTime);
        container.getService(ManagerWebService.class).addApiSingleton(
                new FleetResourceImpl(
                        container.getService(TimerService.class),
                        container.getService(ManagerIdentityService.class),
                        container.getService(AssetStorageService.class),
                        FleetColumnResolver.fromConfig(container),
                        exporter));
    }

    @Override
    public void start(Container container) throws Exception {
        // No-op: the resource holds no lifecycle state.
    }

    @Override
    public void stop(Container container) throws Exception {
        // No-op.
    }
}
