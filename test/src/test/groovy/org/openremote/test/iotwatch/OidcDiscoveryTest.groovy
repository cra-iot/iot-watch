package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.OidcDiscovery
import spock.lang.Specification

class OidcDiscoveryTest extends Specification {

    static final String DOCUMENT = '''{
        "issuer": "https://sso.example/auth/realms/CRA",
        "authorization_endpoint": "https://sso.example/auth/realms/CRA/protocol/openid-connect/auth",
        "token_endpoint": "https://sso.example/auth/realms/CRA/protocol/openid-connect/token",
        "userinfo_endpoint": "https://sso.example/auth/realms/CRA/protocol/openid-connect/userinfo",
        "jwks_uri": "https://sso.example/auth/realms/CRA/protocol/openid-connect/certs",
        "end_session_endpoint": "https://sso.example/auth/realms/CRA/protocol/openid-connect/logout",
        "grant_types_supported": ["authorization_code"]
    }'''

    def "takes the broker endpoints from the discovery document"() {
        when:
        def discovery = OidcDiscovery.parse(DOCUMENT)

        then:
        discovery.issuer() == "https://sso.example/auth/realms/CRA"
        discovery.authorizationUrl() == "https://sso.example/auth/realms/CRA/protocol/openid-connect/auth"
        discovery.tokenUrl() == "https://sso.example/auth/realms/CRA/protocol/openid-connect/token"
        discovery.jwksUrl() == "https://sso.example/auth/realms/CRA/protocol/openid-connect/certs"
        discovery.userInfoUrl() == "https://sso.example/auth/realms/CRA/protocol/openid-connect/userinfo"
        discovery.endSessionUrl() == "https://sso.example/auth/realms/CRA/protocol/openid-connect/logout"
    }

    def "optional endpoints are null when absent"() {
        when:
        def discovery = OidcDiscovery.parse('{"issuer":"i","authorization_endpoint":"a","token_endpoint":"t","jwks_uri":"j"}')

        then:
        discovery.userInfoUrl() == null
        discovery.endSessionUrl() == null
    }

    def "a document without the mandatory endpoints is rejected"() {
        when:
        OidcDiscovery.parse('{"issuer":"i","token_endpoint":"t"}')

        then:
        thrown(IllegalArgumentException)
    }
}
