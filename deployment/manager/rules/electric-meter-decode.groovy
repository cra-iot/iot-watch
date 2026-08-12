package demo.rules

import org.openremote.manager.rules.RulesBuilder
import org.openremote.model.query.AssetQuery
import org.openremote.model.rules.Assets
import org.openremote.model.util.ValueUtil
import org.openremote.model.electricmeter.ElectricMeterAsset

import java.util.logging.Logger

Logger LOG = binding.LOG
RulesBuilder rules = binding.rules
Assets assets = binding.assets

// Attribute names declared by the ElectricMeterAsset model. Computed once from the model
// registry: the rules facade cannot return per-instance attributes (Assets.getResults
// forces excludeAttributes), so we filter decoded keys against the type descriptors.
Set<String> KNOWN = ValueUtil.getAssetInfo(ElectricMeterAsset.class)
        .map { it.getAttributeDescriptors().keySet() }
        .orElse(Collections.emptySet())

// Standalone electric meter: decode rawValue.data_decoded into the meter's own
// attributes. Only keys declared by the ElectricMeterAsset model are written; other
// decoder keys are ignored. A modelled key the operator didn't add to this instance is
// dispatched anyway and harmlessly dropped by the manager.
rules.add()
        .name("Electric meter → measurements (standalone)")
        .when({ facts ->
            def updates = []
            facts.matchAssetState(
                    new AssetQuery().types(ElectricMeterAsset).attributeName("rawValue")
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
                    LOG.warning("Electric meter rule error [${assetState.id}]: ${e.message}")
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
