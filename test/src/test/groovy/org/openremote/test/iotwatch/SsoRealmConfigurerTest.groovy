package org.openremote.test.iotwatch

import jakarta.ws.rs.core.Response
import org.keycloak.admin.client.resource.AuthenticationManagementResource
import org.keycloak.admin.client.resource.IdentityProviderResource
import org.keycloak.admin.client.resource.IdentityProvidersResource
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation
import org.keycloak.representations.idm.AuthenticationFlowRepresentation
import org.keycloak.representations.idm.IdentityProviderRepresentation
import org.openremote.manager.iotwatch.OidcDiscovery
import org.openremote.manager.iotwatch.SsoConfig
import org.openremote.manager.iotwatch.SsoRealmConfigurer
import spock.lang.Specification

import static org.openremote.manager.iotwatch.SsoRealmConfigurer.AUTO_LINK
import static org.openremote.manager.iotwatch.SsoRealmConfigurer.DETECT_EXISTING
import static org.openremote.manager.iotwatch.SsoRealmConfigurer.Outcome.CREATED
import static org.openremote.manager.iotwatch.SsoRealmConfigurer.Outcome.UPDATED

class SsoRealmConfigurerTest extends Specification {

    static final String FLOW = "cra-sso-first-login"

    static SsoConfig config(Map<String, Object> extra = [:]) {
        SsoConfig.parse([OR_IOTWATCH_SSO_ISSUER: "https://sso.example/auth/realms/CRA", OR_IOTWATCH_SSO_CLIENT_ID: "iot-watch-test",
                         OR_IOTWATCH_SSO_CLIENT_SECRET: "s3cret"] + extra).get()
    }

    static OidcDiscovery discovery() {
        new OidcDiscovery("https://sso.example/auth/realms/CRA", "https://sso.example/auth", "https://sso.example/token",
            "https://sso.example/certs", "https://sso.example/userinfo", "https://sso.example/logout")
    }

    static AuthenticationExecutionInfoRepresentation execution(String id, String providerId, String requirement) {
        def execution = new AuthenticationExecutionInfoRepresentation()
        execution.id = id
        execution.providerId = providerId
        execution.requirement = requirement
        return execution
    }

    static AuthenticationFlowRepresentation flow(String alias) {
        def flow = new AuthenticationFlowRepresentation()
        flow.alias = alias
        return flow
    }

    static IdentityProviderRepresentation existingProvider(String alias, Map<String, String> config) {
        def rep = new IdentityProviderRepresentation()
        rep.alias = alias
        rep.internalId = "internal-1"
        rep.config = config
        return rep
    }

    AuthenticationManagementResource flows = Mock()
    IdentityProvidersResource providers = Mock()
    IdentityProviderResource provider = Mock()
    RealmResource realm = Mock() {
        flows() >> flows
        identityProviders() >> providers
    }

    def "creates the detect-and-link flow and the provider in a realm without SSO"() {
        given:
        def configurer = new SsoRealmConfigurer(config(), discovery())
        flows.getFlows() >> []
        flows.getExecutions(FLOW) >>> [[], [execution("e1", DETECT_EXISTING, "DISABLED"), execution("e2", AUTO_LINK, "DISABLED")]]
        providers.findAll() >> []

        when:
        def outcome = configurer.apply(realm)

        then:
        outcome == CREATED
        1 * flows.createFlow({ AuthenticationFlowRepresentation f ->
            f.alias == FLOW && f.providerId == "basic-flow" && f.topLevel && !f.builtIn
        }) >> Response.created(URI.create("flow")).build()
        1 * flows.addExecution(FLOW, [provider: DETECT_EXISTING])
        1 * flows.addExecution(FLOW, [provider: AUTO_LINK])
        1 * flows.updateExecutions(FLOW, { it.id == "e1" && it.requirement == "REQUIRED" })
        1 * flows.updateExecutions(FLOW, { it.id == "e2" && it.requirement == "REQUIRED" })
        1 * providers.create({ IdentityProviderRepresentation rep ->
            rep.alias == "cra-sso" && rep.displayName == "CRA SSO" && rep.providerId == "oidc" && rep.enabled &&
                rep.trustEmail && !rep.storeToken && !rep.hideOnLogin &&
                rep.firstBrokerLoginFlowAlias == FLOW && !rep.postBrokerLoginFlowAlias &&
                rep.config.clientId == "iot-watch-test" && rep.config.clientSecret == "s3cret" &&
                rep.config.clientAuthMethod == "client_secret_post" &&
                rep.config.issuer == "https://sso.example/auth/realms/CRA" &&
                rep.config.authorizationUrl == "https://sso.example/auth" && rep.config.tokenUrl == "https://sso.example/token" &&
                rep.config.jwksUrl == "https://sso.example/certs" && rep.config.useJwksUrl == "true" &&
                rep.config.validateSignature == "true" && rep.config.defaultScope == "openid email profile" &&
                rep.config.syncMode == "IMPORT" && !rep.config.logoutUrl
        }) >> Response.created(URI.create("idp")).build()
        0 * providers.get(_)
    }

