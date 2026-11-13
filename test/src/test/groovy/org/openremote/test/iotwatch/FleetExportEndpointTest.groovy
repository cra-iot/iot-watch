package org.openremote.test.iotwatch

import jakarta.ws.rs.client.Entity
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.openremote.container.security.IdentityProvider
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.datapoint.AssetDatapointService
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.model.Constants
import org.openremote.model.asset.UserAssetLink
import org.openremote.model.attribute.Attribute
import org.openremote.manager.iotwatch.FleetExportPeriod
import org.openremote.model.iotwatch.FleetExportReport
import org.openremote.model.iotwatch.FleetExportRequest
import org.openremote.model.iotwatch.FleetQuery
import org.openremote.model.security.Realm
import org.openremote.model.util.MapAccess
import org.openremote.model.value.ValueType
import org.openremote.model.watermeter.WaterMeterAsset
import org.openremote.test.ManagerContainerTrait
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate

// Boots the OpenRemote container (ManagerContainerTrait), which needs a running PostgreSQL and Keycloak.
// Runs only when OR_CONTAINER_TESTS=true (i.e. the dev stack is up); otherwise the spec is skipped.
// Fixtures use their own realm and users; all identifiers are fixture values, never real device or delivery-point codes.
// Rerunnable against a persistent dev database: leftovers of an aborted run are purged before the
// fixtures are created, and cleanupSpec removes the fixture realm with its assets.
@IgnoreIf({ env['OR_CONTAINER_TESTS'] != 'true' })
class FleetExportEndpointTest extends Specification implements ManagerContainerTrait {

    static final String REALM = "fleet-export-test"
    /** Holds the outsider and the meter whose register a restricted user cannot read. */
    static final String OTHER_REALM = "fleet-export-other"
    static final String PASSWORD = "dummy-test-password-not-a-secret"
    static final String BOM = new String([0xEF, 0xBB, 0xBF] as byte[], StandardCharsets.UTF_8)

    @Shared int port
    @Shared String adminToken
    @Shared String restrictedToken
    @Shared String linkedAssetId
    @Shared String otherAssetId
    @Shared String outsiderToken
    @Shared String hiddenToken
    @Shared String hiddenAssetId
    /** The last finished day on the container clock, in Europe/Prague. */
    @Shared LocalDate yesterday
    @Shared LocalDate today

    def setupSpec() {
        port = findEphemeralPort()
        def container = startContainer(defaultConfig(port), defaultServices())
        def identityProvider = container.getService(ManagerIdentityService).getIdentityProvider()
        def assetStorageService = container.getService(AssetStorageService)
        def datapointService = container.getService(AssetDatapointService)

        [REALM, OTHER_REALM].each { name ->
            FleetEndpointTest.removeFixtureRealm(identityProvider, assetStorageService, name)
            identityProvider.createRealm(new Realm().setName(name).setDisplayName(name).setEnabled(true))
        }

        today = LocalDate.ofInstant(Instant.ofEpochMilli(getClockTimeOf(container)), FleetExportPeriod.ZONE)
        yesterday = today.minusDays(1)

        linkedAssetId = createMeter(assetStorageService, "Export meter A", "00112233AABBEE01")
        otherAssetId = createMeter(assetStorageService, "Export meter B", "00112233AABBEE02")
        datapointService.upsertValue(linkedAssetId, "currentReading", 12.5d,
                yesterday.minusDays(1).atTime(12, 0).atZone(FleetExportPeriod.ZONE).toInstant().toEpochMilli())
        datapointService.upsertValue(otherAssetId, "currentReading", 7.25d,
                yesterday.atTime(8, 0).atZone(FleetExportPeriod.ZONE).toInstant().toEpochMilli())

        def restricted = FleetEndpointTest.fixtureUser(identityProvider, REALM, "export-restricted")
        identityProvider.updateUserRealmRoles(REALM, restricted.getId(), Constants.RESTRICTED_USER_REALM_ROLE)
        identityProvider.updateUserClientRoles(REALM, restricted.getId(), Constants.KEYCLOAK_CLIENT_ID, "read:assets")
        assetStorageService.storeUserAssetLinks([new UserAssetLink(REALM, restricted.getId(), linkedAssetId)])

        // currentReading without ACCESS_RESTRICTED_READ on the instance, as on an attribute added in the
        // Manager, but with a data point: the raw SQL reads it, the export must not write it.
        def hidden = new WaterMeterAsset("Export meter C")
        hidden.setRealm(OTHER_REALM)
        hidden.getAttributes().getOrCreate(WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR).setValue("00112233AABBEE03")
        hidden.getAttributes().addOrReplace(new Attribute<>(
                WaterMeterAsset.CURRENT_READING_ATTRIBUTE_DESCRIPTOR.getName(), ValueType.NUMBER))
        hiddenAssetId = assetStorageService.merge(hidden).getId()
        datapointService.upsertValue(hiddenAssetId, "currentReading", 3.5d,
                yesterday.atTime(8, 0).atZone(FleetExportPeriod.ZONE).toInstant().toEpochMilli())

        def outsider = FleetEndpointTest.fixtureUser(identityProvider, OTHER_REALM, "export-outsider")
        identityProvider.updateUserClientRoles(OTHER_REALM, outsider.getId(), Constants.KEYCLOAK_CLIENT_ID, "read:assets")
        def hiddenUser = FleetEndpointTest.fixtureUser(identityProvider, OTHER_REALM, "export-hidden")
        identityProvider.updateUserRealmRoles(OTHER_REALM, hiddenUser.getId(), Constants.RESTRICTED_USER_REALM_ROLE)
        identityProvider.updateUserClientRoles(OTHER_REALM, hiddenUser.getId(), Constants.KEYCLOAK_CLIENT_ID, "read:assets")
        assetStorageService.storeUserAssetLinks([new UserAssetLink(OTHER_REALM, hiddenUser.getId(), hiddenAssetId)])

        restrictedToken = authenticate(container, REALM, Constants.KEYCLOAK_CLIENT_ID, "export-restricted", PASSWORD)
        outsiderToken = authenticate(container, OTHER_REALM, Constants.KEYCLOAK_CLIENT_ID, "export-outsider", PASSWORD)
        hiddenToken = authenticate(container, OTHER_REALM, Constants.KEYCLOAK_CLIENT_ID, "export-hidden", PASSWORD)
        def adminPassword = MapAccess.getString(container.getConfig(), IdentityProvider.OR_ADMIN_PASSWORD,
                IdentityProvider.OR_ADMIN_PASSWORD_DEFAULT)
        adminToken = authenticate(container, Constants.MASTER_REALM, Constants.KEYCLOAK_CLIENT_ID,
                Constants.MASTER_REALM_ADMIN_USER, adminPassword)
    }

