package org.openremote.model.iotwatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One device in the overview. Brief by design: detail lives in the standard asset view. */
public class FleetRow {

    public String id;
    public String name;
    public String type;
    public String parentId;
    /**
     * Name of the parent asset, for location context. Null when the caller may not read the
     * parent (a restricted user linked to the meter but not to its building).
     */
    public String parentName;
    /** Ancestor asset ids, root first. Ids, not names - never render these. */
    public List<String> path = new ArrayList<>();

    public String deliveryPointId;
    public String meterId;
    public String devEui;

    /**
     * Attribute name of this row's primary register - the one the mixed-type "Reading" column
     * shows. Null when the type has none, which is normal for a tracker or a plain ThingAsset.
     */
    public String primaryName;

    /**
     * Values of the resolved columns, by attribute name. A missing key means the asset has no
     * such attribute; a null value means it has one that never received a value. The UI renders
     * an em dash for both, and must never distinguish them visually.
     */
    public Map<String, Object> values = new LinkedHashMap<>();

    public Integer batteryLevel;
    /** Epoch millis of the primary register's timestamp; null when it never reported. */
    public Long lastReading;
    /**
     * Epoch millis of rawValue's timestamp - last communication; null when it never reported.
     * Filled from a server-side lookup, because rawValue itself is hidden from restricted users.
     */
    public Long lastSeen;

    /**
     * Names of alarm BOOLEAN attributes whose current value is true; state booleans listed in
     * FleetColumns.STATE_BOOLEANS are excluded. Absent on the wire when empty.
     */
    public List<String> alarms = new ArrayList<>();
}
