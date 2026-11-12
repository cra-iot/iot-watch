package org.openremote.model.iotwatch;

import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The declared primary registers: the one attribute that stands for "the reading" on a type this
 * project knows about. It lives in model/ beside the asset classes, so adding a meter type is a
 * one-line edit in the module that already defines it and neither manager/ nor ui/ holds a copy.
 *
 * A type absent from this map is not unsupported. FleetColumnResolver falls back to the asset
 * model, and the user can override the column set in the UI; declaring a type only removes the
 * guess. Never add a lookup here for an attribute the model could answer for itself.
 */
public final class FleetColumns {

    public static final Map<String, String> PRIMARY_REGISTERS;

    static {
        Map<String, String> registers = new LinkedHashMap<>();
        registers.put("WaterMeterAsset", "currentReading");
        registers.put("GasMeterAsset", "volume");
        registers.put("ElectricMeterAsset", "energy_active_total");
        registers.put("HeatMeterAsset", "energy_heating");
        registers.put("PassageCounterAsset", "period_in");
        PRIMARY_REGISTERS = Collections.unmodifiableMap(registers);
    }

    /** Export register groups per type: group key to the attribute it reads, in column order. */
    public static final Map<String, Map<String, String>> EXPORT_REGISTERS;

    /** Identity attributes exported with their current value, in column order. */
    public static final List<String> EXPORT_IDENTITY = List.of("meter_id", "devEui", "external_id", "delivery_point_id");

    static {
        Map<String, Map<String, String>> exports = new LinkedHashMap<>();
        exports.put("WaterMeterAsset", groups("volume", "currentReading"));
        exports.put("GasMeterAsset", groups("volume", "volume", "volume_standard", "volume_standard"));
        exports.put("ElectricMeterAsset", groups("total", "energy_active_total", "t1", "energy_active_t1", "t2", "energy_active_t2"));
        exports.put("HeatMeterAsset", groups("energy", "energy_heating", "t1", "energy_tariff_1", "t2", "energy_tariff_2"));
        exports.put("PassageCounterAsset", groups("in", "total_in", "out", "total_out"));
        EXPORT_REGISTERS = Collections.unmodifiableMap(exports);
    }

    private static Map<String, String> groups(String... keyAndAttribute) {
        Map<String, String> groups = new LinkedHashMap<>();
        for (int i = 0; i < keyAndAttribute.length; i += 2) {
            groups.put(keyAndAttribute[i], keyAndAttribute[i + 1]);
        }
        return Collections.unmodifiableMap(groups);
    }

    /**
     * Attributes fetched for every row whatever the type: identity and the battery. These are
     * genuinely cross-type - every meter asset in this project declares them under the same
     * names - which is the only reason they may be a constant.
     *
     * rawValue is deliberately absent: it is hidden from restricted users and its value must
     * never be loaded into a row. FleetResourceImpl reads its timestamp in a separate lookup.
     */
    public static final String[] INFRASTRUCTURE_ATTRIBUTES = {
            "delivery_point_id", "meter_id", "devEui", "external_id", "battery_level"
    };

    /** The ingest marker; its timestamp is the row's lastSeen. Never selected into a row. */
    public static final String RAW_VALUE = "rawValue";

    /**
     * Boolean attributes that report a state, not a fault, so a true value is not an alarm.
     * Every other boolean on an asset is treated as an alarm flag. Add a name here when a new
     * type declares a state boolean; never encode alarms themselves as a list.
     *
     * active: a tracker's assignment flag, true whenever the tracker is in use.
     */
    public static final Set<String> STATE_BOOLEANS = Set.of("valve_closed", "active");

    private FleetColumns() {
    }
}
