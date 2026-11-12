package org.openremote.model.iotwatch;

/**
 * A fleet CSV export: the devices of one meter type the page's current search matches, one row
 * per device and month (MONTHLY) or day (DAILY) over an inclusive range, in Europe/Prague.
 */
public class FleetExportRequest {

    /** The page's current search; paging and grouping are ignored, every match is exported. */
    public FleetQuery query;
    /** The one meter type exported, e.g. WaterMeterAsset. */
    public String assetType;
    public FleetExportReport report;
    /** First period, yyyy-MM-dd; for MONTHLY the first day of the first month. */
    public String from;
    /** Last period, yyyy-MM-dd, inclusive; for MONTHLY the first day of the last month. */
    public String to;
}
