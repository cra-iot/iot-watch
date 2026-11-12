package org.openremote.test.iotwatch

import org.openremote.container.persistence.PersistenceService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.datapoint.AssetDatapointService
import org.openremote.manager.iotwatch.DailyValueRepository
import org.openremote.manager.iotwatch.FleetExportRange
import org.openremote.manager.security.ManagerIdentityService
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
// Fixtures use their own realm; all identifiers are fixture values, never real device or delivery-point codes.
// Rerunnable against a persistent dev database: leftovers of an aborted run are purged before the
// fixtures are created, and cleanupSpec removes the fixture realm with its assets.
@IgnoreIf({ env['OR_CONTAINER_TESTS'] != 'true' })
class DailyValueRepositoryTest extends Specification implements ManagerContainerTrait {

    static final String REALM = "export-test"
    static final String ATTRIBUTE = "currentReading"
    /** Fixed "now" far after the fixture data, so every fixture range is in the past. */
    static final Instant NOW = Instant.parse("2026-12-01T12:00:00Z")

    @Shared String assetId
    @Shared String otherAssetId
    @Shared DailyValueRepository repository
    @Shared DailyValueRepository shortLookback

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
        point(datapointService, assetId, ATTRIBUTE, 9.5d, "2026-09-30T23:59:00Z")
        point(datapointService, otherAssetId, ATTRIBUTE, 8.0d, "2026-09-15T12:00:00Z")

        repository = new DailyValueRepository(persistenceService, 0)
        shortLookback = new DailyValueRepository(persistenceService, 1)
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

    static FleetExportRange range(String from, String to, String zone = null) {
        return FleetExportRange.parse(from, to, zone, NOW)
    }

    def "returns the last reading of each UTC day"() {
        when:
        def daily = repository.daily([assetId], [ATTRIBUTE], range("2026-10-01", "2026-10-31"))
        def days = daily[assetId]
        def reading = days[LocalDate.parse("2026-10-01")][ATTRIBUTE]

        then:
        new BigDecimal(reading.value()) == 11
        reading.at() == Instant.parse("2026-10-01T20:00:00Z")
        new BigDecimal(days[LocalDate.parse("2026-10-03")][ATTRIBUTE].value()) == 12
        !days.containsKey(LocalDate.parse("2026-10-02")) || days[LocalDate.parse("2026-10-02")][ATTRIBUTE].at() == Instant.parse("2026-10-02T00:00:00Z")
    }

    def "a reading exactly at 00:00 belongs to the next day"() {
        when:
        def days = repository.daily([assetId], [ATTRIBUTE], range("2026-10-01", "2026-10-31"))[assetId]
        def reading = days[LocalDate.parse("2026-10-02")][ATTRIBUTE]

        then:
        new BigDecimal(reading.value()) == 13
        reading.at() == Instant.parse("2026-10-02T00:00:00Z")
    }

    def "a zone moves readings across midnight"() {
        when:
        def days = repository.daily([assetId], [ATTRIBUTE], range("2026-10-01", "2026-10-31", "Europe/Prague"))[assetId]
        def reading = days[LocalDate.parse("2026-10-05")][ATTRIBUTE]

        then:
        new BigDecimal(reading.value()) == 14
        reading.at() == Instant.parse("2026-10-04T22:30:00Z")
        !days.containsKey(LocalDate.parse("2026-10-04")) || days[LocalDate.parse("2026-10-04")][ATTRIBUTE].at() != Instant.parse("2026-10-04T22:30:00Z")
    }

    def "seed is the last reading before the range"() {
        when:
        def seed = repository.seed([assetId, otherAssetId], [ATTRIBUTE], range("2026-10-01", "2026-10-31"))
        def shortSeed = shortLookback.seed([otherAssetId], [ATTRIBUTE], range("2026-10-01", "2026-10-31"))

        then:
        new BigDecimal(seed[assetId][ATTRIBUTE].value()) == new BigDecimal("9.5")
        seed[assetId][ATTRIBUTE].at() == Instant.parse("2026-09-30T23:59:00Z")
        new BigDecimal(seed[otherAssetId][ATTRIBUTE].value()) == 8

        and: "a point older than the lookback is not used"
        shortSeed[otherAssetId] == null || shortSeed[otherAssetId][ATTRIBUTE] == null
    }

    def "ignores other attributes and out-of-range points"() {
        when:
        def daily = repository.daily([assetId], [ATTRIBUTE], range("2026-10-01", "2026-10-31"))
        def attributes = daily[assetId].values().collectMany { it.keySet() } as Set
        def lastDay = daily[assetId].keySet().max()

        then:
        attributes == [ATTRIBUTE] as Set
        lastDay <= LocalDate.parse("2026-10-31")
        daily[assetId].values().every { it[ATTRIBUTE].at() < Instant.parse("2026-11-01T00:00:00Z") }
    }
}
