package org.openremote.manager.iotwatch;

import jakarta.ws.rs.core.Response;
import org.openremote.manager.asset.AssetProcessingService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.asset.Asset;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.iotwatch.IngestEnvelope;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.filter.AttributePredicate;
import org.openremote.model.query.filter.RealmPredicate;
import org.openremote.model.query.filter.StringPredicate;

import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Receives CRA IoT Platform HTTP egress messages on POST /api/{realm}/ingest.
 * Authentication is a per-realm API key (OR_IOTWATCH_INGEST_KEYS env var,
 * "realm1:key1,realm2:key2") — the platform egress cannot do OAuth2. The
 * target asset is matched by its devEui attribute through an in-memory cache
 * so the request path performs no database query; the parsed inner message is
 * written to the asset's rawValue attribute for Groovy rules to decode.
 * See docs/http-ingest.md.
 */
public class IotWatchIngestService implements ContainerService {

    public static final String OR_IOTWATCH_INGEST_KEYS = "OR_IOTWATCH_INGEST_KEYS";
    public static final String DEV_EUI_ATTRIBUTE_NAME = "devEui";
    public static final String RAW_VALUE_ATTRIBUTE_NAME = "rawValue";

    private static final Logger LOG = Logger.getLogger(IotWatchIngestService.class.getName());

    protected IngestKeyStore keyStore = IngestKeyStore.parse(null);
    protected final DevEuiCache cache = new DevEuiCache();
    protected AssetStorageService assetStorageService;
    protected AssetProcessingService assetProcessingService;

    @Override
    public void init(Container container) throws Exception {
        // completed in Task 7 (lifecycle wiring)
    }

    @Override
    public void start(Container container) throws Exception {
        // completed in Task 7 (lifecycle wiring)
    }

    @Override
    public void stop(Container container) throws Exception {
    }

    public Response handle(String realm, String apiKey, IngestEnvelope envelope) {
        if (!keyStore.check(realm, apiKey)) {
            LOG.fine(() -> "Rejected ingest request for realm '" + realm + "': invalid API key");
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        IngestPayload payload;
        try {
            payload = IngestPayload.parse(envelope);
        } catch (IngestPayload.InvalidPayloadException e) {
            LOG.log(Level.FINE, "Rejected ingest request for realm '" + realm + "'", e);
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }

        Set<String> assetIds = cache.resolve(realm, payload.getEui());
        if (assetIds.isEmpty()) {
            assetIds = queryAssetIdsByDevEui(realm, payload.getEui());
            if (assetIds.size() == 1) {
                cache.put(realm, payload.getEui(), assetIds.iterator().next());
            }
        }

        if (assetIds.isEmpty()) {
            LOG.warning("No asset with devEui '" + payload.getEui() + "' in realm '" + realm + "'");
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (assetIds.size() > 1) {
            LOG.warning("Multiple assets with devEui '" + payload.getEui() + "' in realm '" + realm + "': " + assetIds);
            return Response.status(Response.Status.CONFLICT).build();
        }

        long timestamp = payload.getTimestamp() != null ? payload.getTimestamp() : currentTimeMillis();
        dispatch(new AttributeEvent(assetIds.iterator().next(), RAW_VALUE_ATTRIBUTE_NAME, payload.getMessage(), timestamp));
        return Response.ok().build();
    }

    protected Set<String> queryAssetIdsByDevEui(String realm, String eui) {
        return assetStorageService.findAll(new AssetQuery()
                .realm(new RealmPredicate(realm))
                .attributes(new AttributePredicate()
                    .name(DEV_EUI_ATTRIBUTE_NAME)
                    .value(new StringPredicate(AssetQuery.Match.EXACT, false, eui))))
            .stream()
            .map(Asset::getId)
            .collect(Collectors.toSet());
    }

    protected void dispatch(AttributeEvent event) {
        assetProcessingService.sendAttributeEvent(event);
    }

    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
