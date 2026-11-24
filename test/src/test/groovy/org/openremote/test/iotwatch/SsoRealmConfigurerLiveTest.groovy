package org.openremote.test.iotwatch

import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.KeycloakBuilder
import org.keycloak.representations.idm.RealmRepresentation
import org.openremote.manager.iotwatch.OidcDiscovery
import org.openremote.manager.iotwatch.SsoConfig
import org.openremote.manager.iotwatch.SsoRealmConfigurer
import spock.lang.IgnoreIf
import spock.lang.Specification

import static org.openremote.manager.iotwatch.SsoRealmConfigurer.AUTO_LINK
import static org.openremote.manager.iotwatch.SsoRealmConfigurer.DETECT_EXISTING

// Exercises the configurer against a real Keycloak; SsoRealmConfigurerTest mocks the admin client.
// Runs only when OR_SSO_LIVE_KEYCLOAK_URL (e.g. https://test-watch.iot.cra.cz/auth), OR_SSO_LIVE_ISSUER
// (any OIDC issuer reachable from here) and OR_ADMIN_PASSWORD are set. It creates a throwaway realm
// and deletes it afterwards; nothing else on the server is touched.
@IgnoreIf({ !env['OR_SSO_LIVE_KEYCLOAK_URL'] })
class SsoRealmConfigurerLiveTest extends Specification {

    def "applies the flow and the provider to a fresh realm and re-applies without duplicates"() {
        given:
        def url = System.getenv("OR_SSO_LIVE_KEYCLOAK_URL")
        def issuer = System.getenv("OR_SSO_LIVE_ISSUER")
        Keycloak keycloak = KeycloakBuilder.builder().serverUrl(url).realm("master").clientId("admin-cli")
            .username("admin").password(System.getenv("OR_ADMIN_PASSWORD")).build()
        def realmName = "sso-live-" + Long.toHexString(System.nanoTime())
        def representation = new RealmRepresentation()
        representation.realm = realmName
        representation.enabled = true
        keycloak.realms().create(representation)
        def realm = keycloak.realm(realmName)
        def config = SsoConfig.parse([OR_IOTWATCH_SSO_ISSUER: issuer, OR_IOTWATCH_SSO_CLIENT_ID: "iot-watch-test",
                                      OR_IOTWATCH_SSO_CLIENT_SECRET: "not-a-real-secret"]).get()
        def configurer = new SsoRealmConfigurer(config, OidcDiscovery.fetch(issuer))

        when:
        def first = configurer.apply(realm)
        def second = configurer.apply(realm)

        then:
        first == SsoRealmConfigurer.Outcome.CREATED
        second == SsoRealmConfigurer.Outcome.UPDATED
        def executions = realm.flows().getExecutions(config.flowAlias())
        executions*.providerId == [DETECT_EXISTING, AUTO_LINK]
        executions*.requirement == ["REQUIRED", "REQUIRED"]
        def providers = realm.identityProviders().findAll()
        providers*.alias == ["cra-sso"]
        providers[0].providerId == "oidc"
        providers[0].enabled
        providers[0].trustEmail
        providers[0].firstBrokerLoginFlowAlias == config.flowAlias()
        providers[0].getConfig().authorizationUrl == issuer + "/protocol/openid-connect/auth"
        providers[0].getConfig().clientId == "iot-watch-test"

        cleanup:
        realm?.remove()
        keycloak?.close()
    }
}
