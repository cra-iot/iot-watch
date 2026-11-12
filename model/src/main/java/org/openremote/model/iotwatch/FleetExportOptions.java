package org.openremote.model.iotwatch;

import java.util.ArrayList;
import java.util.List;

/**
 * What the export dialog may offer for a search: the exportable meter types among the matched
 * devices, their register groups as the caller may read them, and the allowed periods.
 */
public class FleetExportOptions {

    public static class Group {
        /** The register group key, the column prefix, e.g. t1. */
        public String key;
        public String attribute;
        public String label;
        /** Header suffix such as kWh, empty when none. */
        public String unit = "";
    }

    public static class Type {
        public String type;
        /** Matched devices of this type. */
        public int deviceCount;
        /** The register groups reported for this type, in column order. */
        public List<Group> groups = new ArrayList<>();
    }

    public int maxDays = 366;
    /** The latest day a daily report may end on: yesterday in Europe/Prague, yyyy-MM-dd. */
    public String lastDay;
    /** The latest month a monthly report may end on: the previous month in Europe/Prague, yyyy-MM. */
    public String lastMonth;
    public int maxMonths = 24;
    public List<Type> types = new ArrayList<>();
}
