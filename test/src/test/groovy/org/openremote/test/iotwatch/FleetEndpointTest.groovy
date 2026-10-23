package org.openremote.test.iotwatch

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotAuthorizedException
import jakarta.ws.rs.client.Entity
import jakarta.ws.rs.core.MediaType
import org.openremote.manager.security.ManagerIdentityProvider
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.container.security.IdentityProvider
import org.openremote.model.Constants
import org.openremote.model.asset.UserAssetLink
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.gasmeter.GasMeterAsset
import org.openremote.model.iotwatch.FleetQuery
import org.openremote.model.iotwatch.FleetResource
import org.openremote.model.query.AssetQuery
import org.openremote.model.query.filter.RealmPredicate
import org.openremote.model.security.Realm
import org.openremote.model.security.User
import org.openremote.model.util.MapAccess
import org.openremote.model.tracker.TrackerAsset
import org.openremote.model.value.ValueType
import org.openremote.model.watermeter.WaterMeterAsset
import org.openremote.test.ManagerContainerTrait
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.TimeUnit

// Boots the OpenRemote container (ManagerContainerTrait), which needs a running PostgreSQL and Keycloak.
// Runs only when OR_CONTAINER_TESTS=true (i.e. the dev stack is up); otherwise the spec is skipped.
// Fixtures use their own realms and users; all identifiers are fixture values, never real device or delivery-point codes.
// Rerunnable against a persistent dev database: leftovers of an aborted run are purged before the
// fixtures are created, and cleanupSpec removes both fixture realms with their assets.
@IgnoreIf({ env['OR_CONTAINER_TESTS'] != 'true' })
class FleetEndpointTest extends Specification implements ManagerContainerTrait {

    static final String REALM = "fleet-test"
    static final String OTHER_REALM = "fleet-other"
    static final String PASSWORD = "dummy-test-password-not-a-secret"
    /** Distinctive fixture payload text, so its absence from a response body is meaningful. */
    static final String RAW_PAYLOAD_MARKER = "fleet-fixture-raw-payload"

    @Shared FleetResource readerResource
    @Shared FleetResource restrictedResource
    @Shared FleetResource outsiderResource
    /** The outsider on their own realm's path, so only the query's realm points at the fixture realm. */
    @Shared FleetResource outsiderOwnRealmResource
    /** The master admin on the master path, as the realm picker in the fleet app calls it. */
    @Shared FleetResource superUserResource
    @Shared String linkedAssetId
    @Shared String buildingId
    @Shared String floorId
    @Shared int port
    @Shared String readerToken
    @Shared String restrictedToken

