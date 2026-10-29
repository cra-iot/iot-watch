package org.openremote.model.iotwatch;

import java.util.List;

/**
 * Selection input for the fleet overview. Deliberately independent of
 * {@link org.openremote.model.query.AssetQuery}: it is the wire contract the UI search bar
 * produces, and FleetQueryTranslator turns it into one or two AssetQuery objects.
 */
public class FleetQuery {

    /** Operators accepted on an attribute filter; mirrors AssetQuery's operator set. */
    public enum Operator {
        EQUALS, NOT_EQUALS, CONTAINS, STARTS_WITH,
        LESS_THAN, LESS_EQUALS, GREATER_THAN, GREATER_EQUALS, BETWEEN
    }

    public enum GroupBy { NONE, PARENT, TYPE, STATUS }

    public static class AttributeFilter {
        public String name;
        public Operator operator = Operator.EQUALS;
        public Object value;
        /** Upper bound for BETWEEN; ignored otherwise. */
        public Object rangeValue;
        public boolean negate;
    }

    public static class SeenFilter {
        /** ISO-8601 duration, e.g. PT24H. Matches assets whose lastSeen is older, including never. */
        public String olderThan;
        /** ISO-8601 duration. Matches assets whose lastSeen is newer; never-seen assets never match. */
        public String newerThan;
    }

    public static class Sort {
        public String field = "name";
        public boolean descending;
    }

    /**
     * Free text values, ORed: an asset matches when any value is contained in its name, delivery
     * point, serial, devEUI or external id. Repeated attribute filters on the same attribute are
     * ORed too (see FleetQueryTranslator.anyOfFilters); everything else is ANDed.
     */
    public List<String> text;
    public List<String> types;
    public List<String> parentIds;
    public boolean recursive;
    public List<AttributeFilter> attributes;
    public SeenFilter seen;
    /** Row status keys ("alarm", "silent", "ok"), ORed; null or empty means every status. */
    public List<String> status;
    public GroupBy groupBy = GroupBy.NONE;
    /**
     * Group keys (of groupBy) the user collapsed. Their rows stay in total and facets but leave
     * the paging, so the rows of the open groups fill the pages; ignored without grouping.
     */
    public List<String> collapsed;
    public Sort sort = new Sort();

    /**
     * Attribute names the caller wants in every row, on top of the type's own primary register.
     * Null or empty means "whatever FleetColumnResolver resolves for the types in the result",
     * which is what the page sends until the user touches the column picker.
     */
    public List<String> columns;

    public int offset;
    /** Zero means "use the server default". */
    public int limit;

    /**
     * Realm to search; null or empty means the request realm. Lets a super user's realm picker
     * target another realm, as AssetQuery.realm does; authorizeAssetQuery still rejects any
     * other caller whose own realm differs.
     */
    public String realm;
}
