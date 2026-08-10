package demo.rules

import org.openremote.manager.rules.RulesBuilder
import org.openremote.model.query.AssetQuery
import org.openremote.model.rules.Assets
import org.openremote.model.watermeter.WaterMeterAsset

import java.util.logging.Logger

Logger LOG = binding.LOG
RulesBuilder rules = binding.rules
Assets assets = binding.assets

// Standalone water meter: decode rawValue.data_decoded into the meter's own attributes.
// Only keys that already exist as attributes on the asset are written (the operator
// picks the subset the meter reports); unknown keys are ignored so the manager never
// rejects an event for a missing attribute. The platform decoder emits final values,
// including the alarm booleans (e.g. "leakage": true) — no bit-cracking happens here.
rules.add()
        .name("Water meter → measurements (standalone)")
        .when({ facts ->
            def updates = []
            facts.matchAssetState(
                    new AssetQuery().types(WaterMeterAsset).attributeName("rawValue")
            ).toList().forEach { assetState ->
                String fireKey = "lastFire_${assetState.id}".toString()
                Optional<Long> last = facts.getOptional(fireKey)
                if (last.isPresent() && assetState.getTimestamp() <= last.get()) return
                facts.put(fireKey, assetState.getTimestamp())
                try {
                    def payload = assetState.getValue().orElse(null)
                    def decoded = payload instanceof Map ? payload["data_decoded"] : null
                    if (!(decoded instanceof Map)) return   // child meters (empty rawValue) fall out here

                    def asset = assets.getResults(new AssetQuery().ids((String) assetState.id)).findFirst().orElse(null)
                    if (asset == null) return
                    def attrs = asset.getAttributes()
                    decoded.each { k, v ->
                        String name = (String) k
                        if (v != null && attrs.get(name).isPresent()) {
                            updates.add([id: assetState.id, name: name, value: v])
                        }
                    }
                } catch (Exception e) {
                    LOG.warning("Water meter rule error [${assetState.id}]: ${e.message}")
                }
            }
            if (!updates.isEmpty()) { facts.bind("updates", updates); return true }
            false
        })
        .then({ facts ->
            facts.bound("updates").each { u ->
                assets.dispatch((String) u.id, (String) u.name, u.value)
            }
        })
