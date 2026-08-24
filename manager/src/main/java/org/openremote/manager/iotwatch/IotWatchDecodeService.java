package org.openremote.manager.iotwatch;

import org.apache.camel.builder.RouteBuilder;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.manager.asset.AssetProcessingService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.event.ClientEventService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetFilter;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.watermeter.WaterMeterAsset;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Decodes rawValue changes into typed attributes for IoT Watch asset types, replacing the
 * Manager-UI Groovy decode rules. Reacts to committed rawValue attribute events on the
 * internal event bus (the pattern used by RulesService/AgentService/AssetDatapointService),
 * so it is independent of how rawValue was written. A per-asset decode plan is cached and
 * kept fresh from asset persistence events, keeping the reaction path DB-free in steady state.
 */
public class IotWatchDecodeService implements ContainerService {

    public static final String RAW_VALUE_ATTRIBUTE_NAME = "rawValue";
    public static final String DATA_DECODED_KEY = "data_decoded";

    private static final Logger LOG = Logger.getLogger(IotWatchDecodeService.class.getName());

    public record CachedPlan(String realm, String devEui,
                             DecoderRegistry.DecodePlan plan, String externalId,
                             Set<String> contestedNames) {}

    protected final DecoderRegistry registry = new DecoderRegistry();
    protected final Map<String, CachedPlan> planCache = new ConcurrentHashMap<>();
    // realm+devEui -> asset ids sharing it, to recompute contested fields across siblings
    protected final Map<String, Set<String>> assetIdsByEuiKey = new ConcurrentHashMap<>();
    protected AssetStorageService assetStorageService;
    protected AssetProcessingService assetProcessingService;
    protected ClientEventService clientEventService;
    protected MessageBrokerService messageBrokerService;

    @Override
    public void init(Container container) throws Exception {
        assetStorageService = container.getService(AssetStorageService.class);
        assetProcessingService = container.getService(AssetProcessingService.class);
        clientEventService = container.getService(ClientEventService.class);
        messageBrokerService = container.getService(MessageBrokerService.class);
    }

