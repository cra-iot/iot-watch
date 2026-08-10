package demo.rules

import org.openremote.manager.rules.RulesBuilder
import org.openremote.model.query.AssetQuery
import org.openremote.model.rules.Assets
import org.openremote.model.electricmeter.ElectricMeterAsset
import org.openremote.model.electricmeter.ElectricMeterGatewayAsset

import java.util.logging.Logger

Logger LOG = binding.LOG
RulesBuilder rules = binding.rules
Assets assets = binding.assets

// PROVISIONAL payload contract — reconcile with the decoder revision:
//   data_decoded.<METERS_FIELD> : list of per-meter blocks
//   each block: [ (METER_SERIAL_FIELD): "<serial>", "<snake_case_key>": <number>, ... ]
final String METERS_FIELD = "meters"
final String METER_SERIAL_FIELD = "serial"

// Concentrator: one rawValue holds several meters. Fan out each meter block to the
// child ElectricMeterAsset whose meterId matches the block serial. Only keys that
// exist as attributes on that child are written.
rules.add()
        .name("Electric meter gateway → child meters (fan-out)")
        .when({ facts ->
            def updates = []
            facts.matchAssetState(
                    new AssetQuery().types(ElectricMeterGatewayAsset).attributeName("rawValue")
            ).toList().forEach { gwState ->
                String fireKey = "lastFire_${gwState.id}".toString()
                Optional<Long> last = facts.getOptional(fireKey)
                if (last.isPresent() && gwState.getTimestamp() <= last.get()) return
                facts.put(fireKey, gwState.getTimestamp())
                try {
                    def payload = gwState.getValue().orElse(null)
                    def decoded = payload instanceof Map ? payload["data_decoded"] : null
                    if (!(decoded instanceof Map)) return
                    def meters = decoded[METERS_FIELD]
                    def blocks = (meters instanceof Object[]) ? (meters as List)
                            : (meters instanceof Iterable ? meters.toList() : [])
                    if (blocks.isEmpty()) return

                    // index this gateway's child meters by meterId
                    def children = assets.getResults(
                            new AssetQuery().parents((String) gwState.id).types(ElectricMeterAsset)
                    ).toList()
                    def byMeterId = [:]
                    children.each { child ->
                        child.getAttributes().get("meterId")
                                .flatMap { it.getValue(String.class) }
                                .ifPresent { serial ->
                                    if (byMeterId.containsKey(serial)) LOG.warning("Gateway ${gwState.id}: duplicate meterId ${serial} across child meters — overwriting")
                                    byMeterId[serial] = child
                                }
                    }

                    blocks.each { block ->
                        if (!(block instanceof Map)) return
                        def serialRaw = block[METER_SERIAL_FIELD]
                        if (serialRaw == null) return
                        def child = byMeterId[serialRaw.toString()]
                        if (child == null) {
                            LOG.info("Gateway ${gwState.id}: no child meter with meterId=${serialRaw}")
                            return
                        }
                        def attrs = child.getAttributes()
                        block.each { k, v ->
                            String name = (String) k
                            if (name == METER_SERIAL_FIELD) return
                            if (v != null && attrs.get(name).isPresent()) {
                                updates.add([id: child.id, name: name, value: v])
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.warning("Electric meter gateway rule error [${gwState.id}]: ${e.message}")
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
