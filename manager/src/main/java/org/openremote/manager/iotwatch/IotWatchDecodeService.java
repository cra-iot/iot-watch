package org.openremote.manager.iotwatch;

import org.apache.camel.builder.RouteBuilder;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.manager.asset.AssetProcessingService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.event.ClientEventService;
import org.openremote.manager.iotwatch.DecoderRegistry.DecodePlan;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetFilter;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.query.AssetQuery;

import java.util.Map;
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

    private static final Logger LOG = Logger.getLogger(IotWatchDecodeService.class.getName());

    protected final DecoderRegistry registry = new DecoderRegistry();
    protected final Map<String, DecodePlan> planCache = new ConcurrentHashMap<>();
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
            case DELETE -> planCache.remove(asset.getId());
            case CREATE, UPDATE -> cacheAsset(asset);
        }
    }

    protected void cacheAsset(Asset<?> asset) {
        registry.planFor(asset).ifPresentOrElse(
            plan -> planCache.put(asset.getId(), plan),
            () -> planCache.remove(asset.getId()));
    }

    protected void onRawValue(AttributeEvent event) {
        DecodePlan plan = planCache.get(event.getId());
        if (plan == null) {
            plan = loadPlan(event.getId());
        }
        if (plan == null) {
            return;
        }
        Object raw = event.getValue().orElse(null);
        if (!(raw instanceof Map)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) raw;
        try {
            plan.decoder().decode(event.getId(), message, plan.targetNames(), event.getTimestamp())
                .forEach(this::dispatch);
        } catch (Exception e) {
            LOG.warning("IoT Watch decode error [" + event.getId() + "]: " + e.getMessage());
        }
    }

    // Cache miss (a rawValue event arriving before the asset's persistence event was seen):
    // fetch the asset once, build and cache the plan. Rare; keeps steady state DB-free.
    protected DecodePlan loadPlan(String assetId) {
        Asset<?> asset = assetStorageService.find(assetId, true);
        if (asset == null) {
            return null;
        }
        DecodePlan plan = registry.planFor(asset).orElse(null);
        if (plan != null) {
            planCache.put(assetId, plan);
        }
        return plan;
    }

    protected void dispatch(AttributeEvent event) {
        assetProcessingService.sendAttributeEvent(event);
    }
}
