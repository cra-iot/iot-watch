package demo.rules

import org.openremote.manager.rules.RulesBuilder
import org.openremote.model.query.AssetQuery
import org.openremote.model.rules.Assets
import org.openremote.model.util.ValueUtil
import org.openremote.model.watermeter.WaterMeterAsset

import java.util.logging.Logger

Logger LOG = binding.LOG
RulesBuilder rules = binding.rules
Assets assets = binding.assets

// Attribute names declared by the WaterMeterAsset model (currentReading, battery_level,
// every measurement/alarm, …). Computed once from the model registry: the rules facade
// cannot return per-instance attributes (Assets.getResults forces excludeAttributes), so
// we filter decoded keys against the type descriptors instead.
Set<String> KNOWN = ValueUtil.getAssetInfo(WaterMeterAsset.class)
        .map { it.getAttributeDescriptors().keySet() }
        .orElse(Collections.emptySet())

// Standalone water meter: decode rawValue.data_decoded into the meter's own attributes.
// Only keys declared by the WaterMeterAsset model are written; other decoder keys
// (datetime, diagnostics, …) are ignored. A modelled key the operator didn't add to
// this instance is dispatched anyway and harmlessly dropped by the manager. The platform
// decoder emits final values, including the alarm booleans (e.g. "leakage": true).
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

                    decoded.each { k, v ->
                        String name = (String) k
                        if (v != null && KNOWN.contains(name)) {
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
