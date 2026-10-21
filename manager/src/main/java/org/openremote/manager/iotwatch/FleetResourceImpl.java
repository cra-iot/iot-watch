package org.openremote.manager.iotwatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;

import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebResource;
import org.openremote.model.asset.Asset;
import org.openremote.model.iotwatch.FleetColumns;
import org.openremote.model.iotwatch.FleetQuery;
import org.openremote.model.iotwatch.FleetQueryResult;
import org.openremote.model.iotwatch.FleetResource;
import org.openremote.model.iotwatch.FleetRow;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.filter.RealmPredicate;

public class FleetResourceImpl extends ManagerWebResource implements FleetResource {

    protected final AssetStorageService assetStorageService;
    protected final FleetColumnResolver columnResolver;

    public FleetResourceImpl(TimerService timerService, ManagerIdentityService identityService,
                             AssetStorageService assetStorageService,
                             FleetColumnResolver columnResolver) {
        super(timerService, identityService);
        this.assetStorageService = assetStorageService;
        this.columnResolver = columnResolver;
    }

    @Override
    public FleetQueryResult query(FleetQuery query) {
        FleetQuery fleetQuery = query == null ? new FleetQuery() : query;
        String realm = getRequestRealmName();
        validateFilters(fleetQuery);
        rejectHiddenFilters(fleetQuery);

        // The select covers the query's types (or every device type for an empty filter) and has to
        // reach every branch of the union; the displayed columns are narrowed to the types
        // actually found, below.
        String[] select = columnResolver.selectAttributes(fleetQuery, fleetQuery.types);

        List<AssetQuery> assetQueries = new ArrayList<>();
        AssetQuery nameQuery = FleetQueryTranslator.nameTextQuery(fleetQuery, realm, select);
        if (nameQuery != null) {
            assetQueries.add(nameQuery);
            assetQueries.add(FleetQueryTranslator.identifierTextQuery(fleetQuery, realm, select));
        } else {
            assetQueries.add(FleetQueryTranslator.filterQuery(fleetQuery, realm, select));
        }

        List<Asset<?>> assets = new ArrayList<>();
        for (AssetQuery assetQuery : assetQueries) {
            // The single most important line here: core's own authorization gate, never bypassed.
            if (!assetStorageService.authorizeAssetQuery(assetQuery, getAuthContext(), realm)) {
                throw new ForbiddenException("User not authorized to execute specified query");
            }
            assets.addAll(assetStorageService.findAll(assetQuery));
        }

        Set<String> resultTypes = new LinkedHashSet<>();
        assets.forEach(asset -> resultTypes.add(asset.getType()));
        List<FleetQueryResult.ColumnDescriptor> columns =
                columnResolver.columnsFor(resultTypes, fleetQuery.columns);
        List<String> selectedColumns = columns.stream()
                .filter(column -> column.selected)
                .map(column -> column.name)
                .distinct()
                .toList();

        Map<String, Long> lastSeen = lastSeen(realm, assets);
        List<FleetRow> rows = new ArrayList<>();
        for (Asset<?> asset : assets) {
            rows.add(FleetRowMapper.map(asset, columnResolver, selectedColumns, lastSeen.get(asset.getId())));
        }
        Map<String, String> parentNames = parentNames(realm, rows);
        rows.forEach(row -> row.parentName = parentNames.get(row.parentId));

        FleetQueryResult result;
        try {
            result = FleetResultAssembler.assemble(rows, fleetQuery, timerService.getCurrentTimeMillis());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        result.facets.parents.forEach(facet -> facet.label = parentNames.get(facet.key));
        result.columns = columns;
        return result;
    }

    /**
     * Malformed attribute filters are the caller's error: a null element or a filter without a
     * name would otherwise surface as a NullPointerException, i.e. a 500.
     */
    public static void validateFilters(FleetQuery fleetQuery) {
        if (fleetQuery.attributes == null) {
            return;
        }
        for (FleetQuery.AttributeFilter filter : fleetQuery.attributes) {
            if (filter == null) {
                throw new BadRequestException("Attribute filter must not be null");
            }
            if (filter.name == null || filter.name.isBlank()) {
                throw new BadRequestException("Attribute filter requires a name");
            }
        }
    }

    /**
     * A filter on rawValue would let a caller probe a payload they cannot read: the WHERE clause
     * of an AssetQuery is not restricted by ACCESS_RESTRICTED_READ, only the select is.
     */
    public static void rejectHiddenFilters(FleetQuery fleetQuery) {
        // Case-insensitive and trimmed: the translator matches attribute names case-insensitively.
        if (fleetQuery.attributes != null && fleetQuery.attributes.stream()
                .anyMatch(filter -> filter != null && filter.name != null
                        && FleetColumns.RAW_VALUE.equalsIgnoreCase(filter.name.trim()))) {
            throw new BadRequestException("Filtering on " + FleetColumns.RAW_VALUE + " is not supported");
        }
    }

    /**
     * rawValue's timestamp per asset, read without the caller's access restriction. Safe
     * because the ids come only from assets the authorized queries above returned, and only
     * the timestamp leaves this method - never the payload. No authorizeAssetQuery here on
     * purpose: for a restricted caller it would strip rawValue, which is the whole point.
     */
    protected Map<String, Long> lastSeen(String realm, List<Asset<?>> authorizedAssets) {
        String[] ids = authorizedAssets.stream().map(Asset::getId).distinct().toArray(String[]::new);
        Map<String, Long> timestamps = new HashMap<>();
        if (ids.length == 0) {
            return timestamps;
        }
        AssetQuery lookup = new AssetQuery()
                .realm(new RealmPredicate(realm))
                .ids(ids)
                .select(new AssetQuery.Select().attributes(FleetColumns.RAW_VALUE));
        for (Asset<?> asset : assetStorageService.findAll(lookup)) {
            asset.getAttributes().get(FleetColumns.RAW_VALUE)
                    .flatMap(attribute -> attribute.getTimestamp())
                    .ifPresent(timestamp -> timestamps.put(asset.getId(), timestamp));
        }
        return timestamps;
    }

    /**
     * Parent names through the caller's own authorization: a restricted user linked to a meter
     * but not to its building gets no name, rather than a name they could not otherwise see.
     */
    protected Map<String, String> parentNames(String realm, List<FleetRow> rows) {
        String[] parentIds = rows.stream().map(row -> row.parentId).filter(Objects::nonNull)
                .distinct().toArray(String[]::new);
        Map<String, String> names = new HashMap<>();
        if (parentIds.length == 0) {
            return names;
        }
        AssetQuery parents = new AssetQuery()
                .realm(new RealmPredicate(realm))
                .ids(parentIds)
                .select(new AssetQuery.Select().excludeAttributes());
        if (assetStorageService.authorizeAssetQuery(parents, getAuthContext(), realm)) {
            assetStorageService.findAll(parents).forEach(parent -> names.put(parent.getId(), parent.getName()));
        }
        return names;
    }
}