    def setupSpec() {
        port = findEphemeralPort()
        def container = startContainer(defaultConfig(port), defaultServices())
        def identityProvider = container.getService(ManagerIdentityService).getIdentityProvider()
        def assetStorageService = container.getService(AssetStorageService)

        // Realms: start from a clean slate even if an earlier run was aborted before cleanupSpec
        [REALM, OTHER_REALM].each { name ->
            removeFixtureRealm(identityProvider, assetStorageService, name)
            identityProvider.createRealm(new Realm().setName(name).setDisplayName(name).setEnabled(true))
        }

        // Assets (created after the Task 0 descriptor change, so identity attributes carry ACCESS_RESTRICTED_READ)
        def building = new ThingAsset("Building")
        building.setRealm(REALM)
        building = assetStorageService.merge(building)
        buildingId = building.getId()

        // One level deeper: a free-text name match under a site must reach below the site's children
        def floor = new ThingAsset("Floor")
        floor.setRealm(REALM)
        floor.setParentId(buildingId)
        floorId = assetStorageService.merge(floor).getId()

        def waterNested = new WaterMeterAsset("Water meter C")
        waterNested.setRealm(REALM)
        waterNested.setParentId(floorId)
        assetStorageService.merge(waterNested)

        def waterWithRaw = new WaterMeterAsset("Water meter A")
        waterWithRaw.setRealm(REALM)
        waterWithRaw.setParentId(building.getId())
        waterWithRaw.getAttributes().getOrCreate(WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR).setValue("00112233AABBCC01")
        waterWithRaw.getAttributes().getOrCreate(WaterMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR).setValue("TEST-METER-0001")
        waterWithRaw.getAttributes().getOrCreate(WaterMeterAsset.LEAKAGE_ATTRIBUTE_DESCRIPTOR).setValue(true)
        // rawValue with an explicit timestamp taken from the container clock (not older than the merge)
        def rawValue = new ValueType.ObjectMap()
        rawValue.put("cmd", RAW_PAYLOAD_MARKER)
        waterWithRaw.getAttributes().getOrCreate(WaterMeterAsset.RAW_VALUE_ATTRIBUTE_DESCRIPTOR)
            .setValue(rawValue, getClockTimeOf(container))
        waterWithRaw = assetStorageService.merge(waterWithRaw)
        linkedAssetId = waterWithRaw.getId()

        def waterPlain = new WaterMeterAsset("Water meter B")
        waterPlain.setRealm(REALM)
        waterPlain.setParentId(building.getId())
        waterPlain.getAttributes().getOrCreate(WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR).setValue("00112233AABBCC02")
        assetStorageService.merge(waterPlain)

        def gas = new GasMeterAsset("Gas meter")
        gas.setRealm(REALM)
        gas.setParentId(building.getId())
        gas.getAttributes().getOrCreate(GasMeterAsset.DELIVERY_POINT_ID_ATTRIBUTE_DESCRIPTOR).setValue("TEST-DP-0001")
        gas.getAttributes().getOrCreate(GasMeterAsset.GAS_TEMPERATURE_ATTRIBUTE_DESCRIPTOR).setValue(12.5d)
        assetStorageService.merge(gas)

        def tracker = new TrackerAsset("Tracker")
        tracker.setRealm(REALM)
        assetStorageService.merge(tracker)

        // Users
        def reader = fixtureUser(identityProvider, REALM, "fleet-reader")
        identityProvider.updateUserClientRoles(REALM, reader.getId(), Constants.KEYCLOAK_CLIENT_ID, "read:assets")

        def restricted = fixtureUser(identityProvider, REALM, "fleet-restricted")
        identityProvider.updateUserRealmRoles(REALM, restricted.getId(), Constants.RESTRICTED_USER_REALM_ROLE)
        identityProvider.updateUserClientRoles(REALM, restricted.getId(), Constants.KEYCLOAK_CLIENT_ID, "read:assets")
        // Linked to the meter with rawValue only, deliberately not to "Building"
        assetStorageService.storeUserAssetLinks([new UserAssetLink(REALM, restricted.getId(), linkedAssetId)])

        def outsider = fixtureUser(identityProvider, OTHER_REALM, "fleet-outsider")
        identityProvider.updateUserClientRoles(OTHER_REALM, outsider.getId(), Constants.KEYCLOAK_CLIENT_ID, "read:assets")

        // Tokens and proxies; the outsider calls the fleet-test path with a fleet-other token
        def token = { String tokenRealm, String username ->
            authenticate(container, tokenRealm, Constants.KEYCLOAK_CLIENT_ID, username, PASSWORD)
        }
        readerToken = token(REALM, "fleet-reader")
        restrictedToken = token(REALM, "fleet-restricted")
        def proxy = { String accessToken ->
            getClientApiTarget(serverUri(port), REALM, accessToken).proxy(FleetResource)
        }
        readerResource = proxy(readerToken)
        restrictedResource = proxy(restrictedToken)
        def outsiderToken = token(OTHER_REALM, "fleet-outsider")
        outsiderResource = proxy(outsiderToken)
        outsiderOwnRealmResource = getClientApiTarget(serverUri(port), OTHER_REALM, outsiderToken).proxy(FleetResource)
        def adminPassword = MapAccess.getString(container.getConfig(), IdentityProvider.OR_ADMIN_PASSWORD,
                IdentityProvider.OR_ADMIN_PASSWORD_DEFAULT)
        def superUserToken = authenticate(container, Constants.MASTER_REALM, Constants.KEYCLOAK_CLIENT_ID,
                Constants.MASTER_REALM_ADMIN_USER, adminPassword)
        superUserResource = getClientApiTarget(serverUri(port), Constants.MASTER_REALM, superUserToken).proxy(FleetResource)
    }

    def cleanupSpec() {
        if (container == null) {
            return
        }
        def identityProvider = container.getService(ManagerIdentityService).getIdentityProvider()
        def assetStorageService = container.getService(AssetStorageService)
        [REALM, OTHER_REALM].each { name ->
            removeFixtureRealm(identityProvider, assetStorageService, name)
        }
    }

    /** Deletes a fixture realm and every asset in it; the identity provider refuses a non-empty realm. */
    static void removeFixtureRealm(ManagerIdentityProvider identityProvider,
                                   AssetStorageService assetStorageService, String name) {
        if (!identityProvider.realmExists(name)) {
            return
        }
        List<String> ids = assetStorageService.findAll(new AssetQuery()
                .realm(new RealmPredicate(name))
                .select(new AssetQuery.Select().excludeAttributes()))*.id
        if (!ids.isEmpty()) {
            assert assetStorageService.deleteUntilFinished(ids, true).get(60, TimeUnit.SECONDS)
        }
        identityProvider.deleteRealm(name)
    }

