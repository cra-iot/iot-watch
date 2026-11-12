package org.openremote.manager.iotwatch;

import org.hibernate.Session;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.model.iotwatch.FleetExportReport;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the end-of-period (day or month) values of the fleet CSV export straight from asset_datapoint (a TimescaleDB
 * hypertable whose timestamp column is "timestamp without time zone" holding JVM-default-zone local time,
 * as OpenRemote writes it). Data points with
 * a JSON null value are ignored, so a Reading never has a null value.
 */
public class PeriodValueRepository {

    public static final int DEFAULT_LOOKBACK_WEEKS = 260;

    // The period is the first date of the day or month of the data point in the export zone (stored wall
    // time -> timestamptz -> zone wall time -> date_trunc -> date), so a period with a DST change is still
    // one period and a reading at local midnight belongs to the new one. public.last is
    // schema-qualified, as in OpenRemote's own AssetDatapointService.
    private static final String PERIOD_SQL =
            "SELECT ad.entity_id, ad.attribute_name, "
                    + "date_trunc(?, ((ad.timestamp AT TIME ZONE ?) AT TIME ZONE ?))::date AS period, "
                    + "public.last(ad.value #>> '{}', ad.timestamp) AS value, "
                    + "max(ad.timestamp) AS reading_at "
                    + "FROM asset_datapoint ad "
                    + "WHERE ad.entity_id = ANY(?) AND ad.attribute_name = ANY(?) "
                    + "AND jsonb_typeof(ad.value) <> 'null' "
                    + "AND ad.timestamp >= ? AND ad.timestamp < ? "
                    + "GROUP BY 1, 2, 3";

    private static final String SEED_SQL =
            "SELECT e.entity_id, a.attribute_name, s.value, s.timestamp "
                    + "FROM unnest(?::varchar[]) AS e(entity_id) "
                    + "CROSS JOIN unnest(?::varchar[]) AS a(attribute_name) "
                    + "CROSS JOIN LATERAL ("
                    + "SELECT d.value #>> '{}' AS value, d.timestamp FROM asset_datapoint d "
                    + "WHERE d.entity_id = e.entity_id AND d.attribute_name = a.attribute_name "
                    + "AND jsonb_typeof(d.value) <> 'null' "
                    + "AND d.timestamp < ? AND d.timestamp >= ? "
                    + "ORDER BY d.timestamp DESC LIMIT 1) s";

    private final PersistenceService persistenceService;
    private final int lookbackWeeks;

    public PeriodValueRepository(PersistenceService persistenceService, int lookbackWeeks) {
        this.persistenceService = persistenceService;
        this.lookbackWeeks = lookbackWeeks;
    }

    /** assetId → period start (in FleetExportPeriod.ZONE) → attribute → last reading of that period. */
    public Map<String, Map<LocalDate, Map<String, PeriodRows.Reading>>> lastPerPeriod(
            List<String> assetIds, List<String> attributes, FleetExportPeriod period) {
        Map<String, Map<LocalDate, Map<String, PeriodRows.Reading>>> result = new HashMap<>();
        if (assetIds.isEmpty() || attributes.isEmpty()) {
            return result;
        }
        String truncUnit = period.report() == FleetExportReport.MONTHLY ? "month" : "day";
        String zoneId = sqlZone(FleetExportPeriod.ZONE);
        String storedZone = sqlZone(ZoneId.systemDefault());
        return persistenceService.doReturningTransaction(em ->
                em.unwrap(Session.class).doReturningWork(connection -> {
                    try (PreparedStatement st = connection.prepareStatement(PERIOD_SQL)) {
                        st.setString(1, truncUnit);
                        st.setString(2, storedZone);
                        st.setString(3, zoneId);
                        st.setArray(4, varcharArray(connection, assetIds));
                        st.setArray(5, varcharArray(connection, attributes));
                        st.setObject(6, stored(period.start()));
                        st.setObject(7, stored(period.end()));
                        try (ResultSet rs = st.executeQuery()) {
                            while (rs.next()) {
                                LocalDate periodStart = rs.getObject("period", LocalDate.class);
                                result.computeIfAbsent(rs.getString("entity_id"), k -> new HashMap<>())
                                        .computeIfAbsent(periodStart, k -> new HashMap<>())
                                        .put(rs.getString("attribute_name"),
                                                new PeriodRows.Reading(rs.getString("value"), instant(rs, "reading_at")));
                            }
                        }
                    }
                    return result;
                }));
    }

    /** assetId → attribute → last reading before period.start, not older than period.start - lookbackWeeks. */
    public Map<String, Map<String, PeriodRows.Reading>> seed(
            List<String> assetIds, List<String> attributes, FleetExportPeriod period) {
        Map<String, Map<String, PeriodRows.Reading>> result = new HashMap<>();
        if (assetIds.isEmpty() || attributes.isEmpty()) {
            return result;
        }
        int weeks = lookbackWeeks <= 0 ? DEFAULT_LOOKBACK_WEEKS : lookbackWeeks;
        LocalDateTime upper = stored(period.start());
        LocalDateTime lower = upper.minusWeeks(weeks);
        return persistenceService.doReturningTransaction(em ->
                em.unwrap(Session.class).doReturningWork(connection -> {
                    try (PreparedStatement st = connection.prepareStatement(SEED_SQL)) {
                        st.setArray(1, varcharArray(connection, assetIds));
                        st.setArray(2, varcharArray(connection, attributes));
                        st.setObject(3, upper);
                        st.setObject(4, lower);
                        try (ResultSet rs = st.executeQuery()) {
                            while (rs.next()) {
                                result.computeIfAbsent(rs.getString("entity_id"), k -> new HashMap<>())
                                        .put(rs.getString("attribute_name"),
                                                new PeriodRows.Reading(rs.getString("value"), instant(rs, "timestamp")));
                            }
                        }
                    }
                    return result;
                }));
    }

    private static Array varcharArray(Connection connection, List<String> values) throws SQLException {
        return connection.createArrayOf("varchar", values.toArray());
    }

    /** A region-safe zone name for PostgreSQL: "Z" and fixed offsets would be read with reversed sign. */
    private static String sqlZone(ZoneId zone) {
        return zone instanceof ZoneOffset || zone.getId().matches("(UTC|GMT|UT)[+-].*") ? "UTC" : zone.getId();
    }

    /** As OpenRemote writes the column: local time in the JVM default zone. */
    private static LocalDateTime stored(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    /** The column holds JVM-default-zone local time, as OpenRemote writes it. */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).atZone(ZoneId.systemDefault()).toInstant();
    }
}
