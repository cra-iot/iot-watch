package org.openremote.model.iotwatch;

import java.util.List;
import java.util.Map;

/**
 * A fleet CSV export: the devices the page's current search matches, one row per device and day
 * over an inclusive range of calendar days, with the chosen attributes per asset type.
 */
public class FleetExportRequest {

    /** The page's current search; paging and grouping are ignored, every match is exported. */
    public FleetQuery query;
    /** The one meter type exported, e.g. WaterMeterAsset. */
    public String assetType;
    public FleetExportReport report;
    /** First day, yyyy-MM-dd; for MONTHLY the first day of the first month. */
    public String from;
    /** Last day, yyyy-MM-dd, inclusive; for MONTHLY the first day of the last month. */
    public String to;
    /** Asset type to the attribute names selected for it. */
    public Map<String, List<String>> attributes;
    /** Optional IANA region id the days are cut in; UTC when absent. API only, the UI sends none. */
    public String timeZone;
}
