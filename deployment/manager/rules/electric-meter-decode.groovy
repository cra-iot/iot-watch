package demo.rules

import org.openremote.manager.rules.RulesBuilder
import org.openremote.model.query.AssetQuery
import org.openremote.model.rules.Assets
import org.openremote.model.electricmeter.ElectricMeterAsset
import org.openremote.model.value.AttributeDescriptor

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.logging.Logger

Logger LOG = binding.LOG
RulesBuilder rules = binding.rules
Assets assets = binding.assets

// Attribute names declared by the ElectricMeterAsset model. Read from the class's own
// static AttributeDescriptor fields rather than ValueUtil.getAssetInfo(...).
// getAttributeDescriptors(), whose map also includes the inherited base-Asset attributes
// (name, location, notes, …) — a decoder key colliding with one of those would otherwise
// be written to the core attribute. The rules facade cannot return per-instance attributes
// (Assets.getResults forces excludeAttributes), so this comes from the model, not the asset.
Set<String> KNOWN = ElectricMeterAsset.class.getDeclaredFields()
        .findAll { Field f -> Modifier.isStatic(f.modifiers) && AttributeDescriptor.isAssignableFrom(f.type) }
        .collect { Field f -> ((AttributeDescriptor) f.get(null)).getName() }
        .toSet()

// Standalone electric meter: decode rawValue.data_decoded into the meter's own attributes.
// Only keys declared by the ElectricMeterAsset model are written; other decoder keys are
// ignored. A modelled key the operator did not add to this instance is still dispatched,
// then logged at WARNING (ATTRIBUTE_NOT_FOUND) and discarded — keys that do exist still
// land, so provision only the attributes the device reports to avoid warning spam.
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
                    if (!(decoded instanceof Map)) return   // no decoded payload → nothing to write

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
