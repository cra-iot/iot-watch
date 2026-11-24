package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.SsoConfig
import spock.lang.Specification

class SsoConfigTest extends Specification {

    static Map<String, Object> minimal() {
        [OR_IOTWATCH_SSO_ISSUER   : "https://test-sso.example/auth/realms/CRA/",
         OR_IOTWATCH_SSO_CLIENT_ID: "iot-watch-test",
         OR_IOTWATCH_SSO_CLIENT_SECRET: "s3cret"]
    }

    def "issuer, client id and secret enable the service with defaults for the rest"() {
        when:
        def config = SsoConfig.parse(minimal()).get()

        then:
        config.issuer() == "https://test-sso.example/auth/realms/CRA"   // trailing slash dropped
        config.clientId() == "iot-watch-test"
        config.clientSecret() == "s3cret"
        config.alias() == "cra-sso"
        config.displayName() == "CRA SSO"
        config.flowAlias() == "cra-sso-first-login"
        !config.logout()
        config.appliesTo("master")        // every realm, master included, unless a list is given
        config.appliesTo("vodomery")
    }

    def "a missing #missing disables the service"() {
        given:
        def env = minimal()
        env.remove(missing)

        expect:
        SsoConfig.parse(env).isEmpty()

        where:
        missing << ["OR_IOTWATCH_SSO_ISSUER", "OR_IOTWATCH_SSO_CLIENT_ID", "OR_IOTWATCH_SSO_CLIENT_SECRET"]
    }

    def "a blank value counts as missing"() {
        expect:
        SsoConfig.parse(minimal() + [OR_IOTWATCH_SSO_CLIENT_SECRET: "  "]).isEmpty()
        SsoConfig.parse([:]).isEmpty()
    }

    def "a realm list restricts where SSO is configured"() {
        when:
        def config = SsoConfig.parse(minimal() + [OR_IOTWATCH_SSO_REALMS: " master, vodomery ,"]).get()

        then:
        config.appliesTo("master")
        config.appliesTo("vodomery")
        !config.appliesTo("ppas")
    }

    def "alias, display name and logout can be overridden"() {
        when:
        def config = SsoConfig.parse(minimal() + [OR_IOTWATCH_SSO_ALIAS: "cra", OR_IOTWATCH_SSO_DISPLAY_NAME: "Firemní účet",
                                                  OR_IOTWATCH_SSO_LOGOUT: "true"]).get()

        then:
        config.alias() == "cra"
        config.displayName() == "Firemní účet"
        config.flowAlias() == "cra-first-login"
        config.logout()
    }
}