    def cleanupSpec() {
        if (container == null) {
            return
        }
        [REALM, OTHER_REALM].each { name ->
            FleetEndpointTest.removeFixtureRealm(
                    container.getService(ManagerIdentityService).getIdentityProvider(),
                    container.getService(AssetStorageService), name)
        }
    }

    static String createMeter(AssetStorageService assetStorageService, String name, String devEui) {
        def meter = new WaterMeterAsset(name)
        meter.setRealm(REALM)
        meter.getAttributes().getOrCreate(WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR).setValue(devEui)
        meter.setNotes("TEST-NOTE")
        return assetStorageService.merge(meter).getId()
    }

    FleetExportRequest exportRequest(String assetType, FleetExportReport report, LocalDate from, LocalDate to,
                                     String realm = REALM) {
        def query = new FleetQuery()
        query.realm = realm
        query.types = ["WaterMeterAsset"]
        def request = new FleetExportRequest()
        request.query = query
        request.assetType = assetType
        request.report = report
        request.from = from.toString()
        request.to = to.toString()
        return request
    }

    FleetExportRequest dailyRequest(LocalDate from, LocalDate to) {
        return exportRequest("WaterMeterAsset", FleetExportReport.DAILY, from, to)
    }

    /** POSTs the export as the given caller on the given realm path; the caller closes the response. */
    Response post(String accessToken, String realmPath, FleetExportRequest request) {
        return getClientApiTarget(serverUri(port), realmPath, accessToken)
                .path("fleet").path("export")
                .request("text/csv")
                .post(Entity.json(request))
    }

    /** The row's last four cells: end_reading_at and the volume group's start, end and consumption. */
    static List<String> readingCells(String line) {
        def cells = line.split(",", -1).toList()
        return cells.subList(cells.size() - 4, cells.size())
    }

    static List<String> csvLines(Response response) {
        def text = new String(response.readEntity(byte[]), StandardCharsets.UTF_8)
        assert text.startsWith(BOM)
        return text.substring(BOM.length()).split("\r\n").toList()
    }