    @Override
    public void start(Container container) throws Exception {
        assetStorageService.findAll(new AssetQuery().types(
                registry.assetClasses().toArray(new Class[0])))
            .forEach(this::cacheAsset);
        LOG.info("IoT Watch decode plan cache warmed up (" + planCache.size() + " assets)");

        clientEventService.addSubscription(AttributeEvent.class,
            new AssetFilter<AttributeEvent>()
                .setAttributeNames(RAW_VALUE_ATTRIBUTE_NAME)
                .setAssetClasses(registry.assetClasses()),
            this::onRawValue);

        messageBrokerService.getContext().addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from(PersistenceService.PERSISTENCE_TOPIC)
                    .routeId("Persistence-IotWatchDecodePlan")
                    .filter(PersistenceService.isPersistenceEventForEntityType(Asset.class))
                    .process(exchange -> {
                        @SuppressWarnings("unchecked")
                        PersistenceEvent<Asset<?>> event =
                            exchange.getIn().getBody(PersistenceEvent.class);
                        onAssetChange(event);
                    });
            }
        });
    }

    @Override
    public void stop(Container container) throws Exception {
    }

    protected void onAssetChange(PersistenceEvent<Asset<?>> event) {
        Asset<?> asset = event.getEntity();
        switch (event.getCause()) {
            case DELETE -> removeAsset(asset.getId());
            case CREATE, UPDATE -> cacheAsset(asset);
        }
    }

    protected void cacheAsset(Asset<?> asset) {
        removeAsset(asset.getId());   // drop any stale grouping first (devEui may have changed)
        registry.planFor(asset).ifPresent(plan -> {
            String realm = asset.getRealm();
            String devEui = normaliseEui(asset.getAttributes()
                .get(IotWatchIngestService.DEV_EUI_ATTRIBUTE_NAME)
                .flatMap(a -> a.getValue(String.class)).orElse(null));
            String externalId = asset.getAttributes()
                .get(WaterMeterAsset.EXTERNAL_ID_ATTRIBUTE_DESCRIPTOR.getName())
                .flatMap(a -> a.getValue(String.class)).orElse(null);
            planCache.put(asset.getId(), new CachedPlan(realm, devEui, plan, externalId, Set.of()));
            if (devEui != null) {
                assetIdsByEuiKey.computeIfAbsent(key(realm, devEui), k -> ConcurrentHashMap.newKeySet())
                    .add(asset.getId());
                recomputeContested(realm, devEui);
            }
        });
    }

    protected void removeAsset(String assetId) {
        CachedPlan previous = planCache.remove(assetId);
        if (previous == null || previous.devEui() == null) {
            return;
        }
        String k = key(previous.realm(), previous.devEui());
        Set<String> ids = assetIdsByEuiKey.get(k);
        if (ids != null) {
            ids.remove(assetId);
            if (ids.isEmpty()) {
                assetIdsByEuiKey.remove(k, ids);
            }
        }
        recomputeContested(previous.realm(), previous.devEui());
    }

    // Recompute contestedNames for every asset sharing this realm+devEui: a field is contested
    // for asset A when at least one sibling B also has it in targetNames.
    protected void recomputeContested(String realm, String devEui) {
        Set<String> ids = assetIdsByEuiKey.getOrDefault(key(realm, devEui), Set.of());
        for (String id : ids) {
            CachedPlan cp = planCache.get(id);
            if (cp == null) {
                continue;
            }
            Set<String> contested = new HashSet<>();
            for (String other : ids) {
                if (!other.equals(id)) {
                    CachedPlan sibling = planCache.get(other);
                    if (sibling != null) {
                        for (String name : cp.plan().targetNames()) {
                            if (sibling.plan().targetNames().contains(name)) {
                                contested.add(name);
                            }
                        }
                    }
                }
            }
            planCache.put(id, new CachedPlan(cp.realm(), cp.devEui(), cp.plan(), cp.externalId(),
                Set.copyOf(contested)));
        }
    }

    private static String key(String realm, String devEui) {
        return realm + "|" + devEui;
    }

    private static String normaliseEui(String eui) {
        return (eui == null || eui.isBlank()) ? null : DevEuiCache.normalize(eui);
    }

    protected void onRawValue(AttributeEvent event) {
        CachedPlan cp = planCache.get(event.getId());
        if (cp == null) {
            cp = loadPlan(event.getId());
        }
        if (cp == null) {
            return;
        }
        Object raw = event.getValue().orElse(null);
        if (!(raw instanceof Map)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) raw;
        try {
            ReadingRouter.RouteResult rr = ReadingRouter.route(
                message, cp.externalId(), cp.plan().targetNames(), cp.contestedNames(),
                event.getTimestamp());
            rr.warnings().forEach(w -> LOG.warning("IoT Watch decode [" + event.getId() + "]: " + w));
            for (ReadingRouter.Routed routed : rr.routed()) {
                cp.plan().decoder()
                    .decode(event.getId(), routed.message(), routed.effectiveTargets(), routed.timestamp())
                    .forEach(this::dispatch);
            }
        } catch (Exception e) {
            LOG.warning("IoT Watch decode error [" + event.getId() + "]: " + e.getMessage());
        }
    }

    // Cache miss (rawValue arriving before the asset's persistence event): fetch, build, cache
    // via cacheAsset so the sibling grouping/contested set is populated too.
    protected CachedPlan loadPlan(String assetId) {
        Asset<?> asset = assetStorageService.find(assetId, true);
        if (asset == null) {
            return null;
        }
        cacheAsset(asset);
        return planCache.get(assetId);
    }

    protected void dispatch(AttributeEvent event) {
        assetProcessingService.sendAttributeEvent(event);
    }
}
