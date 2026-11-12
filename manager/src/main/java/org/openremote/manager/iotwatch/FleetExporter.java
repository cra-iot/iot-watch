package org.openremote.manager.iotwatch;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

import org.openremote.model.asset.Asset;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.iotwatch.FleetColumns;
import org.openremote.model.iotwatch.FleetExportRequest;

/**
 * Plans and streams the fleet CSV export: one CSV for one meter type, a row per meter and period
 * (month or day) with the start, end and consumption of each register group. Validation happens
 * in plan, before anything is read or written; write streams in batches so memory stays bounded
 * by BATCH_SIZE assets, not by the export size.
 *
 * A failure while writing propagates and leaves the CSV truncated, so a broken export never looks
 * complete.
 */
public class FleetExporter {

    public static final String MAX_ROWS = "OR_IOTWATCH_EXPORT_MAX_ROWS";
    public static final String MAX_CONCURRENT = "OR_IOTWATCH_EXPORT_MAX_CONCURRENT";
    public static final String PERMIT_TIMEOUT_MINUTES = "OR_IOTWATCH_EXPORT_PERMIT_TIMEOUT_MINUTES";
    public static final int DEFAULT_PERMIT_TIMEOUT_MINUTES = 60;
    public static final int BATCH_SIZE = 50;

    public static final String CSV_CONTENT_TYPE = "text/csv; charset=UTF-8";

    /** File name stem of a header-only export whose requested type has no register groups. */
    static final String NO_TYPE_STEM = "fleet-export";

    /**
     * A validated export. assets: sorted by name then id, empty when the search matched nothing;
     * groups: the type's register groups the caller may read, in column order. assetType is null
     * for a header-only plan whose requested type has no register groups; requestedType is the
     * request's trimmed type as given (null when blank), unchecked, for the log line only.
     */
    public record ExportPlan(FleetExportPeriod period, String assetType, String requestedType,
                             List<Asset<?>> assets, List<FleetExportRegisters.Group> groups, Instant exportedAt) {

        public int devices() {
            return assets.size();
        }
    }

    private static final Logger LOG = Logger.getLogger(FleetExporter.class.getName());