    def "an admin gets one CSV row per meter and day"() {
        when:
        def response = post(adminToken, Constants.MASTER_REALM, dailyRequest(yesterday.minusDays(1), yesterday))

        then:
        response.status == 200
        response.mediaType == MediaType.valueOf("text/csv; charset=UTF-8")
        response.getHeaderString("Content-Disposition") =~ /^attachment; filename="WaterMeterAsset_daily_.*\.csv"$/

        when:
        def lines = csvLines(response)

        then:
        lines[0] == "date,assetId,name,meter_id,devEui,external_id,delivery_point_id,end_reading_at," +
                "volume_start_m3,volume_end_m3,volume_consumption_m3"
        lines.size() == 5
        lines[1].startsWith(yesterday.minusDays(1).toString() + "," + linkedAssetId + ",Export meter A,")
        // Identifiers are Excel text: ="…" quoted as a CSV cell
        lines.any { it.contains(',"=""00112233AABBEE01""",') }
        !lines.any { it.contains("TEST-NOTE") }

        cleanup:
        response?.close()
    }

    def "a monthly export is one CSV named for the type"() {
        given:
        def lastMonth = FleetExportPeriod.lastMonth(Instant.ofEpochMilli(getClockTimeOf(container))).atDay(1)

        when:
        def response = post(adminToken, Constants.MASTER_REALM,
                exportRequest("WaterMeterAsset", FleetExportReport.MONTHLY, lastMonth, lastMonth))
        def lines = csvLines(response)

        then:
        response.status == 200
        response.mediaType == MediaType.valueOf("text/csv; charset=UTF-8")
        response.getHeaderString("Content-Disposition").contains("WaterMeterAsset_monthly_")
        lines[0].startsWith("month,assetId,name,meter_id,devEui,external_id,delivery_point_id,end_reading_at,")
        lines.size() == 3

        cleanup:
        response?.close()
    }

    def "a restricted user exports only the linked meter"() {
        when:
        def response = post(restrictedToken, REALM, dailyRequest(yesterday, yesterday))
        def lines = csvLines(response)

        then:
        response.status == 200
        lines.size() == 2
        lines[1].contains(linkedAssetId)
        !lines.any { it.contains(otherAssetId) || it.contains("TEST-NOTE") }

        cleanup:
        response?.close()
    }

    def "an export ending today is rejected"() {
        when:
        def response = post(adminToken, Constants.MASTER_REALM, dailyRequest(yesterday, today))

        then:
        response.status == 400
        response.readEntity(String) == "The last day must be yesterday or earlier"

        cleanup:
        response?.close()
    }

    def "a search with matches but no meter type is rejected"() {
        when:
        def response = post(adminToken, Constants.MASTER_REALM,
                exportRequest(null, FleetExportReport.DAILY, yesterday, yesterday))

        then:
        response.status == 400
        response.readEntity(String) == "Select a meter type"

        cleanup:
        response?.close()
    }

    def "an anonymous export is rejected"() {
        when: "no bearer token: the JWT filter lets it through and @RolesAllowed refuses it"
        def response = getClientApiTarget(serverUri(port), REALM)
                .path("fleet").path("export")
                .request("text/csv")
                .post(Entity.json(dailyRequest(yesterday, yesterday)))

        then:
        response.status in [401, 403]

        cleanup:
        response?.close()
    }

    def "a user of another realm cannot export this realm's meters"() {
        when: "a foreign-realm token on this realm's path"
        def foreignPath = post(outsiderToken, REALM, dailyRequest(yesterday, yesterday))

        then: "the JWT filter rejects it"
        foreignPath.status == 401

        when: "the own realm's path with this realm in the query"
        def widenedQuery = post(outsiderToken, OTHER_REALM, dailyRequest(yesterday, yesterday))

        then: "authorizeAssetQuery rejects a realm other than the caller's"
        widenedQuery.status == 403
        widenedQuery.readEntity(String) == "User not authorized to execute specified query"

        cleanup:
        foreignPath?.close()
        widenedQuery?.close()
    }

    def "a restricted user gets empty readings for a register they cannot read"() {
        given:
        def request = exportRequest("WaterMeterAsset", FleetExportReport.DAILY, yesterday, yesterday, OTHER_REALM)

        when: "an unrestricted user of the realm exports the meter"
        def full = post(outsiderToken, OTHER_REALM, request)
        def fullLines = csvLines(full)

        then: "the data point is there"
        full.status == 200
        fullLines.size() == 2
        fullLines[1].contains(hiddenAssetId)
        readingCells(fullLines[1]).contains("3.5")

        when: "the linked restricted user exports it"
        def restricted = post(hiddenToken, OTHER_REALM, request)
        def restrictedLines = csvLines(restricted)

        then: "the row is written, its readings are not"
        restricted.status == 200
        restrictedLines.size() == 2
        restrictedLines[1].contains(hiddenAssetId)
        readingCells(restrictedLines[1]) == ["", "", "", ""]

        cleanup:
        full?.close()
        restricted?.close()
    }
}
