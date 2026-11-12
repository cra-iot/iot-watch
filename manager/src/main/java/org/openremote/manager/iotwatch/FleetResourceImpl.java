package org.openremote.manager.iotwatch;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

import io.undertow.servlet.handlers.ServletRequestContext;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebResource;
import org.openremote.model.asset.Asset;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.iotwatch.FleetColumns;
import org.openremote.model.iotwatch.FleetExportOptions;
import org.openremote.model.iotwatch.FleetExportRequest;
import org.openremote.model.iotwatch.FleetQuery;
import org.openremote.model.iotwatch.FleetQueryResult;
import org.openremote.model.iotwatch.FleetResource;
import org.openremote.model.iotwatch.FleetRow;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.filter.RealmPredicate;
import org.xnio.IoUtils;

public class FleetResourceImpl extends ManagerWebResource implements FleetResource {

    private static final Logger LOG = Logger.getLogger(FleetResourceImpl.class.getName());

    protected final AssetStorageService assetStorageService;
    protected final FleetColumnResolver columnResolver;
    protected final FleetExporter exporter;

    public FleetResourceImpl(TimerService timerService, ManagerIdentityService identityService,
                             AssetStorageService assetStorageService,
                             FleetColumnResolver columnResolver,
                             FleetExporter exporter) {
        super(timerService, identityService);
        this.assetStorageService = assetStorageService;
        this.columnResolver = columnResolver;
        this.exporter = exporter;
    }

    /** The devices a search resolves to, before the status filter, sorting and paging. */
    protected record Resolved(List<Asset<?>> assets, List<FleetRow> rows,
                              List<FleetQueryResult.ColumnDescriptor> columns) {
    }