    private static final Comparator<Asset<?>> BY_NAME_THEN_ID = Comparator
            .comparing((Asset<?> asset) -> asset.getName(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Asset::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final PeriodValueRepository repository;
    private final int maxRows;
    private final int maxConcurrent;
    private final long permitTimeoutNanos;
    private final LongSupplier nanoClock;
    private final Set<Permit> permits = ConcurrentHashMap.newKeySet();

    public FleetExporter(PeriodValueRepository repository, int maxRows, int maxConcurrent) {
        this(repository, maxRows, maxConcurrent, Duration.ofMinutes(DEFAULT_PERMIT_TIMEOUT_MINUTES), System::nanoTime);
    }

    public FleetExporter(PeriodValueRepository repository, int maxRows, int maxConcurrent,
                         Duration permitTimeout, LongSupplier nanoClock) {
        this.repository = repository;
        this.maxRows = maxRows;
        if (maxConcurrent < 1) {
            LOG.warning(MAX_CONCURRENT + "=" + maxConcurrent + " is below 1, using 1");
        }
        this.maxConcurrent = Math.max(1, maxConcurrent);
        if (permitTimeout.compareTo(Duration.ofMinutes(1)) < 0) {
            LOG.warning(PERMIT_TIMEOUT_MINUTES + "=" + permitTimeout.toMinutes() + " is below 1, using 1");
        }
        this.permitTimeoutNanos = Math.max(Duration.ofMinutes(1).toNanos(), permitTimeout.toNanos());
        this.nanoClock = nanoClock;
    }

    /**
     * A concurrent-export slot. Closing it twice, or after it expired, is a no-op, so a late
     * close never frees a slot another export holds.
     */
    public final class Permit implements AutoCloseable {

        private final long acquiredAt;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(long acquiredAt) {
            this.acquiredAt = acquiredAt;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                permits.remove(this);
            }
        }
    }

    /** Loads the caller-authorized assets among ids, with only the given attributes. */
    @FunctionalInterface
    public interface AssetLoader {
        List<Asset<?>> load(String assetType, Set<String> ids, List<String> attributes);
    }

    /**
     * Validates, then loads and orders. matchedIds: the ids of every device the search matched,
     * per asset type. Only a search without matches yields the header-only plan; otherwise the
     * request is validated before anything is loaded: no type, a type the search did not match or
     * a type without register groups throw IllegalArgumentException (→400), a type whose groups a
     * restricted caller cannot read throws SecurityException (→403). The type is then loaded once
     * through loader with its matched ids, the identity and the group attributes. The fleet app
     * translates these messages by their wording (fleet-export.ts, serverProblem).
     */
    public ExportPlan plan(FleetExportRequest request, Map<String, Set<String>> matchedIds, AssetLoader loader,
                           boolean restricted, Instant now) {
        FleetExportPeriod period = FleetExportPeriod.parse(request.report, request.from, request.to, now);
        String type = request.assetType == null || request.assetType.isBlank() ? null : request.assetType.trim();
        boolean noDevices = matchedIds == null
                || matchedIds.values().stream().allMatch(ids -> ids == null || ids.isEmpty());
        if (noDevices) {
            // Only a known meter type names the file: the request's type is unchecked input here.
            boolean exportable = type != null && FleetExportRegisters.exportable(type);
            return new ExportPlan(period, exportable ? type : null, type, List.of(),
                    exportable ? FleetExportRegisters.groups(type, restricted) : List.of(), now);
        }

        if (type == null) {
            throw new IllegalArgumentException("Select a meter type");
        }
        Set<String> ids = matchedIds.get(type);
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("No matched devices of type " + type);
        }
        if (!FleetExportRegisters.exportable(type)) {
            throw new IllegalArgumentException("Meter type " + type + " has no readings to export");
        }
        List<FleetExportRegisters.Group> groups = FleetExportRegisters.groups(type, restricted);
        if (groups.isEmpty()) {
            throw new SecurityException("No readable readings for " + type);
        }
        if ((long) ids.size() * period.count() > maxRows) {
            throw new IllegalArgumentException("Export too large: narrow the search or the period");
        }

        List<String> attributes = new ArrayList<>(FleetColumns.EXPORT_IDENTITY);
        groups.forEach(group -> attributes.add(group.attribute()));
        List<Asset<?>> assets = loader.load(type, ids, attributes);
        List<Asset<?>> sorted = assets == null ? List.of()
                : assets.stream().filter(Objects::nonNull).sorted(BY_NAME_THEN_ID).toList();
        return new ExportPlan(period, type, type, sorted, groups, now);
    }

    /**
     * A slot for one export, or null while maxConcurrent exports run. A slot held longer than the
     * permit timeout is reclaimed first: the response body that would close it may never run (a
     * writer or filter failing before it), and a lost slot would otherwise block exports until
     * the manager restarts. An export that genuinely runs longer only loses its slot, not its data.
     */
    public synchronized Permit tryAcquire() {
        long now = nanoClock.getAsLong();
        permits.removeIf(permit -> {
            if (now - permit.acquiredAt < permitTimeoutNanos) {
                return false;
            }
            LOG.warning("Fleet export slot held for over " + Duration.ofNanos(permitTimeoutNanos).toMinutes()
                    + " minutes, reclaiming it");
            permit.closed.set(true);
            return true;
        });
        if (permits.size() >= maxConcurrent) {
            return null;
        }
        Permit permit = new Permit(now);
        permits.add(permit);
        return permit;
    }

    public String fileName(ExportPlan plan) {
        return FleetExportCsv.fileName(plan.assetType() == null ? NO_TYPE_STEM : plan.assetType(),
                plan.period(), plan.exportedAt());
    }

    public String contentType(ExportPlan plan) {
        return CSV_CONTENT_TYPE;
    }

