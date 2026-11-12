package org.openremote.test.iotwatch

import jakarta.ws.rs.client.Entity
import jakarta.ws.rs.core.Response
import org.openremote.container.security.IdentityProvider
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.datapoint.AssetDatapointService
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.model.Constants
import org.openremote.model.asset.UserAssetLink
import org.openremote.model.iotwatch.FleetExportRequest
import org.openremote.model.iotwatch.FleetQuery
import org.openremote.model.security.Realm
import org.openremote.model.util.MapAccess
import org.openremote.model.watermeter.WaterMeterAsset
import org.openremote.test.ManagerContainerTrait
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// Boots the OpenRemote container (ManagerContainerTrait), which needs a running PostgreSQL and Keycloak.
// Runs only when OR_CONTAINER_TESTS=true (i.e. the dev stack is up); otherwise the spec is skipped.
// Fixtures use their own realm and users; all identifiers are fixture values, never real device or delivery-point codes.
// Rerunnable against a persistent dev database: leftovers of an aborted run are purged before the
// fixtures are created, and cleanupSpec removes the fixture realm with its assets.
@IgnoreIf({ env['OR_CONTAINER_TESTS'] != 'true' })
class FleetExportEndpointTest extends Specification implements ManagerContainerTrait {

    static final String REALM = "fleet-export-test"
    static final String PASSWORD = "dummy-test-password-not-a-secret"
    static final String BOM = new String([0xEF, 0xBB, 0xBF] as byte[], StandardCharsets.UTF_8)

    @Shared int port
    @Shared String adminToken
    @Shared String restrictedToken
    @Shared String linkedAssetId
    @Shared String otherAssetId
    /** The export's last day: yesterday on the container clock, in UTC. */
    @Shared LocalDate yesterday
    @Shared LocalDate today

    def setupSpec() {
        port = findEphemeralPort()
        def container = startContainer(defaultConfig(port), defaultServices())
        def identityProvider = container.getService(ManagerIdentityService).getIdentityProvider()
        def assetStorageService = container.getService(AssetStorageService)
        def datapointService = container.getService(AssetDatapointService)

        FleetEndpointTest.removeFixtureRealm(identityProvider, assetStorageService, REALM)
        identityProvider.createRealm(new Realm().setName(REALM).setDisplayName(REALM).setEnabled(true))

        today = LocalDate.ofInstant(Instant.ofEpochMilli(getClockTimeOf(container)), ZoneOffset.UTC)
        yesterday = today.minusDays(1)

        linkedAssetId = createMeter(assetStorageService, "Export meter A", "00112233AABBEE01")
        otherAssetId = createMeter(assetStorageService, "Export meter B", "00112233AABBEE02")
        datapointService.upsertValue(linkedAssetId, "currentReading", 12.5d,
                yesterday.minusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli())
        datapointService.upsertValue(otherAssetId, "currentReading", 7.25d,
                yesterday.atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli())

        def restricted = FleetEndpointTest.fixtureUser(identityProvider, REALM, "export-restricted")
        identityProvider.updateUserRealmRoles(REALM, restricted.getId(), Constants.RESTRICTED_USER_REALM_ROLE)
        identityProvider.updateUserClientRoles(REALM, restricted.getId(), Constants.KEYCLOAK_CLIENT_ID, "read:assets")
        assetStorageService.storeUserAssetLinks([new UserAssetLink(REALM, restricted.getId(), linkedAssetId)])

        restrictedToken = authenticate(container, REALM, Constants.KEYCLOAK_CLIENT_ID, "export-restricted", PASSWORD)
        def adminPassword = MapAccess.getString(container.getConfig(), IdentityProvider.OR_ADMIN_PASSWORD,
                IdentityProvider.OR_ADMIN_PASSWORD_DEFAULT)
        adminToken = authenticate(container, Constants.MASTER_REALM, Constants.KEYCLOAK_CLIENT_ID,
                Constants.MASTER_REALM_ADMIN_USER, adminPassword)
    }

