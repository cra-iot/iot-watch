package org.openremote.test.iotwatch

import org.openremote.container.persistence.PersistenceService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.datapoint.AssetDatapointService
import org.openremote.manager.iotwatch.PeriodValueRepository
import org.openremote.manager.iotwatch.FleetExportPeriod
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.model.iotwatch.FleetExportReport
import org.openremote.model.security.Realm
import org.openremote.model.watermeter.WaterMeterAsset
import org.openremote.test.ManagerContainerTrait
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

import java.time.Instant
import java.time.LocalDate

// Boots the OpenRemote container (ManagerContainerTrait), which needs a running PostgreSQL and Keycloak.
// Runs only when OR_CONTAINER_TESTS=true (i.e. the dev stack is up); otherwise the spec is skipped.
// Periods are Europe/Prague calendar days or months.
// Fixtures use their own realm; all identifiers are fixture values, never real device or delivery-point codes.
// Rerunnable against a persistent dev database: leftovers of an aborted run are purged before the
// fixtures are created, and cleanupSpec removes the fixture realm with its assets.
@IgnoreIf({ env['OR_CONTAINER_TESTS'] != 'true' })
class PeriodValueRepositoryTest extends Specification implements ManagerContainerTrait {

    static final String REALM = "export-test"
    static final String ATTRIBUTE = "currentReading"
    /** Fixed "now" far after the fixture data, so every fixture range is in the past. */
    static final Instant NOW = Instant.parse("2026-12-01T12:00:00Z")

    @Shared String assetId
    @Shared String otherAssetId
    @Shared PeriodValueRepository repository
    @Shared PeriodValueRepository shortLookback

    def setupSpec() {
        def container = startContainer(defaultConfig(findEphemeralPort()), defaultServices())
        def identityProvider = container.getService(ManagerIdentityService).getIdentityProvider()
        def assetStorageService = container.getService(AssetStorageService)
        def datapointService = container.getService(AssetDatapointService)
        def persistenceService = container.getService(PersistenceService)

        FleetEndpointTest.removeFixtureRealm(identityProvider, assetStorageService, REALM)
        identityProvider.createRealm(new Realm().setName(REALM).setDisplayName(REALM).setEnabled(true))

        assetId = createMeter(assetStorageService, "TEST-EXPORT-0001")
        otherAssetId = createMeter(assetStorageService, "TEST-EXPORT-0002")

        point(datapointService, assetId, ATTRIBUTE, 10.0d, "2026-10-01T08:00:00Z")
        point(datapointService, assetId, ATTRIBUTE, 11.0d, "2026-10-01T20:00:00Z")
        point(datapointService, assetId, ATTRIBUTE, 12.0d, "2026-10-03T09:00:00Z")
        point(datapointService, assetId, ATTRIBUTE, 13.0d, "2026-10-02T00:00:00Z")
        point(datapointService, assetId, ATTRIBUTE, 14.0d, "2026-10-04T22:30:00Z")
        point(datapointService, assetId, "flow_rate", 99.0d, "2026-10-01T09:00:00Z")
        point(datapointService, assetId, ATTRIBUTE, 15.0d, "2026-11-01T00:00:00Z")
        point(datapointService, assetId, ATTRIBUTE, 9.0d, "2026-09-15T12:00:00Z")
        point(datapointService, assetId, ATTRIBUTE, 9.5d, "2026-09-30T21:59:00Z")
        point(datapointService, assetId, ATTRIBUTE, 9.7d, "2026-09-30T22:00:00Z")
        point(datapointService, otherAssetId, ATTRIBUTE, 8.0d, "2026-09-15T12:00:00Z")
        point(datapointService, assetId, ATTRIBUTE, 16.0d, "2026-10-30T09:00:00Z")

        repository = new PeriodValueRepository(persistenceService, 0)
        shortLookback = new PeriodValueRepository(persistenceService, 1)
    }

    def cleanupSpec() {
        if (container == null) {
            return
        }
        FleetEndpointTest.removeFixtureRealm(
                container.getService(ManagerIdentityService).getIdentityProvider(),
                container.getService(AssetStorageService), REALM)
    }

    static String createMeter(AssetStorageService assetStorageService, String name) {
        def meter = new WaterMeterAsset(name)
        meter.setRealm(REALM)
        return assetStorageService.merge(meter).getId()
    }

    static void point(AssetDatapointService service, String id, String attribute, Object value, String at) {
        service.upsertValue(id, attribute, value, Instant.parse(at).toEpochMilli())
    }

    static FleetExportPeriod period(FleetExportReport report, String from, String to) {
        return FleetExportPeriod.parse(report, from, to, NOW)
    }