    @Override
    public FleetQueryResult query(FleetQuery query) {
        FleetQuery fleetQuery = query == null ? new FleetQuery() : query;
        String realm = realmOf(fleetQuery);
        validate(fleetQuery);

        Resolved resolved = resolve(fleetQuery, realm);
        List<FleetRow> rows = resolved.rows();
        Map<String, String> parentNames = parentNames(realm, rows);
        rows.forEach(row -> row.parentName = parentNames.get(row.parentId));

        FleetQueryResult result;
        try {
            result = FleetResultAssembler.assemble(rows, fleetQuery, timerService.getCurrentTimeMillis());
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
        result.facets.parents.forEach(facet -> facet.label = parentNames.get(facet.key));
        result.columns = resolved.columns();
        return result;
    }

    @Override
    public FleetExportOptions exportOptions(FleetQuery query) {
        FleetQuery fleetQuery = query == null ? new FleetQuery() : query;
        String realm = realmOf(fleetQuery);
        validate(fleetQuery);

        Map<String, Integer> deviceCounts = new HashMap<>();
        matchedRows(fleetQuery, realm).forEach(row -> deviceCounts.merge(row.type, 1, Integer::sum));
        Instant now = now();
        return FleetExportOptionsAssembler.assemble(deviceCounts, isRestrictedUser(), columnResolver,
                FleetExportRange.maxTo(ZoneOffset.UTC, now));
    }

    @Override
    public Response export(FleetExportRequest request) {
        long started = System.currentTimeMillis();
        if (request == null) {
            throw badRequest("Export request required");
        }
        FleetQuery fleetQuery = request.query == null ? new FleetQuery() : request.query;
        String realm = realmOf(fleetQuery);
        validate(fleetQuery);

        Map<String, Set<String>> idsByType = new HashMap<>();
        matchedRows(fleetQuery, realm).forEach(row ->
                idsByType.computeIfAbsent(row.type, type -> new LinkedHashSet<>()).add(row.id));

        // Each requested type is reloaded through the caller's authorization with only the selected
        // attributes, so a restricted caller's asset lacks every attribute it cannot read.
        Map<String, List<Asset<?>>> assetsByType = new HashMap<>();
        if (request.attributes != null) {
            request.attributes.forEach((type, names) -> {
                Set<String> ids = idsByType.get(type);
                if (ids == null || names == null || names.isEmpty()) {
                    return;
                }
                String[] select = names.stream().filter(Objects::nonNull).distinct().toArray(String[]::new);
                AssetQuery assetQuery = new AssetQuery()
                        .realm(new RealmPredicate(realm))
                        .ids(ids.toArray(new String[0]))
                        .select(new AssetQuery.Select().attributes(select));
                assetsByType.put(type, authorizedFindAll(assetQuery, realm));
            });
        }

        FleetExporter.ExportPlan plan;
        try {
            plan = exporter.plan(request, assetsByType, isRestrictedUser(),
                    now());
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        } catch (SecurityException e) {
            throw forbidden(e.getMessage());
        }

        if (!exporter.tryAcquire()) {
            throw new WebApplicationException(Response.status(429)
                    .entity("Another export is running, try again in a moment").build());
        }
        try {
            StreamingOutput body = exportBody(plan, getUsername(), realm, started);
            return Response.ok(body)
                    .header(HttpHeaders.CONTENT_TYPE, exporter.contentType(plan))
                    .header("Content-Disposition", "attachment; filename=\"" + exporter.fileName(plan) + "\"")
                    .build();
        } catch (RuntimeException e) {
            exporter.release();
            throw e;
        }
    }

    /**
     * The response body: streams the export, then releases the permit and logs once. On failure the
     * connection is aborted before the exception propagates: the headers are already sent, and the
     * container's exception mapper would otherwise end the chunked response normally, leaving the
     * client a truncated file that looks complete.
     */
    protected StreamingOutput exportBody(FleetExporter.ExportPlan plan, String username, String realm, long started) {
        return out -> {
            long rows = -1;
            boolean ok = false;
            try {
                rows = exporter.write(plan, out);
                ok = true;
            } catch (IOException | RuntimeException | Error e) {
                abortConnection();
                throw e;
            } finally {
                exporter.release();
                logExport(username, realm, plan, rows, started, ok);
            }
        };
    }

    /** Closes the current request's connection without ending the response, so the client sees a reset. */
    protected void abortConnection() {
        ServletRequestContext context = ServletRequestContext.current();
        if (context != null) {
            IoUtils.safeClose(context.getExchange().getConnection());
        }
    }

    /** One line per export; never values. */
    protected static void logExport(String username, String realm, FleetExporter.ExportPlan plan,
                                    long rows, long started, boolean ok) {
        LOG.info("Fleet export user=" + username
                + " realm=" + realm
                + " types=" + String.join(",", plan.types().stream().map(FleetExporter.TypeExport::assetType).toList())
                + " devices=" + plan.devices()
                + " from=" + plan.range().from()
                + " to=" + plan.range().to()
                + " zone=" + plan.range().zone().getId()
                + " rows=" + rows
                + " durationMs=" + (System.currentTimeMillis() - started)
                + " outcome=" + (ok ? "ok" : "failed"));
    }

    /** The manager clock's current instant. */
    protected Instant now() {
        return Instant.ofEpochMilli(timerService.getCurrentTimeMillis());
    }

    /**
     * A 400 whose body is the message as text/plain. Thrown without an entity, OpenRemote's
     * exception mapper would replace the message with a generic text (a stack trace in dev mode).
     */
    public static BadRequestException badRequest(String message) {
        return new BadRequestException(message, textResponse(Response.Status.BAD_REQUEST, message));
    }

    /** A 403 whose body is the message as text/plain; see badRequest. */
    public static ForbiddenException forbidden(String message) {
        return new ForbiddenException(message, textResponse(Response.Status.FORBIDDEN, message));
    }

    private static Response textResponse(Response.Status status, String message) {
        return Response.status(status).type(MediaType.TEXT_PLAIN_TYPE).entity(message == null ? "" : message).build();
    }

    /** The query's realm, else the request's. */
    protected String realmOf(FleetQuery fleetQuery) {
        return fleetQuery.realm == null || fleetQuery.realm.isBlank()
                ? getRequestRealmName() : fleetQuery.realm;
    }

    /** Rejects a malformed or hidden-attribute query before anything is read. */
    protected static void validate(FleetQuery fleetQuery) {
        validateFilters(fleetQuery);
        validateOperators(fleetQuery);
        rejectHiddenFilters(fleetQuery);
    }

    /**
     * Every device the search matches, with no page applied: the overview's result before
     * paging, including the status filter. The query must have passed validate.
     */
    protected List<FleetRow> matchedRows(FleetQuery fleetQuery, String realm) {
        List<FleetRow> rows = resolve(fleetQuery, realm).rows();
        try {
            return FleetResultAssembler.match(rows, fleetQuery, timerService.getCurrentTimeMillis());
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
    }

    /**
     * The authorized assets a validated search matches, mapped to rows with the columns shown
     * for them. Every read goes through authorizedFindAll; restricted callers lose the matches
     * they could only have made on a value they cannot read.
     */
    protected Resolved resolve(FleetQuery fleetQuery, String realm) {
        // The select covers the query's types (or every device type for an empty filter) and has to
        // reach every branch of the union; the displayed columns are narrowed to the types
        // actually found, below.
        String[] select = FleetQueryTranslator.withAnyOfAttributes(
                columnResolver.selectAttributes(fleetQuery, fleetQuery.types), fleetQuery);

        List<Asset<?>> assets = new ArrayList<>();
        AssetQuery nameQuery = FleetQueryTranslator.nameTextQuery(fleetQuery, realm, select);
        if (nameQuery != null) {
            List<Asset<?>> byName = authorizedFindAll(nameQuery, realm);
            if (FleetQueryTranslator.matchesNameInJava(fleetQuery)) {
                // The name half of the union is matched here, over the authorized result only.
                byName = FleetQueryTranslator.filterByName(byName, FleetQueryTranslator.texts(fleetQuery));
            }
            assets.addAll(byName);
            assets.addAll(authorizedFindAll(
                    FleetQueryTranslator.identifierTextQuery(fleetQuery, realm, select), realm));
        } else {
            assets.addAll(authorizedFindAll(FleetQueryTranslator.filterQuery(fleetQuery, realm, select), realm));
        }
        // Repeated filters on one attribute are ORed here, over the authorized result only.
        assets = FleetQueryTranslator.filterByAnyOf(assets, fleetQuery);
        if (isRestrictedUser()) {
            assets = dropHiddenMatches(realm, assets, fleetQuery);
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
        return new Resolved(assets, rows, columns);
    }

    protected List<Asset<?>> authorizedFindAll(AssetQuery assetQuery, String realm) {
        // The single most important line here: core's own authorization gate, never bypassed.
        if (!assetStorageService.authorizeAssetQuery(assetQuery, getAuthContext(), realm)) {
            throw forbidden("User not authorized to execute specified query");
        }
        return assetStorageService.findAll(assetQuery);
    }

    /**
     * Malformed attribute filters are the caller's error: a null element or a filter without a
     * name would otherwise surface as a NullPointerException, i.e. a 500. An unknown status
     * would silently match nothing.
     */
    public static void validateFilters(FleetQuery fleetQuery) {
        if (fleetQuery.status != null) {
            for (String status : fleetQuery.status) {
                if (status == null || !FleetResultAssembler.STATUSES.contains(status)) {
                    throw badRequest("Unknown status: " + status);
                }
            }
        }
        if (fleetQuery.attributes == null) {
            return;
        }
        for (FleetQuery.AttributeFilter filter : fleetQuery.attributes) {
            if (filter == null) {
                throw badRequest("Attribute filter must not be null");
            }
            if (filter.name == null || filter.name.isBlank()) {
                throw badRequest("Attribute filter requires a name");
            }
        }
    }

    /**
     * An operator that does not fit its value would otherwise be translated into a different
     * query - CONTAINS on a number into EQUALS, a comparison on a string into an exact match -
     * and answer the wrong question without an error.
     */
    public static void validateOperators(FleetQuery fleetQuery) {
        if (fleetQuery.attributes == null) {
            return;
        }
        for (FleetQuery.AttributeFilter filter : fleetQuery.attributes) {
            if (filter == null || filter.operator == null) {
                continue;
            }
            switch (filter.operator) {
                case CONTAINS, STARTS_WITH -> {
                    if (filter.value instanceof Number || filter.value instanceof Boolean) {
                        throw badRequest(
                                filter.operator + " requires a text value on " + filter.name);
                    }
                }
                // A null value is no number either: it would become an exact match on "".
                case LESS_THAN, LESS_EQUALS, GREATER_THAN, GREATER_EQUALS, BETWEEN -> {
                    if (!(filter.value instanceof Number)) {
                        throw badRequest(
                                filter.operator + " requires a numeric value on " + filter.name);
                    }
                    if (filter.operator == FleetQuery.Operator.BETWEEN
                            && !(filter.rangeValue instanceof Number)) {
                        throw badRequest("BETWEEN requires a numeric rangeValue on " + filter.name);
                    }
                }
                default -> {
                    // EQUALS and NOT_EQUALS apply to any value type.
                }
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
            throw badRequest("Filtering on " + FleetColumns.RAW_VALUE + " is not supported");
        }
    }

    /**
     * Restricted callers only: drops the assets that matched on a value the caller cannot read.
     * Core limits the select of a restricted AssetQuery to ACCESS_RESTRICTED_READ attributes, but
     * not its WHERE clause, so an attribute filter or an identifier match would otherwise answer
     * "does this hidden value match" for the caller's own assets. Checked against the stored
     * assets through the caller's authorization, not against descriptors: the meta is per
     * attribute instance, and an older asset or one edited in the Manager may lack it.
     */
    protected List<Asset<?>> dropHiddenMatches(String realm, List<Asset<?>> assets, FleetQuery fleetQuery) {
        Set<String> filtered = filteredAttributeNames(fleetQuery);
        List<String> texts = FleetQueryTranslator.texts(fleetQuery);
        if (assets.isEmpty() || (filtered.isEmpty() && texts.isEmpty())) {
            return assets;
        }
        Set<String> select = new LinkedHashSet<>(filtered);
        if (!texts.isEmpty()) {
            select.addAll(Arrays.asList(FleetQueryTranslator.IDENTIFIER_ATTRIBUTES));
        }
        AssetQuery visibleQuery = new AssetQuery()
                .realm(new RealmPredicate(realm))
                .ids(assets.stream().map(Asset::getId).distinct().toArray(String[]::new))
                .select(new AssetQuery.Select().attributes(select.toArray(new String[0])));
        Map<String, Asset<?>> visible = new HashMap<>();
        authorizedFindAll(visibleQuery, realm).forEach(asset -> visible.put(asset.getId(), asset));
        return assets.stream()
                .filter(asset -> matchesVisibly(visible.get(asset.getId()), filtered, texts))
                .toList();
    }

    /** The attribute filters' names as sent, trimmed; validateFilters has rejected null ones. */
    public static Set<String> filteredAttributeNames(FleetQuery fleetQuery) {
        Set<String> names = new LinkedHashSet<>();
        if (fleetQuery.attributes != null) {
            fleetQuery.attributes.forEach(filter -> names.add(filter.name.trim()));
        }
        return names;
    }

    /**
     * True when {@code visible}, the asset as the caller may read it, still explains the match:
     * every filtered attribute is readable, and free text is found in the name or in a readable
     * identifier. Names compare ignoring case like the SQL filter does, while the select loads
     * exact names only, so a differently cased filter fails closed.
     */
    public static boolean matchesVisibly(Asset<?> visible, Set<String> filtered, List<String> texts) {
        if (visible == null) {
            return false;
        }
        Set<String> readable = new HashSet<>();
        visible.getAttributes().values().forEach(attribute -> readable.add(attribute.getName().toLowerCase(Locale.ROOT)));
        if (!filtered.stream().allMatch(name -> readable.contains(name.toLowerCase(Locale.ROOT)))) {
            return false;
        }
        if (texts.isEmpty()) {
            return true;
        }
        List<String> haystack = new ArrayList<>();
        if (visible.getName() != null) {
            haystack.add(visible.getName());
        }
        for (String name : FleetQueryTranslator.IDENTIFIER_ATTRIBUTES) {
            visible.getAttributes().get(name)
                    .flatMap(Attribute::getValue)
                    .ifPresent(value -> haystack.add(value.toString()));
        }
        return haystack.stream().map(value -> value.toUpperCase(Locale.ROOT)).anyMatch(value ->
                texts.stream().anyMatch(text -> value.contains(text.toUpperCase(Locale.ROOT))));
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
                    .map(FleetResourceImpl::payloadTimestamp)
                    .ifPresent(timestamp -> timestamps.put(asset.getId(), timestamp));
        }
        return timestamps;
    }

    /**
     * The attribute's timestamp, but only when it holds a payload. The empty rawValue created at
     * provisioning carries a creation timestamp that is not a report, so it yields null.
     */
    public static Long payloadTimestamp(Attribute<?> attribute) {
        return attribute.getValue().isPresent() ? attribute.getTimestamp().orElse(null) : null;
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