    def cleanupSpec() {
        if (container == null) {
            return
        }
        FleetEndpointTest.removeFixtureRealm(
                container.getService(ManagerIdentityService).getIdentityProvider(),
                container.getService(AssetStorageService), REALM)
    }

    static String createMeter(AssetStorageService assetStorageService, String name, String devEui) {
        def meter = new WaterMeterAsset(name)
        meter.setRealm(REALM)
        meter.getAttributes().getOrCreate(WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR).setValue(devEui)
        meter.setNotes("TEST-NOTE")
        return assetStorageService.merge(meter).getId()
    }

    FleetExportRequest exportRequest(List<String> attributes, LocalDate from, LocalDate to) {
        def query = new FleetQuery()
        query.realm = REALM
        query.types = ["WaterMeterAsset"]
        def request = new FleetExportRequest()
        request.query = query
        request.from = from.toString()
        request.to = to.toString()
        request.attributes = ["WaterMeterAsset": attributes]
        return request
    }

    /** POSTs the export as the given caller on the given realm path; the caller closes the response. */
    Response post(String accessToken, String realmPath, FleetExportRequest request) {
        return getClientApiTarget(serverUri(port), realmPath, accessToken)
                .path("fleet").path("export")
                .request("text/csv", "application/zip")
                .post(Entity.json(request))
    }

    static List<String> csvLines(Response response) {
        def text = new String(response.readEntity(byte[]), StandardCharsets.UTF_8)
        assert text.startsWith(BOM)
        return text.substring(BOM.length()).split("\r\n").toList()
    }

    def "an admin gets one row per meter and day with carried-forward values"() {
        when:
        def response = post(adminToken, Constants.MASTER_REALM,
                exportRequest(["devEui", "notes", "currentReading"], yesterday.minusDays(1), yesterday))

        then:
        response.status == 200
        response.getHeaderString("Content-Type").startsWith("text/csv")
        response.getHeaderString("Content-Disposition") =~ /^attachment; filename="WaterMeterAsset_.*\.csv"$/

        when:
        def lines = csvLines(response)

        then:
        lines[0] == "date,assetId,name,lastReadingAt,devEui,notes,currentReading"
        lines.size() == 5
        lines[1].startsWith(yesterday.minusDays(1).toString() + "," + linkedAssetId + ",Export meter A,")
        lines[1].endsWith(",00112233AABBEE01,TEST-NOTE,12.5")
        lines[2].startsWith(yesterday.toString() + "," + linkedAssetId)
        lines[2].endsWith(",12.5")
        lines[3].startsWith(yesterday.minusDays(1).toString() + "," + otherAssetId + ",Export meter B,,")
        lines[4].endsWith(",7.25")

        cleanup:
        response?.close()
    }

    def "a restricted user exports only the linked meter and no generic attributes"() {
        when:
        def response = post(restrictedToken, REALM, exportRequest(["devEui", "currentReading"], yesterday, yesterday))
        def lines = csvLines(response)

        then:
        response.status == 200
        lines[0] == "date,assetId,name,lastReadingAt,devEui,currentReading"
        lines.size() == 2
        lines[1].contains(linkedAssetId)
        !lines.any { it.contains(otherAssetId) || it.contains("TEST-NOTE") }

        cleanup:
        response?.close()
    }

    def "a restricted user requesting notes is forbidden"() {
        when:
        def response = post(restrictedToken, REALM, exportRequest(["devEui", "notes"], yesterday, yesterday))

        then:
        response.status == 403
        response.readEntity(String) == "Attribute notes is not readable for WaterMeterAsset"

        cleanup:
        response?.close()
    }

    def "an export ending today is rejected"() {
        when:
        def response = post(adminToken, Constants.MASTER_REALM, exportRequest(["currentReading"], yesterday, today))

        then:
        response.status == 400
        response.readEntity(String) == "The last day must be yesterday or earlier"

        cleanup:
        response?.close()
    }
}