    def "returns the last reading of each Prague day"() {
        when:
        def daily = repository.lastPerPeriod([assetId], [ATTRIBUTE],
                period(FleetExportReport.DAILY, "2026-10-01", "2026-10-31"))
        def days = daily[assetId]
        def reading = days[LocalDate.parse("2026-10-01")][ATTRIBUTE]

        then:
        new BigDecimal(reading.value()) == 11
        reading.at() == Instant.parse("2026-10-01T20:00:00Z")
        new BigDecimal(days[LocalDate.parse("2026-10-03")][ATTRIBUTE].value()) == 12
    }

    def "a reading at local midnight belongs to the new day"() {
        when:
        def days = repository.lastPerPeriod([assetId], [ATTRIBUTE],
                period(FleetExportReport.DAILY, "2026-10-01", "2026-10-31"))[assetId]
        def reading = days[LocalDate.parse("2026-10-02")][ATTRIBUTE]

        then: "2026-10-02T00:00Z is 02:00 in Prague, the same day"
        new BigDecimal(reading.value()) == 13
        reading.at() == Instant.parse("2026-10-02T00:00:00Z")
    }

    def "a reading late in the evening UTC falls on the next Prague day"() {
        when:
        def days = repository.lastPerPeriod([assetId], [ATTRIBUTE],
                period(FleetExportReport.DAILY, "2026-10-01", "2026-10-31"))[assetId]
        def reading = days[LocalDate.parse("2026-10-05")][ATTRIBUTE]

        then:
        new BigDecimal(reading.value()) == 14
        reading.at() == Instant.parse("2026-10-04T22:30:00Z")
        !days.containsKey(LocalDate.parse("2026-10-04"))
    }

    def "a reading at local midnight on the first belongs to the new month"() {
        when: "2026-09-30T22:00Z is 2026-10-01T00:00 in Prague"
        def months = repository.lastPerPeriod([assetId], [ATTRIBUTE],
                period(FleetExportReport.MONTHLY, "2026-09-01", "2026-10-01"))[assetId]
        def september = months[LocalDate.parse("2026-09-01")][ATTRIBUTE]

        then: "the 21:59Z reading is the last of September, the 22:00Z one is not"
        new BigDecimal(september.value()) == new BigDecimal("9.5")
        september.at() == Instant.parse("2026-09-30T21:59:00Z")
        months[LocalDate.parse("2026-10-01")][ATTRIBUTE].at() > Instant.parse("2026-09-30T22:00:00Z")
    }

    def "monthly bucket keeps the last reading of the month"() {
        when:
        def months = repository.lastPerPeriod([assetId], [ATTRIBUTE],
                period(FleetExportReport.MONTHLY, "2026-10-01", "2026-10-01"))[assetId]
        def reading = months[LocalDate.parse("2026-10-01")][ATTRIBUTE]

        then:
        months.keySet() == [LocalDate.parse("2026-10-01")] as Set
        new BigDecimal(reading.value()) == 16
        reading.at() == Instant.parse("2026-10-30T09:00:00Z")
    }

    def "seed is the last reading before the period"() {
        when:
        def range = period(FleetExportReport.DAILY, "2026-10-01", "2026-10-31")
        def seed = repository.seed([assetId, otherAssetId], [ATTRIBUTE], range)
        def shortSeed = shortLookback.seed([otherAssetId], [ATTRIBUTE], range)

        then:
        new BigDecimal(seed[assetId][ATTRIBUTE].value()) == new BigDecimal("9.5")
        seed[assetId][ATTRIBUTE].at() == Instant.parse("2026-09-30T21:59:00Z")
        new BigDecimal(seed[otherAssetId][ATTRIBUTE].value()) == 8

        and: "a point older than the lookback is not used"
        shortSeed[otherAssetId] == null || shortSeed[otherAssetId][ATTRIBUTE] == null
    }

    def "seed ignores JSON null values"() {
        given:
        def nullAsset = createMeter(container.getService(AssetStorageService), "TEST-EXPORT-0003")
        def service = container.getService(AssetDatapointService)
        point(service, nullAsset, ATTRIBUTE, 7.0d, "2026-09-10T12:00:00Z")
        point(service, nullAsset, ATTRIBUTE, null, "2026-09-20T12:00:00Z")

        when:
        def seed = repository.seed([nullAsset], [ATTRIBUTE], period(FleetExportReport.MONTHLY, "2026-10-01", "2026-10-01"))

        then:
        new BigDecimal(seed[nullAsset][ATTRIBUTE].value()) == 7
    }

    def "ignores other attributes and out-of-range points"() {
        when:
        def range = period(FleetExportReport.DAILY, "2026-10-01", "2026-10-31")
        def daily = repository.lastPerPeriod([assetId], [ATTRIBUTE], range)
        def attributes = daily[assetId].values().collectMany { it.keySet() } as Set

        then:
        attributes == [ATTRIBUTE] as Set
        daily[assetId].keySet().max() <= LocalDate.parse("2026-10-31")
        daily[assetId].values().every { it[ATTRIBUTE].at() < range.end() }
    }
}
