package org.openremote.manager.iotwatch;

import org.openremote.model.asset.Asset;
import org.openremote.model.electricmeter.ElectricMeterAsset;
import org.openremote.model.heatmeter.HeatMeterAsset;
import org.openremote.model.tracker.ShipTrackerAsset;
import org.openremote.model.tracker.TrackerAsset;
import org.openremote.model.watermeter.WaterMeterAsset;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maps each IoT Watch asset type to its decoder and builds the per-asset decode plan: the
 * decoder plus the attribute names that are both decodable for the type and actually
 * provisioned on the instance.
 */
public class DecoderRegistry {

    public record DecodePlan(DeviceDecoder decoder, Set<String> targetNames) {}

    private final Map<Class<?>, DeviceDecoder> byClass;

    public DecoderRegistry() {
        DeviceDecoder sameName = new SameNameDecoder();
        DeviceDecoder gnss = new GnssDecoder();
        byClass = Map.of(
            WaterMeterAsset.class, sameName,
            ElectricMeterAsset.class, sameName,
            HeatMeterAsset.class, sameName,
            TrackerAsset.class, gnss,
            ShipTrackerAsset.class, gnss);
    }

    /** Asset classes to narrow the event subscription to (Task 4). */
    public List<Class<? extends Asset>> assetClasses() {
        return List.of(WaterMeterAsset.class, ElectricMeterAsset.class, HeatMeterAsset.class,
            TrackerAsset.class, ShipTrackerAsset.class);
    }

    public Optional<DecodePlan> planFor(Asset<?> asset) {
        DeviceDecoder decoder = byClass.get(asset.getClass());
        if (decoder == null) {
            return Optional.empty();
        }
        Set<String> provisioned = asset.getAttributes().keySet();
        Set<String> targetNames = decoder.candidateAttributeNames(asset).stream()
            .filter(provisioned::contains)
            .collect(Collectors.toUnmodifiableSet());
        if (targetNames.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DecodePlan(decoder, targetNames));
    }
}
