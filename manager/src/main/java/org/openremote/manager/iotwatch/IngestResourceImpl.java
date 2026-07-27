package org.openremote.manager.iotwatch;

import jakarta.ws.rs.core.Response;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebResource;
import org.openremote.model.iotwatch.IngestEnvelope;
import org.openremote.model.iotwatch.IngestResource;

/**
 * No @RolesAllowed on purpose: the endpoint is reachable without an OAuth
 * token and performs its own API-key authentication in the service.
 */
public class IngestResourceImpl extends ManagerWebResource implements IngestResource {

    protected final IotWatchIngestService ingestService;

    public IngestResourceImpl(TimerService timerService, ManagerIdentityService identityService,
                              IotWatchIngestService ingestService) {
        super(timerService, identityService);
        this.ingestService = ingestService;
    }

    @Override
    public Response ingest(String apiKey, IngestEnvelope envelope) {
        return ingestService.handle(getRequestRealmName(), apiKey, envelope);
    }
}