    /** Reuses the user when present (resetting its password), so setup never fails on a duplicate username. */
    static User fixtureUser(ManagerIdentityProvider identityProvider, String realm, String username) {
        def user = identityProvider.getUserByUsername(realm, username) ?: new User().setUsername(username)
        return identityProvider.createUpdateUser(realm, user.setEnabled(true), PASSWORD, false)
    }

    /** POSTs the query with a plain JAX-RS client and returns the response body exactly as sent. */
    String rawQueryBody(String accessToken, String queryJson) {
        def response = getClientApiTarget(serverUri(port), REALM, accessToken)
                .path("fleet").path("query")
                .request(MediaType.APPLICATION_JSON)
                .post(Entity.json(queryJson))
        try {
            assert response.status == 200
            return response.readEntity(String)
        } finally {
            response.close()
        }
    }

    def "a query without free text returns the realm's meters with their primary values"() {
        when:
        def result = readerResource.query(new FleetQuery())

        then:
        result.total > 0
        result.rows.every { it.id != null && it.name != null }
        result.facets.types.sum { it.count } == result.total

        and: "the resolved columns travel with the result, so the UI needs no copy of the model"
        result.columns.any { it.primary && it.selected }

        and: "columns come from the types in the result, not from every registered type"
        result.columns*.assetType.toSet().every { it in result.rows*.type.toSet() }

        and: "an empty type filter lists devices only: the building is not a silent device"
        !result.rows.any { it.type == "ThingAsset" }
    }

    def "an alarm set on a fixture asset reaches the row"() {
        given:
        def query = new FleetQuery()
        query.types = ["WaterMeterAsset"]

        when:
        def result = readerResource.query(query)

        then: "the alarm boolean was selected, so the mapper could see it"
        result.rows.any { (it.alarms ?: []).contains("leakage") }
    }

    def "a type outside FleetColumns still gets a column set"() {
        given:
        def query = new FleetQuery()
        query.types = ["TrackerAsset"]

        when:
        def result = readerResource.query(query)

        then: "the resolver fell back to the asset model rather than returning nothing to show"
        result.total == 1
        result.columns.any { it.assetType == "TrackerAsset" }
    }

    def "a caller-requested column is fetched and returned"() {
        given:
        def query = new FleetQuery()
        query.types = ["GasMeterAsset"]
        query.columns = ["gas_temperature"]

        when:
        def result = readerResource.query(query)

        then:
        result.columns.find { it.name == "gas_temperature" }.selected
        result.rows[0].values.containsKey("gas_temperature")
    }

    def "free text matches a delivery point identifier, not only the asset name"() {
        given:
        def query = new FleetQuery()
        query.text = ["TEST-DP-0001"]

        when:
        def result = readerResource.query(query)

        then:
        result.total == 1
        result.rows[0].deliveryPointId == "TEST-DP-0001"
    }

    def "several free-text values are ORed: pasted devEUIs each find their meter"() {
        given:
        def query = new FleetQuery()
        query.text = ["00112233AABBCC01", "00112233AABBCC02"]

        when:
        def result = readerResource.query(query)

        then:
        result.rows*.name as Set == ["Water meter A", "Water meter B"] as Set
        result.unmatchedText.isEmpty()

        when: "one pasted value matches no meter"
        query.text = ["00112233AABBCC01", "00112233AABBCC99"]
        def partial = readerResource.query(query)

        then: "the others are still found, and the missing one is named"
        partial.rows*.name == ["Water meter A"]
        partial.unmatchedText == ["00112233AABBCC99"]
    }

    def "repeated filters on one attribute are ORed, other filters still narrow"() {
        given:
        def query = new FleetQuery()
        query.attributes = [
                devEui(FleetQuery.Operator.CONTAINS, "AABBCC01"),
                devEui(FleetQuery.Operator.CONTAINS, "AABBCC02"),
        ]

        when:
        def both = readerResource.query(query)

        then:
        both.rows*.name as Set == ["Water meter A", "Water meter B"] as Set

        when: "an alarm filter on a different attribute is ANDed with the devEUI OR"
        def leakage = new FleetQuery.AttributeFilter()
        leakage.name = "leakage"
        leakage.value = true
        query.attributes = query.attributes + [leakage]
        def narrowed = readerResource.query(query)

        then:
        narrowed.rows*.name == ["Water meter A"]

        when: "free text is ANDed with the devEUI OR as well"
        query.attributes = query.attributes.take(2)
        query.text = ["meter B"]
        def withText = readerResource.query(query)

        then:
        withText.rows*.name == ["Water meter B"]
    }

