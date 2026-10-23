package org.openremote.manager.iotwatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.openremote.model.asset.Asset;
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
 *
 * Same kind ORs, different kinds AND: several free-text values are ORed, and so are repeated
 * text filters on one attribute ({@link #anyOfFilters}). The free-text OR goes into SQL; the
 * attribute OR does not, because AssetStorageService (1.32.1) gives sibling attribute groups the
 * same jsonb_each alias, so an AND of two OR groups is invalid SQL. The caller applies
 * {@link #filterByAnyOf} to the authorized result instead.
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

    /**
     * Tokens plus a name CONTAINS match, or null when there is no free text. When
     * {@link #matchesNameInJava} is true the name match is left out of the query and the caller
     * applies {@link #filterByName} to its result instead.
     */
    public static AssetQuery nameTextQuery(FleetQuery fleetQuery, String realm, String[] select) {
        List<String> texts = texts(fleetQuery);
        if (texts.isEmpty()) {
            return null;
        }
        AssetQuery query = baseQuery(fleetQuery, realm, select, null);
        if (!matchesNameInJava(fleetQuery)) {
            // AssetQuery.names are ORed by the SQL builder.
            query.names = texts.stream()
                    .map(text -> new StringPredicate(AssetQuery.Match.CONTAINS, false, text))
                    .toArray(StringPredicate[]::new);
        }
        return query;
    }

    /**
     * True when the free-text name match cannot go into SQL. AssetStorageService (1.32.1) applies
     * AssetQuery.names only to the first level of a recursive parent query, so combined with a
     * site the names would match the site's direct children and return everything below a
     * matching child - a name match at the wrong level.
     */
    public static boolean matchesNameInJava(FleetQuery fleetQuery) {
        return !texts(fleetQuery).isEmpty() && fleetQuery.parentIds != null && !fleetQuery.parentIds.isEmpty();
    }

    /**
     * The assets whose name contains any of {@code texts}, ignoring case: the Java equivalent of
     * the name predicates {@link #nameTextQuery} would otherwise send. Applied only to assets an
     * authorized query returned.
     */
    public static List<Asset<?>> filterByName(List<Asset<?>> assets, List<String> texts) {
        List<String> needles = texts.stream().map(text -> text.toUpperCase(Locale.ROOT)).toList();
        return assets.stream()
                .filter(asset -> asset.getName() != null
                        && needles.stream().anyMatch(asset.getName().toUpperCase(Locale.ROOT)::contains))
                .toList();
    }

    /** The non-blank free-text values, trimmed. */
    public static List<String> texts(FleetQuery fleetQuery) {
        if (fleetQuery.text == null) {
            return List.of();
        }
        return fleetQuery.text.stream()
                .filter(text -> !isBlank(text))
                .map(String::trim)
                .distinct()
                .toList();
    }

    /**
     * Attribute filters to OR rather than AND, by attribute name (lower case): two or more
     * non-negated text filters (EQUALS, CONTAINS, STARTS_WITH) on the same attribute, e.g. two
     * devEUIs. Numbers, booleans and negations keep AND: two bounds on one number are a range,
     * and "not A and not B" is what two exclusions mean.
     */
    public static Map<String, List<FleetQuery.AttributeFilter>> anyOfFilters(FleetQuery fleetQuery) {
        Map<String, List<FleetQuery.AttributeFilter>> byName = new LinkedHashMap<>();
        if (fleetQuery.attributes == null) {
            return byName;
        }
        for (FleetQuery.AttributeFilter filter : fleetQuery.attributes) {
            if (isAnyOfCandidate(filter)) {
                byName.computeIfAbsent(filter.name.trim().toLowerCase(Locale.ROOT), name -> new ArrayList<>())
                        .add(filter);
            }
        }
        byName.values().removeIf(filters -> filters.size() < 2);
        return byName;
    }

    /** The select plus the attributes {@link #filterByAnyOf} reads, which it cannot match unloaded. */
    public static String[] withAnyOfAttributes(String[] select, FleetQuery fleetQuery) {
        Set<String> names = new LinkedHashSet<>(Arrays.asList(select));
        anyOfFilters(fleetQuery).values().forEach(filters -> names.add(filters.get(0).name.trim()));
        return names.toArray(new String[0]);
    }

    /**
     * The assets that satisfy every {@link #anyOfFilters} group: for each group, the attribute
     * matches at least one of its filters. Attribute names match ignoring case, as in SQL.
     * Applied only to assets an authorized query returned.
     */
    public static List<Asset<?>> filterByAnyOf(List<Asset<?>> assets, FleetQuery fleetQuery) {
        Map<String, List<FleetQuery.AttributeFilter>> groups = anyOfFilters(fleetQuery);
        if (groups.isEmpty()) {
            return assets;
        }
        Map<String, List<Predicate<Object>>> predicates = new LinkedHashMap<>();
        groups.forEach((name, filters) -> predicates.put(name, filters.stream()
                .map(filter -> ((StringPredicate) toValuePredicate(filter)).asPredicate(System::currentTimeMillis))
                .toList()));
        return assets.stream()
                .filter(asset -> predicates.entrySet().stream().allMatch(group -> {
                    Object value = attributeValue(asset, group.getKey());
                    return value != null && group.getValue().stream().anyMatch(predicate -> predicate.test(value));
                }))
                .toList();
    }

    private static boolean isAnyOfCandidate(FleetQuery.AttributeFilter filter) {
        if (filter == null || filter.name == null || filter.negate || !(filter.value instanceof String)) {
            return false;
        }
        FleetQuery.Operator operator = filter.operator == null ? FleetQuery.Operator.EQUALS : filter.operator;
        return operator == FleetQuery.Operator.EQUALS
                || operator == FleetQuery.Operator.CONTAINS
                || operator == FleetQuery.Operator.STARTS_WITH;
    }

    private static Object attributeValue(Asset<?> asset, String lowerCaseName) {
        return asset.getAttributes().values().stream()
                .filter(attribute -> attribute.getName().toLowerCase(Locale.ROOT).equals(lowerCaseName))
                .findFirst()
                .flatMap(attribute -> attribute.getValue())
                .orElse(null);
    }

    /**
     * Tokens plus an OR over every identity attribute and free-text value, or null when there is
     * no free text. One OR group whatever the number of values, so it never meets the sibling
     * group limitation described on the class.
     */
    public static AssetQuery identifierTextQuery(FleetQuery fleetQuery, String realm, String[] select) {
        List<String> texts = texts(fleetQuery);
        if (texts.isEmpty()) {
            return null;
        }
        LogicGroup<AttributePredicate> identifierGroup = new LogicGroup<>();
        identifierGroup.operator = LogicGroup.Operator.OR;
        List<AttributePredicate> items = new ArrayList<>();
        for (String text : texts) {
            for (String name : IDENTIFIER_ATTRIBUTES) {
                items.add(new AttributePredicate(
                        new StringPredicate(AssetQuery.Match.EXACT, false, name),
                        new StringPredicate(AssetQuery.Match.CONTAINS, false, text)));
            }
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
            // ORed filters are left to filterByAnyOf; only the ANDed ones reach SQL.
            // By identity: two equal filters are still two tokens.
            Set<FleetQuery.AttributeFilter> anyOf = Collections.newSetFromMap(new IdentityHashMap<>());
            anyOfFilters(fleetQuery).values().forEach(anyOf::addAll);
            for (FleetQuery.AttributeFilter filter : fleetQuery.attributes) {
                if (anyOf.contains(filter)) {
                    continue;
                }
                AttributePredicate predicate = new AttributePredicate(
                        new StringPredicate(AssetQuery.Match.EXACT, false, filter.name),
                        toValuePredicate(filter));
                // NOT_EQUALS is EQUALS negated, so "not equals" combined with negate is EQUALS again.
                predicate.negated = filter.operator == FleetQuery.Operator.NOT_EQUALS
                        ? !filter.negate
                        : filter.negate;
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

    /**
     * Operator/value mismatches are rejected before translation by
     * {@link FleetResourceImpl#validateOperators}; reaching one of the throwing branches here is a
     * programming error, never a silently different query.
     */
    private static AssetQuery.Operator toAssetQueryOperator(FleetQuery.Operator operator) {
        // An explicit null on the wire overrides the field default; it means EQUALS.
        return switch (operator == null ? FleetQuery.Operator.EQUALS : operator) {
            // NOT_EQUALS is carried by AttributePredicate.negated, see baseQuery.
            case EQUALS, NOT_EQUALS -> AssetQuery.Operator.EQUALS;
            case LESS_THAN -> AssetQuery.Operator.LESS_THAN;
            case LESS_EQUALS -> AssetQuery.Operator.LESS_EQUALS;
            case GREATER_THAN -> AssetQuery.Operator.GREATER_THAN;
            case GREATER_EQUALS -> AssetQuery.Operator.GREATER_EQUALS;
            case BETWEEN -> AssetQuery.Operator.BETWEEN;
            case CONTAINS, STARTS_WITH ->
                    throw new IllegalArgumentException(operator + " does not apply to a numeric value");
        };
    }

    private static AssetQuery.Match toMatch(FleetQuery.Operator operator) {
        return switch (operator == null ? FleetQuery.Operator.EQUALS : operator) {
            // NOT_EQUALS is carried by AttributePredicate.negated, see baseQuery.
            case EQUALS, NOT_EQUALS -> AssetQuery.Match.EXACT;
            case CONTAINS -> AssetQuery.Match.CONTAINS;
            case STARTS_WITH -> AssetQuery.Match.BEGIN;
            case LESS_THAN, LESS_EQUALS, GREATER_THAN, GREATER_EQUALS, BETWEEN ->
                    throw new IllegalArgumentException(operator + " requires a numeric value");
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
