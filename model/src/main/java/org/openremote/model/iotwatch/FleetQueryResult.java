package org.openremote.model.iotwatch;

import java.util.ArrayList;
import java.util.List;

public class FleetQueryResult {

    public static class Facet {
        public String key;
        /** Human label when one exists (parent asset name); null means the UI resolves it. */
        public String label;
        public int count;

        public Facet() {
        }

        public Facet(String key, String label, int count) {
            this.key = key;
            this.label = label;
            this.count = count;
        }
    }

    public static class Facets {
        public List<Facet> types = new ArrayList<>();
        public List<Facet> alarms = new ArrayList<>();
        public List<Facet> status = new ArrayList<>();
        public List<Facet> parents = new ArrayList<>();
    }

    /**
     * One offerable column. The server resolves these from the asset model and sends them with
     * the result, so the UI needs no copy of the model: it renders the selected ones and puts
     * the rest in the column picker.
     */
    public static class ColumnDescriptor {
        /** Attribute name; the key into {@link FleetRow#values}. */
        public String name;
        /** The descriptor's LABEL meta, already in the model's language. */
        public String label;
        /**
         * OpenRemote unit keys, e.g. ["metre", "cubed"]; the UI formats them with
         * Util.resolveUnits. Sent with the column so the UI needs no asset-model lookup.
         */
        public String[] units;
        public boolean numeric;
        /** True for the type's primary register - what the mixed-type "Reading" column shows. */
        public boolean primary;
        /** True when this column is in the current result; false means "offered, not selected". */
        public boolean selected;
        /** The asset type that offers it; two types may offer the same name. */
        public String assetType;
    }

    /** Matches before paging. */
    public int total;
    public int offset;
    public int limit;
    public List<FleetRow> rows = new ArrayList<>();
    public Facets facets = new Facets();
    /** Every column the types in this result offer, selected ones first. */
    public List<ColumnDescriptor> columns = new ArrayList<>();
}
