package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.IotWatchSsoService
import org.openremote.manager.iotwatch.OidcDiscovery
import org.openremote.manager.iotwatch.SsoConfig
import org.openremote.model.PersistenceEvent
import org.openremote.model.security.Realm
import spock.lang.Specification

class IotWatchSsoServiceTest extends Specification {

    static final OidcDiscovery DISCOVERY = new OidcDiscovery("https://sso.example/auth/realms/CRA",
        "https://sso.example/auth", "https://sso.example/token", "https://sso.example/certs", null, null)

    /** Records the configured realms; the discovery document is stubbed and counted instead of fetched. */
    static class TestableService extends IotWatchSsoService {
        List<String> configured = []
        int fetches = 0
        boolean issuerDown = false

        @Override protected OidcDiscovery fetchDiscovery() throws IOException {
            fetches++
            if (issuerDown) {
                throw new IOException("connect timed out")
            }
            return DISCOVERY
        }

        @Override protected void configureRealm(String realm) throws Exception {
            discovery()   // like the real implementation, a realm cannot be configured without the endpoints
            configured << realm
        }
    }

    static SsoConfig config(String realms) {
        def env = [OR_IOTWATCH_SSO_ISSUER: "https://sso.example/auth/realms/CRA", OR_IOTWATCH_SSO_CLIENT_ID: "c",
                   OR_IOTWATCH_SSO_CLIENT_SECRET: "s"]
        if (realms != null) {
            env.OR_IOTWATCH_SSO_REALMS = realms
        }
        return SsoConfig.parse(env).get()
    }

    static PersistenceEvent<Realm> event(PersistenceEvent.Cause cause, String name) {
        new PersistenceEvent<Realm>(cause, new Realm(null, name, name, true), new String[0], new Object[0])
    }

    def "configures a newly created realm that the realm list admits, and nothing else"() {
        given:
        def service = new TestableService()
        service.config = config("master,customer-a")

        when:
        service.onRealmChange(event(PersistenceEvent.Cause.CREATE, "customer-a"))
        service.onRealmChange(event(PersistenceEvent.Cause.CREATE, "customer-b"))
        service.onRealmChange(event(PersistenceEvent.Cause.UPDATE, "master"))
        service.onRealmChange(event(PersistenceEvent.Cause.DELETE, "master"))

        then:
        service.configured == ["customer-a"]
    }

    def "reconciles every existing realm at startup, master included, when no list is given"() {
        given:
        def service = new TestableService()
        service.config = config(null)

        when:
        service.reconcile(["master", "customer-a", "customer-b"])

        then:
        service.configured == ["master", "customer-a", "customer-b"]
    }

    def "a failing realm does not stop the reconciliation of the others"() {
        given:
        def service = new TestableService() {
            @Override protected void configureRealm(String realm) throws Exception {
                if (realm == "customer-a") {
                    throw new IllegalStateException("tampered flow")
                }
                super.configureRealm(realm)
            }
        }
        service.config = config(null)

        when:
        service.reconcile(["master", "customer-a", "customer-b"])

        then:
        service.configured == ["master", "customer-b"]
    }

    def "the discovery document is read once, not once per realm"() {
        given:
        def service = new TestableService()
        service.config = config(null)

        when:
        service.reconcile(["master", "customer-a", "customer-b"])
        service.onRealmChange(event(PersistenceEvent.Cause.CREATE, "customer-c"))

        then:
        service.fetches == 1
        service.configured == ["master", "customer-a", "customer-b", "customer-c"]
    }

    def "an unreachable issuer stops the startup reconcile after one attempt and is retried on the next realm creation"() {
        given:
        def service = new TestableService()
        service.config = config(null)
        service.issuerDown = true

        when:
        service.reconcile(["master", "customer-a"])

        then:
        service.fetches == 1
        service.configured == []

        when:
        service.issuerDown = false
        service.onRealmChange(event(PersistenceEvent.Cause.CREATE, "customer-a"))

        then:
        service.fetches == 2
        service.configured == ["customer-a"]
    }

    def "names in the realm list that match no realm are reported"() {
        given:
        def service = new TestableService()
        service.config = config("master,typo")

        expect:
        service.unknownRealms(["master", "customer-a"]) == ["typo"] as Set
        service.unknownRealms(["master", "typo"]).isEmpty()
    }
}