    def "leaves a correct flow alone and updates the provider in place, keeping unknown config keys"() {
        given:
        def configurer = new SsoRealmConfigurer(config(), discovery())
        flows.getFlows() >> [flow("browser"), flow(FLOW)]
        flows.getExecutions(FLOW) >> [execution("e1", DETECT_EXISTING, "REQUIRED"), execution("e2", AUTO_LINK, "REQUIRED")]
        providers.findAll() >> [existingProvider("cra-sso", [guiOrder: "5", clientSecret: "**********", logoutUrl: "https://old/logout"])]
        providers.get("cra-sso") >> provider

        when:
        def outcome = configurer.apply(realm)

        then:
        outcome == UPDATED
        0 * flows.createFlow(_)
        0 * flows.addExecution(*_)
        0 * flows.updateExecutions(*_)
        0 * providers.create(_)
        1 * provider.update({ IdentityProviderRepresentation rep ->
            rep.internalId == "internal-1" && rep.config.guiOrder == "5" && rep.config.clientSecret == "s3cret" &&
                rep.config.tokenUrl == "https://sso.example/token" && !rep.config.logoutUrl
        })
    }

    def "repairs the requirement of an execution that was switched off"() {
        given:
        def configurer = new SsoRealmConfigurer(config(), discovery())
        flows.getFlows() >> [flow(FLOW)]
        flows.getExecutions(FLOW) >> [execution("e1", DETECT_EXISTING, "REQUIRED"), execution("e2", AUTO_LINK, "ALTERNATIVE")]
        providers.findAll() >> []
        providers.create(_) >> Response.created(URI.create("idp")).build()

        when:
        configurer.apply(realm)

        then:
        0 * flows.addExecution(*_)
        1 * flows.updateExecutions(FLOW, { it.id == "e2" && it.requirement == "REQUIRED" })
    }

    def "refuses a flow somebody extended by hand and touches nothing"() {
        given:
        def configurer = new SsoRealmConfigurer(config(), discovery())
        flows.getFlows() >> [flow(FLOW)]
        flows.getExecutions(FLOW) >> [execution("e1", DETECT_EXISTING, "REQUIRED"), execution("e2", AUTO_LINK, "REQUIRED"),
                                      execution("e3", "idp-create-user-if-unique", "ALTERNATIVE")]

        when:
        configurer.apply(realm)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains("idp-create-user-if-unique")
        0 * flows.updateExecutions(*_)
        0 * flows.removeExecution(_)
        0 * providers.create(_)
        0 * providers.get(_)
    }

    def "refuses a flow whose executions are in the wrong order"() {
        given:
        def configurer = new SsoRealmConfigurer(config(), discovery())
        flows.getFlows() >> [flow(FLOW)]
        flows.getExecutions(FLOW) >> [execution("e2", AUTO_LINK, "REQUIRED"), execution("e1", DETECT_EXISTING, "REQUIRED")]

        when:
        configurer.apply(realm)

        then:
        thrown(IllegalStateException)
        0 * providers.create(_)
    }

    def "sets the logout URL only when logout propagation is configured"() {
        given:
        def configurer = new SsoRealmConfigurer(config([OR_IOTWATCH_SSO_LOGOUT: "true"]), discovery())
        flows.getFlows() >> [flow(FLOW)]
        flows.getExecutions(FLOW) >> [execution("e1", DETECT_EXISTING, "REQUIRED"), execution("e2", AUTO_LINK, "REQUIRED")]
        providers.findAll() >> []

        when:
        configurer.apply(realm)

        then:
        1 * providers.create({ it.config.logoutUrl == "https://sso.example/logout" }) >> Response.created(URI.create("idp")).build()
    }

    def "a failed provider creation surfaces as an error"() {
        given:
        def configurer = new SsoRealmConfigurer(config(), discovery())
        flows.getFlows() >> [flow(FLOW)]
        flows.getExecutions(FLOW) >> [execution("e1", DETECT_EXISTING, "REQUIRED"), execution("e2", AUTO_LINK, "REQUIRED")]
        providers.findAll() >> []
        providers.create(_) >> Response.status(400).entity('{"errorMessage":"bad"}').build()

        when:
        configurer.apply(realm)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains("400")
    }

    def "keeps a provider that an operator disabled in the console disabled"() {
        given:
        def configurer = new SsoRealmConfigurer(config(), discovery())
        flows.getFlows() >> [flow(FLOW)]
        flows.getExecutions(FLOW) >> [execution("e1", DETECT_EXISTING, "REQUIRED"), execution("e2", AUTO_LINK, "REQUIRED")]
        def disabled = existingProvider("cra-sso", [:])
        disabled.enabled = false
        providers.findAll() >> [disabled]
        providers.get("cra-sso") >> provider

        when:
        configurer.apply(realm)

        then:
        1 * provider.update({ IdentityProviderRepresentation rep -> !rep.enabled && rep.config.clientSecret == "s3cret" })
    }

    def "refuses a flow that contains a sub-flow with the same clear message"() {
        given:
        def configurer = new SsoRealmConfigurer(config(), discovery())
        flows.getFlows() >> [flow(FLOW)]
        flows.getExecutions(FLOW) >> [execution("e1", DETECT_EXISTING, "REQUIRED"), execution("e2", AUTO_LINK, "REQUIRED"),
                                      execution("e3", null, "ALTERNATIVE")]   // a nested sub-flow has no provider id

        when:
        configurer.apply(realm)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains("unexpected executions")
        0 * providers.create(_)
        0 * providers.get(_)
    }
}