    static FleetQuery.AttributeFilter devEui(FleetQuery.Operator operator, String value) {
        def filter = new FleetQuery.AttributeFilter()
        filter.name = "devEui"
        filter.operator = operator
        filter.value = value
        return filter
    }

    def "parent name is resolved for location context"() {
        when:
        def result = readerResource.query(new FleetQuery())

        then:
        def names = [(buildingId): "Building", (floorId): "Floor"]
        result.rows.findAll { it.parentId != null }.every { it.parentName == names[it.parentId] }
        result.facets.parents.every { it.label == names[it.key] }
    }

    def "a restricted user only sees their own linked assets, with a usable row"() {
        when:
        def result = restrictedResource.query(new FleetQuery())

        then: "the other meters in the same realm are invisible"
        result.total == 1
        result.rows[0].id == linkedAssetId

        and: "identity attributes are readable after Task 0"
        result.rows[0].devEui != null
        result.rows[0].meterId != null

        and: "lastSeen comes from the server-side lookup although rawValue is hidden from them"
        result.rows[0].lastSeen != null

        and: "the unlinked parent's name is not disclosed"
        result.rows[0].parentName == null
    }

    def "a user of another realm cannot read this realm's fleet"() {
        when:
        outsiderResource.query(new FleetQuery())

        then: "the JWT filter rejects a foreign-realm token; authorizeAssetQuery's realm check is defence in depth"
        thrown(NotAuthorizedException)
    }

    def "a super user reads another realm's fleet through the query's realm"() {
        when:
        def result = superUserResource.query(new FleetQuery(realm: REALM))

        then: "the rows are the fixture realm's, although the request realm is master"
        result.total > 0
        result.rows*.id.contains(linkedAssetId)
    }

    def "a non-super user cannot widen the query's realm beyond their own"() {
        when:
        outsiderOwnRealmResource.query(new FleetQuery(realm: REALM))

        then: "authorizeAssetQuery rejects a realm other than the caller's"
        thrown(ForbiddenException)
    }

    def "rawValue never appears in the response payload, for any caller"() {
        when: "the bytes the server sent, not a client DTO re-serialised (which would drop unknown fields)"
        def readerJson = rawQueryBody(readerToken, "{}")
        def restrictedJson = rawQueryBody(restrictedToken, "{}")

        then: "the response really carries the meter whose rawValue was stored"
        readerJson.contains(linkedAssetId)
        restrictedJson.contains(linkedAssetId)

        and: "neither the attribute name nor the payload leaves the server"
        !readerJson.contains("rawValue")
        !restrictedJson.contains("rawValue")
        !readerJson.contains(RAW_PAYLOAD_MARKER)
        !restrictedJson.contains(RAW_PAYLOAD_MARKER)
    }

    def "filtering on rawValue is rejected rather than used as an oracle"() {
        given:
        def query = new FleetQuery()
        def filter = new FleetQuery.AttributeFilter()
        filter.name = "rawValue"
        filter.operator = FleetQuery.Operator.CONTAINS
        filter.value = "EUI"
        query.attributes = [filter]

        when:
        restrictedResource.query(query)

        then:
        thrown(BadRequestException)
    }

    def "a malformed seen duration is a 400, not a 500"() {
        given:
        def query = new FleetQuery()
        query.seen = new FleetQuery.SeenFilter()
        query.seen.olderThan = "24 hours"

        when:
        readerResource.query(query)

        then:
        thrown(BadRequestException)
    }

    def "filtering on rawValue is rejected regardless of letter case"() {
        given:
        def query = new FleetQuery()
        def filter = new FleetQuery.AttributeFilter()
        filter.name = "RAWVALUE"
        filter.operator = FleetQuery.Operator.CONTAINS
        filter.value = "EUI"
        query.attributes = [filter]

        when:
        restrictedResource.query(query)

        then:
        thrown(BadRequestException)
    }

    static FleetQuery siteText(String parentId, String text) {
        def query = new FleetQuery()
        query.parentIds = [parentId]
        query.recursive = true
        query.text = [text]
        return query
    }

    def "free text under a site matches names at every level below it"() {
        when: "the meter sits under Floor, not directly under Building"
        def nested = readerResource.query(siteText(buildingId, "meter c"))

        then:
        nested.total == 1
        nested.rows[0].name == "Water meter C"

        when: "the text names an intermediate location, not a device"
        def intermediate = readerResource.query(siteText(buildingId, "Floor"))

        then: "the meter below it does not match by inheritance"
        intermediate.total == 0
    }

    def "a restricted user's free text under a site returns their linked meter"() {
        when:
        def result = restrictedResource.query(siteText(buildingId, "meter A"))

        then:
        result.total == 1
        result.rows[0].id == linkedAssetId
    }
}
