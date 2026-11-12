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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.openremote.model.asset.Asset;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.iotwatch.FleetExportRequest;
import org.openremote.model.value.AttributeDescriptor;

/**
 * Plans and streams the fleet CSV export: one CSV for a single asset type, a ZIP of CSVs for
 * several. Validation happens in plan, before anything is read or written; write streams in
 * batches so memory stays bounded by BATCH_SIZE assets, not by the export size.
 *
 * A failure while writing propagates and leaves the output unfinished (no ZIP central directory,
 * a truncated CSV), so a broken export never looks complete.
 */
public class FleetExporter {

    public static final String MAX_ROWS = "OR_IOTWATCH_EXPORT_MAX_ROWS";
    public static final String MAX_CONCURRENT = "OR_IOTWATCH_EXPORT_MAX_CONCURRENT";
    public static final String PERMIT_TIMEOUT_MINUTES = "OR_IOTWATCH_EXPORT_PERMIT_TIMEOUT_MINUTES";
    public static final int DEFAULT_PERMIT_TIMEOUT_MINUTES = 60;
    public static final int BATCH_SIZE = 50;

    public static final String CSV_CONTENT_TYPE = "text/csv; charset=UTF-8";
    public static final String ZIP_CONTENT_TYPE = "application/zip";

    /**
     * One asset type's file: its assets sorted by name then id; identity columns in
     * FleetExportAttributes.IDENTITY order, measurements in the declaration order of the asset
     * class's attribute descriptors (FleetExportAttributes.measurements).
     */
    public record TypeExport(String assetType, List<Asset<?>> assets, List<String> identity, List<String> measurements) {
    }

    /** A validated export; types sorted by asset type, empty when no device matched. */
    public record ExportPlan(FleetExportRange range, List<TypeExport> types, Instant exportedAt) {

        public int devices() {
            return types.stream().mapToInt(type -> type.assets().size()).sum();
        }
    }

    private static final Logger LOG = Logger.getLogger(FleetExporter.class.getName());

