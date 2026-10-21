package org.openremote.test.iotwatch

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.ForbiddenException
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.model.Constants
import org.openremote.model.asset.UserAssetLink
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.gasmeter.GasMeterAsset
import org.openremote.model.iotwatch.FleetQuery
import org.openremote.model.iotwatch.FleetResource
import org.openremote.model.security.Realm
import org.openremote.model.security.User
import org.openremote.model.tracker.TrackerAsset
import org.openremote.model.util.ValueUtil
import org.openremote.model.value.ValueType
import org.openremote.model.watermeter.WaterMeterAsset
import org.openremote.test.ManagerContainerTrait
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

// Boots the OpenRemote container (ManagerContainerTrait), which needs a running PostgreSQL and Keycloak.
// Runs only when OR_CONTAINER_TESTS=true (i.e. the dev stack is up); otherwise the spec is skipped.
// Fixtures use their own realms and users; all identifiers are fixture values, never real device or delivery-point codes.
@IgnoreIf({ env['OR_CONTAINER_TESTS'] != 'true' })
class FleetEndpointTest extends Specification implements ManagerContainerTrait {

    static final String REALM = "fleet-test"
    static final String OTHER_REALM = "fleet-other"
    static final String PASSWORD = "dummy-test-password-not-a-secret"

    @Shared FleetResource readerResource
    @Shared FleetResource restrictedResource
    @Shared FleetResource outsiderResource
    @Shared String linkedAssetId

    def setupSpec() {
        def serverPort = findEphemeralPort()
        def container = startContainer(defaultConfig(serverPort), defaultServices())
        def identityProvider = container.getService(ManagerIdentityService).getIdentityProvider()
        def assetStorageService = container.getService(AssetStorageService)

        // Realms
        [REALM, OTHER_REALM].each { name ->
            identityProvider.createRealm(new Realm().setName(name).setDisplayName(name).setEnabled(true))
        }

        // Assets (created after the Task 0 descriptor change, so identity attributes carry ACCESS_RESTRICTED_READ)
        def building = new ThingAsset("Building")
        building.setRealm(REALM)
        building = assetStorageService.merge(building)

        def waterWithRaw = new WaterMeterAsset("Water meter A")
        waterWithRaw.setRealm(REALM)
        waterWithRaw.setParentId(building.getId())
        waterWithRaw.getAttributes().getOrCreate(WaterMeterAsset.DEV_EUI_ATTRIBUTE_DESCRIPTOR).setValue("00112233AABBCC01")
        waterWithRaw.getAttributes().getOrCreate(WaterMeterAsset.METER_ID_ATTRIBUTE_DESCRIPTOR).setValue("TEST-METER-0001")
        waterWithRaw.getAttributes().getOrCreate(WaterMeterAsset.LEAKAGE_ATTRIBUTE_DESCRIPTOR).setValue(true)
        // rawValue with an explicit timestamp taken from the container clock (not older than the merge)
        def rawValue = new ValueType.ObjectMap()
        rawValue.put("cmd", "gw")
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
        def reader = identityProvider.createUpdateUser(REALM, new User().setUsername("fleet-reader").setEnabled(true), PASSWORD, false)
        identityProvider.updateUserClientRoles(REALM, reader.getId(), Constants.KEYCLOAK_CLIENT_ID, "read:assets")

        def restricted = identityProvider.createUpdateUser(REALM, new User().setUsername("fleet-restricted").setEnabled(true), PASSWORD, false)
        identityProvider.updateUserRealmRoles(REALM, restricted.getId(), Constants.RESTRICTED_USER_REALM_ROLE)
        identityProvider.updateUserClientRoles(REALM, restricted.getId(), Constants.KEYCLOAK_CLIENT_ID, "read:assets")
        // Linked to the meter with rawValue only, deliberately not to "Building"
        assetStorageService.storeUserAssetLinks([new UserAssetLink(REALM, restricted.getId(), linkedAssetId)])

        def outsider = identityProvider.createUpdateUser(OTHER_REALM, new User().setUsername("fleet-outsider").setEnabled(true), PASSWORD, false)
        identityProvider.updateUserClientRoles(OTHER_REALM, outsider.getId(), Constants.KEYCLOAK_CLIENT_ID, "read:assets")

        // Tokens and proxies; the outsider calls the fleet-test path with a fleet-other token
        def proxy = { String tokenRealm, String username ->
            def token = authenticate(container, tokenRealm, Constants.KEYCLOAK_CLIENT_ID, username, PASSWORD)
            getClientApiTarget(serverUri(serverPort), REALM, token).proxy(FleetResource)
        }
        readerResource = proxy(REALM, "fleet-reader")
        restrictedResource = proxy(REALM, "fleet-restricted")
        outsiderResource = proxy(OTHER_REALM, "fleet-outsider")
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
        query.text = "TEST-DP-0001"

        when:
        def result = readerResource.query(query)

        then:
        result.total == 1
        result.rows[0].deliveryPointId == "TEST-DP-0001"
    }

    def "parent name is resolved for location context"() {
        when:
        def result = readerResource.query(new FleetQuery())

        then:
        result.rows.findAll { it.parentId != null }.every { it.parentName == "Building" }
        result.facets.parents.every { it.label == "Building" }
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

        then:
        thrown(ForbiddenException)
    }

    def "rawValue never appears in the response payload, for any caller"() {
        when:
        def readerJson = ValueUtil.JSON.writeValueAsString(readerResource.query(new FleetQuery()))
        def restrictedJson = ValueUtil.JSON.writeValueAsString(restrictedResource.query(new FleetQuery()))

        then:
        !readerJson.contains("rawValue")
        !restrictedJson.contains("rawValue")
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
}
