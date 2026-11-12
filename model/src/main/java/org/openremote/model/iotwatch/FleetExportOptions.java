package org.openremote.model.iotwatch;

import java.util.ArrayList;
import java.util.List;

/**
 * What the export dialog may offer for a search: the exportable asset types among the matched
 * devices, their attributes as the caller may read them, and the allowed date range.
 */
public class FleetExportOptions {

    public static class Attribute {
        public String name;
        /** The descriptor's LABEL meta, else the name. */
        public String label;
        public boolean defaultSelected;
    }

    public static class Type {
        public String type;
        /** Matched devices of this type. */
        public int deviceCount;
        /** Attributes exported with their current value. */
        public List<Attribute> identity = new ArrayList<>();
        /** Attributes with stored history, exported as the value at the end of each day. */
        public List<Attribute> measurements = new ArrayList<>();
    }

    /** The latest day an export may end on: yesterday in UTC, yyyy-MM-dd. */
    public String maxTo;
    public int maxDays = 366;
    public List<Type> types = new ArrayList<>();
}
