package demo.rules

import org.openremote.manager.rules.RulesBuilder
import org.openremote.model.query.AssetQuery
import org.openremote.model.rules.Assets
import org.openremote.model.geo.GeoJSONPoint
import org.openremote.model.tracker.TrackerAsset

import java.util.logging.Logger

Logger LOG = binding.LOG
RulesBuilder rules = binding.rules
Assets assets = binding.assets

rules.add()
        .name("Tracker LoRaWAN GNSS → Location")
        .when({
            facts ->
                def updates = []
                facts.matchAssetState(
                        new AssetQuery()
                                .types(TrackerAsset)
                                .attributeName("rawValue")
                ).toList().forEach { assetState ->
                    String key = "lastFire_${assetState.id}".toString()
                    Optional<Long> last = facts.getOptional(key)
                    if (last.isPresent() && assetState.getTimestamp() <= last.get()) return
                    facts.put(key, assetState.getTimestamp())

                    try {
                        def payload = assetState.getValue().orElse(null)
                        def decoded = payload instanceof Map ? payload["data_decoded"] : null
                        if (!(decoded instanceof Map)) return

                        def u = [id: assetState.id]
                        if (decoded["gnss_latitude"] != null && decoded["gnss_longitude"] != null) {
                            u.gnssLat = ((Number) decoded["gnss_latitude"]).doubleValue()
                            u.gnssLon = ((Number) decoded["gnss_longitude"]).doubleValue()
                        }
                        if (decoded["battery_pct"] != null) u.battery = ((Number) decoded["battery_pct"]).doubleValue()
                        if (u.gnssLat != null || u.battery != null) updates.add(u)
                    } catch (Exception e) {
                        LOG.warning("Tracker rule error [${assetState.id}]: ${e.message}")
                    }
                }

                if (!updates.isEmpty()) {
                    facts.bind("updates", updates); return true
                }
                false
        })
        .then({
            facts ->
                facts.bound("updates").each { u ->
                    String id = u.id
                    if (u.gnssLat != null) assets.dispatch(id, "location", new GeoJSONPoint((double) u.gnssLon, (double) u.gnssLat))
                    if (u.battery != null) assets.dispatch(id, "battery", (double) u.battery)
                    if (u.gnssLat != null) LOG.info("Tracker ${id} → [${u.gnssLat}, ${u.gnssLon}] bat=${u.battery}%")
                }
        })