    /**
     * Streams the CSV; returns the number of data rows written, for the log line. The header is
     * flushed to out before the first query, so bytes leave before the database is read.
     */
    public long write(ExportPlan plan, OutputStream out) throws IOException {
        out.write(FleetExportCsv.UTF8_BOM);
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write(FleetExportCsv.line(FleetExportCsv.header(plan.period(), plan.groups())));
        writer.flush();
        out.flush();

        List<LocalDate> periods = plan.period().periods();
        List<Asset<?>> assets = plan.assets();
        long rows = 0;
        for (int from = 0; from < assets.size(); from += BATCH_SIZE) {
            List<Asset<?>> batch = assets.subList(from, Math.min(from + BATCH_SIZE, assets.size()));
            List<String> ids = batch.stream().map(Asset::getId).distinct().toList();
            Set<String> batchAttributes = new LinkedHashSet<>();
            batch.forEach(asset -> batchAttributes.addAll(readable(plan.groups(), asset)));
            List<String> queried = List.copyOf(batchAttributes);
            Map<String, Map<LocalDate, Map<String, PeriodRows.Reading>>> lastPerPeriod =
                    repository.lastPerPeriod(ids, queried, plan.period());
            Map<String, Map<String, PeriodRows.Reading>> seed = repository.seed(ids, queried, plan.period());

            for (Asset<?> asset : batch) {
                // Per-asset readability: a group the caller could not load on this asset is written
                // empty, and the repository's values for it are dropped here. The first readable
                // group gives end_reading_at.
                List<String> readable = readable(plan.groups(), asset);
                List<String> identity = FleetColumns.EXPORT_IDENTITY.stream()
                        .map(name -> asset.getAttributes().get(name)
                                .flatMap(Attribute::getValue)
                                .map(FleetExportCsv::render)
                                .orElse(""))
                        .toList();
                List<PeriodRows.PeriodRow> periodRows = PeriodRows.build(periods, readable,
                        onlyReadable(seed.getOrDefault(asset.getId(), Map.of()), readable),
                        onlyReadablePerPeriod(lastPerPeriod.getOrDefault(asset.getId(), Map.of()), readable));
                for (PeriodRows.PeriodRow row : periodRows) {
                    List<String> cells = new ArrayList<>();
                    cells.add(plan.period().label(row.period()));
                    cells.add(asset.getId());
                    cells.add(asset.getName());
                    cells.addAll(identity);
                    cells.add(FleetExportCsv.instant(row.endReadingAt()));
                    for (FleetExportRegisters.Group group : plan.groups()) {
                        PeriodRows.Values values = row.values().get(group.attribute());
                        cells.add(values == null ? null : values.start());
                        cells.add(values == null ? null : values.end());
                        cells.add(values == null ? null : values.consumption());
                    }
                    writer.write(FleetExportCsv.line(cells));
                    rows++;
                }
            }
            writer.flush();
        }
        writer.flush();
        return rows;
    }

    /** The group attributes present on the loaded asset, in group order. */
    private static List<String> readable(List<FleetExportRegisters.Group> groups, Asset<?> asset) {
        return groups.stream()
                .map(FleetExportRegisters.Group::attribute)
                .filter(attribute -> asset.getAttributes().has(attribute))
                .toList();
    }

    private static Map<String, PeriodRows.Reading> onlyReadable(Map<String, PeriodRows.Reading> readings,
                                                                List<String> readable) {
        Map<String, PeriodRows.Reading> result = new HashMap<>();
        readings.forEach((name, reading) -> {
            if (readable.contains(name)) {
                result.put(name, reading);
            }
        });
        return result;
    }

    private static Map<LocalDate, Map<String, PeriodRows.Reading>> onlyReadablePerPeriod(
            Map<LocalDate, Map<String, PeriodRows.Reading>> perPeriod, List<String> readable) {
        Map<LocalDate, Map<String, PeriodRows.Reading>> result = new HashMap<>();
        perPeriod.forEach((period, readings) -> result.put(period, onlyReadable(readings, readable)));
        return result;
    }
}
