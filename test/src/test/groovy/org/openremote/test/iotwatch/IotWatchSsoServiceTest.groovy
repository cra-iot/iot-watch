package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.IotWatchSsoService
import org.openremote.manager.iotwatch.SsoConfig
import org.openremote.model.PersistenceEvent
import org.openremote.model.security.Realm
import spock.lang.Specification

class IotWatchSsoServiceTest extends Specification {

    static class TestableService extends IotWatchSsoService {
        List<String> configured = []
        @Override protected void configureRealm(String realm) { configured << realm }
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
        service.config = config("master,vodomery")

        when:
        service.onRealmChange(event(PersistenceEvent.Cause.CREATE, "vodomery"))
        service.onRealmChange(event(PersistenceEvent.Cause.CREATE, "ppas"))
        service.onRealmChange(event(PersistenceEvent.Cause.UPDATE, "master"))
        service.onRealmChange(event(PersistenceEvent.Cause.DELETE, "master"))

        then:
        service.configured == ["vodomery"]
    }

    def "reconciles every existing realm at startup, master included, when no list is given"() {
        given:
        def service = new TestableService()
        service.config = config(null)

        when:
        service.reconcile(["master", "vodomery", "ppas"])

        then:
        service.configured == ["master", "vodomery", "ppas"]
    }

    def "a failing realm does not stop the reconciliation of the others"() {
        given:
        def service = new TestableService() {
            @Override protected void configureRealm(String realm) {
                if (realm == "vodomery") throw new IllegalStateException("tampered flow")
                super.configureRealm(realm)
            }
        }
        service.config = config(null)

        when:
        service.reconcile(["master", "vodomery", "ppas"])

        then:
        service.configured == ["master", "ppas"]
    }
}
