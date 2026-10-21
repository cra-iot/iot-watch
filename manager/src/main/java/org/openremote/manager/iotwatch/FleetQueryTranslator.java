package org.openremote.manager.iotwatch;

import java.util.ArrayList;
import java.util.List;

import org.openremote.model.iotwatch.FleetQuery;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.LogicGroup;
import org.openremote.model.query.filter.AttributePredicate;
import org.openremote.model.query.filter.BooleanPredicate;
import org.openremote.model.query.filter.NumberPredicate;
import org.openremote.model.query.filter.ParentPredicate;
import org.openremote.model.query.filter.StringPredicate;
import org.openremote.model.query.filter.ValuePredicate;

/**
 * Turns a {@link FleetQuery} into the AssetQuery objects the storage service understands.
 *
 * Free text needs two queries because AssetQuery.names and AssetQuery.attributes are separate
 * fields combined with AND by the SQL builder, so "name OR identifier" cannot be expressed once.
 * The caller unions the two result sets by asset id.
 */
public final class FleetQueryTranslator {

    /** Identity attributes searched by free text, in the order the OR group is built. */
    public static final String[] IDENTIFIER_ATTRIBUTES = {
            "delivery_point_id", "meter_id", "devEui", "external_id"
    };

    private FleetQueryTranslator() {
    }

    /**
     * The query carrying only the tokens; used when there is no free text. {@code select} comes
     * from {@link FleetColumnResolver#selectAttributes}: an unrestricted select loads every
     * attribute of every asset, and a hardcoded one silently drops the alarms and any column the
     * user added in the picker.
     */
    public static AssetQuery filterQuery(FleetQuery fleetQuery, String realm, String[] select) {
        return baseQuery(fleetQuery, realm, select, null);
    }

    /** Tokens plus a name CONTAINS match, or null when there is no free text. */
    public static AssetQuery nameTextQuery(FleetQuery fleetQuery, String realm, String[] select) {
        if (isBlank(fleetQuery.text)) {
            return null;
        }
        AssetQuery query = baseQuery(fleetQuery, realm, select, null);
        query.names = new StringPredicate[]{
                new StringPredicate(AssetQuery.Match.CONTAINS, false, fleetQuery.text)
        };
        return query;
    }

    /** Tokens plus an OR over the identity attributes, or null when there is no free text. */
    public static AssetQuery identifierTextQuery(FleetQuery fleetQuery, String realm, String[] select) {
        if (isBlank(fleetQuery.text)) {
            return null;
        }
        LogicGroup<AttributePredicate> identifierGroup = new LogicGroup<>();
        identifierGroup.operator = LogicGroup.Operator.OR;
        List<AttributePredicate> items = new ArrayList<>();
        for (String name : IDENTIFIER_ATTRIBUTES) {
            items.add(new AttributePredicate(
                    new StringPredicate(AssetQuery.Match.EXACT, false, name),
                    new StringPredicate(AssetQuery.Match.CONTAINS, false, fleetQuery.text)));
        }
        identifierGroup.items = items;
        return baseQuery(fleetQuery, realm, select, identifierGroup);
    }

    private static AssetQuery baseQuery(FleetQuery fleetQuery, String realm, String[] select,
                                        LogicGroup<AttributePredicate> extraGroup) {
        AssetQuery query = new AssetQuery();
        query.realm(new org.openremote.model.query.filter.RealmPredicate(realm));
        query.select(new AssetQuery.Select().attributes(select));

        // An empty type filter means "every device type", not "every asset": buildings and
        // agents are not devices. The same set FleetColumnResolver resolves the select over.
        query.types = (fleetQuery.types != null && !fleetQuery.types.isEmpty()
                ? fleetQuery.types
                : FleetColumnResolver.deviceTypes()).toArray(new String[0]);
        if (fleetQuery.parentIds != null && !fleetQuery.parentIds.isEmpty()) {
            ParentPredicate[] parents = fleetQuery.parentIds.stream()
                    .map(ParentPredicate::new)
                    .toArray(ParentPredicate[]::new);
            query.parents = parents;
            query.recursive = fleetQuery.recursive;
        }

        LogicGroup<AttributePredicate> group = new LogicGroup<>();
        group.operator = LogicGroup.Operator.AND;
        List<AttributePredicate> items = new ArrayList<>();
        if (fleetQuery.attributes != null) {
            for (FleetQuery.AttributeFilter filter : fleetQuery.attributes) {
                AttributePredicate predicate = new AttributePredicate(
                        new StringPredicate(AssetQuery.Match.EXACT, false, filter.name),
                        toValuePredicate(filter));
                predicate.negated = filter.negate;
                items.add(predicate);
            }
        }
        if (items.isEmpty() && extraGroup != null) {
            // No tokens: the identifier OR is the whole condition, no AND wrapper needed.
            query.attributes = extraGroup;
            return query;
        }
        if (!items.isEmpty()) {
            group.items = items;
            if (extraGroup != null) {
                group.groups = new ArrayList<>(List.of(extraGroup));
            }
            query.attributes = group;
        }
        return query;
    }

    private static ValuePredicate toValuePredicate(FleetQuery.AttributeFilter filter) {
        if (filter.value instanceof Boolean booleanValue) {
            return new BooleanPredicate(booleanValue);
        }
        if (filter.value instanceof Number number) {
            NumberPredicate predicate = new NumberPredicate(number.doubleValue());
            predicate.operator = toAssetQueryOperator(filter.operator);
            if (filter.rangeValue instanceof Number rangeNumber) {
                predicate.rangeValue = rangeNumber.doubleValue();
            }
            return predicate;
        }
        String text = filter.value == null ? "" : filter.value.toString();
        return new StringPredicate(toMatch(filter.operator), false, text);
    }

    private static AssetQuery.Operator toAssetQueryOperator(FleetQuery.Operator operator) {
        // An explicit null on the wire overrides the field default; it means EQUALS.
        return switch (operator == null ? FleetQuery.Operator.EQUALS : operator) {
            case LESS_THAN -> AssetQuery.Operator.LESS_THAN;
            case LESS_EQUALS -> AssetQuery.Operator.LESS_EQUALS;
            case GREATER_THAN -> AssetQuery.Operator.GREATER_THAN;
            case GREATER_EQUALS -> AssetQuery.Operator.GREATER_EQUALS;
            case BETWEEN -> AssetQuery.Operator.BETWEEN;
            default -> AssetQuery.Operator.EQUALS;
        };
    }

    private static AssetQuery.Match toMatch(FleetQuery.Operator operator) {
        return switch (operator == null ? FleetQuery.Operator.EQUALS : operator) {
            case CONTAINS -> AssetQuery.Match.CONTAINS;
            case STARTS_WITH -> AssetQuery.Match.BEGIN;
            default -> AssetQuery.Match.EXACT;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