    private static final Comparator<Asset<?>> BY_NAME_THEN_ID = Comparator
            .comparing((Asset<?> asset) -> asset.getName(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Asset::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final DailyValueRepository repository;
    private final int maxRows;
    private final int maxConcurrent;
    private final long permitTimeoutNanos;
    private final LongSupplier nanoClock;
    private final Set<Permit> permits = ConcurrentHashMap.newKeySet();

    public FleetExporter(DailyValueRepository repository, int maxRows, int maxConcurrent) {
        this(repository, maxRows, maxConcurrent, Duration.ofMinutes(DEFAULT_PERMIT_TIMEOUT_MINUTES), System::nanoTime);
    }

    public FleetExporter(DailyValueRepository repository, int maxRows, int maxConcurrent,
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
     * whole selection is validated before anything is loaded: no selection, a type the search
     * did not match or an attribute that cannot be exported throw IllegalArgumentException
     * (→400), an attribute a restricted caller cannot read throws SecurityException (→403).
     * Each selected type is then loaded once through loader with its matched ids. The fleet app
     * translates these messages by their wording (fleet-export.ts, serverProblem).
     */
    public ExportPlan plan(FleetExportRequest request, Map<String, Set<String>> matchedIds, AssetLoader loader,
                           boolean restricted, Instant now) {
        FleetExportRange range = FleetExportRange.parse(request.from, request.to, request.timeZone, now);
        boolean noDevices = matchedIds == null
                || matchedIds.values().stream().allMatch(ids -> ids == null || ids.isEmpty());
        if (noDevices) {
            return new ExportPlan(range, List.of(), now);
        }

        // Types with an empty selection are dropped; sorted so the ZIP entry order is stable.
        Map<String, List<String>> selection = new TreeMap<>();
        if (request.attributes != null) {
            request.attributes.forEach((type, names) -> {
                if (type != null && names != null && !names.isEmpty()) {
                    selection.put(type, names);
                }
            });
        }
        if (selection.isEmpty()) {
            throw new IllegalArgumentException("Select at least one attribute to export");
        }

        List<TypeSelection> validated = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : selection.entrySet()) {
            String type = entry.getKey();
            Set<String> ids = matchedIds.get(type);
            if (ids == null || ids.isEmpty()) {
                throw new IllegalArgumentException("No matched devices of type " + type);
            }
            Set<String> requested = new HashSet<>(entry.getValue());
            List<String> identity = names(FleetExportAttributes.identity(type, false));
            List<String> measurements = names(FleetExportAttributes.measurements(type, false));
            Set<String> readable = new HashSet<>(names(FleetExportAttributes.identity(type, true)));
            readable.addAll(names(FleetExportAttributes.measurements(type, true)));
            for (String name : requested) {
                if (name == null || (!identity.contains(name) && !measurements.contains(name))) {
                    throw new IllegalArgumentException("Attribute " + name + " cannot be exported for " + type);
                }
                if (restricted && !readable.contains(name)) {
                    throw new SecurityException("Attribute " + name + " is not readable for " + type);
                }
            }
            if ((long) ids.size() * range.days() > maxRows) {
                throw new IllegalArgumentException("Export too large: narrow the search or the date range");
            }
            validated.add(new TypeSelection(type, ids, entry.getValue().stream().distinct().toList(),
                    identity.stream().filter(requested::contains).toList(),
                    measurements.stream().filter(requested::contains).toList()));
        }

        List<TypeExport> types = new ArrayList<>();
        for (TypeSelection type : validated) {
            List<Asset<?>> assets = loader.load(type.assetType(), type.ids(), type.attributes());
            List<Asset<?>> sorted = assets == null ? List.of()
                    : assets.stream().filter(Objects::nonNull).sorted(BY_NAME_THEN_ID).toList();
            types.add(new TypeExport(type.assetType(), sorted, type.identity(), type.measurements()));
        }
        return new ExportPlan(range, List.copyOf(types), now);
    }

    /** A validated type of the selection, before its assets are loaded. */
    private record TypeSelection(String assetType, Set<String> ids, List<String> attributes,
                                 List<String> identity, List<String> measurements) {
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

    /** csvName for one type, zipName for several, the ZIP stem + ".csv" for none. */
    public String fileName(ExportPlan plan) {
        if (plan.types().size() == 1) {
            return FleetExportCsv.csvName(plan.types().get(0).assetType(), plan.range(), plan.exportedAt());
        }
        String zipName = FleetExportCsv.zipName(plan.range(), plan.exportedAt());
        if (plan.types().isEmpty()) {
            return zipName.substring(0, zipName.length() - ".zip".length()) + ".csv";
        }
        return zipName;
    }

    public String contentType(ExportPlan plan) {
        return plan.types().size() > 1 ? ZIP_CONTENT_TYPE : CSV_CONTENT_TYPE;
    }

    /** Streams the file; returns the number of data rows written, for the log line. */
    public long write(ExportPlan plan, OutputStream out) throws IOException {
        if (plan.types().isEmpty()) {
            out.write(FleetExportCsv.UTF8_BOM);
            Writer writer = writer(out);
            writer.write(FleetExportCsv.line(FleetExportCsv.FIXED_COLUMNS));
            writer.flush();
            return 0;
        }
        if (plan.types().size() == 1) {
            out.write(FleetExportCsv.UTF8_BOM);
            return writeType(plan.range(), plan.types().get(0), writer(out), out);
        }
        // Not closed on purpose: finish() runs only after every entry succeeded, and the caller owns out.
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        long rows = 0;
        for (TypeExport type : plan.types()) {
            zip.putNextEntry(new ZipEntry(FleetExportCsv.csvName(type.assetType(), plan.range(), plan.exportedAt())));
            zip.write(FleetExportCsv.UTF8_BOM);
            rows += writeType(plan.range(), type, writer(zip), out);
            zip.closeEntry();
        }
        zip.finish();
        out.flush();
        return rows;
    }

    /** sink: the response stream, flushed after the header so bytes leave before the first query. */
    private long writeType(FleetExportRange range, TypeExport type, Writer writer, OutputStream sink)
            throws IOException {
        List<String> header = new ArrayList<>(FleetExportCsv.FIXED_COLUMNS);
        header.addAll(type.identity());
        header.addAll(type.measurements());
        writer.write(FleetExportCsv.line(header));
        writer.flush();
        sink.flush();

        List<LocalDate> dates = range.dates();
        long rows = 0;
        List<Asset<?>> assets = type.assets();
        for (int from = 0; from < assets.size(); from += BATCH_SIZE) {
            List<Asset<?>> batch = assets.subList(from, Math.min(from + BATCH_SIZE, assets.size()));
            List<String> ids = batch.stream().map(Asset::getId).distinct().toList();
            Map<String, Map<LocalDate, Map<String, DailyRows.Reading>>> daily =
                    repository.daily(ids, type.measurements(), range);
            Map<String, Map<String, DailyRows.Reading>> seed = repository.seed(ids, type.measurements(), range);

            for (Asset<?> asset : batch) {
                // Per-asset readability: a measurement the caller could not load on this asset is
                // written empty, and the repository's values for it are dropped here.
                Set<String> readable = new LinkedHashSet<>();
                type.measurements().stream()
                        .filter(name -> asset.getAttributes().has(name))
                        .forEach(readable::add);
                List<String> measurements = List.copyOf(readable);

                List<String> identityValues = type.identity().stream()
                        .map(name -> asset.getAttributes().get(name)
                                .flatMap(Attribute::getValue)
                                .map(FleetExportCsv::render)
                                .orElse(""))
                        .toList();

                List<DailyRows.DailyRow> dailyRows = DailyRows.build(dates, measurements,
                        onlyReadable(seed.getOrDefault(asset.getId(), Map.of()), readable),
                        onlyReadableDaily(daily.getOrDefault(asset.getId(), Map.of()), readable));
                for (DailyRows.DailyRow row : dailyRows) {
                    List<String> cells = new ArrayList<>(header.size());
                    cells.add(row.date().toString());
                    cells.add(asset.getId());
                    cells.add(asset.getName());
                    cells.add(FleetExportCsv.instant(row.lastReadingAt()));
                    cells.addAll(identityValues);
                    for (String measurement : type.measurements()) {
                        cells.add(row.values().get(measurement));
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

    private static Map<String, DailyRows.Reading> onlyReadable(Map<String, DailyRows.Reading> readings,
                                                               Set<String> readable) {
        Map<String, DailyRows.Reading> result = new HashMap<>();
        readings.forEach((name, reading) -> {
            if (readable.contains(name)) {
                result.put(name, reading);
            }
        });
        return result;
    }

    private static Map<LocalDate, Map<String, DailyRows.Reading>> onlyReadableDaily(
            Map<LocalDate, Map<String, DailyRows.Reading>> daily, Set<String> readable) {
        Map<LocalDate, Map<String, DailyRows.Reading>> result = new HashMap<>();
        daily.forEach((date, readings) -> result.put(date, onlyReadable(readings, readable)));
        return result;
    }

    private static Writer writer(OutputStream out) {
        return new OutputStreamWriter(out, StandardCharsets.UTF_8);
    }

    private static List<String> names(List<AttributeDescriptor<?>> descriptors) {
        return descriptors.stream().map(AttributeDescriptor::getName).toList();
    }
}
