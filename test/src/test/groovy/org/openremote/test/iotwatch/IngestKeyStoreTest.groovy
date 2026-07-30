package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.IngestKeyStore
import spock.lang.Specification

class IngestKeyStoreTest extends Specification {

    def "parses realm:key pairs and checks keys per realm"() {
        given:
        def store = IngestKeyStore.parse("master:key-one,customer1:key-two")

        expect:
        !store.isEmpty()
        store.check("master", "key-one")
        store.check("customer1", "key-two")
        !store.check("master", "key-two")      // right key, wrong realm
        !store.check("master", "wrong")
        !store.check("unknown", "key-one")
        !store.check("master", null)
        !store.check(null, "key-one")
    }

    def "tolerates whitespace around entries"() {
        expect:
        IngestKeyStore.parse(" master : key-one , customer1:key-two ").check("master", "key-one")
    }

    def "skips malformed entries but keeps valid ones"() {
        given:
        def store = IngestKeyStore.parse("nocolon,master:key-one,:nokey,norealm:")

        expect:
        store.check("master", "key-one")
        !store.check("nocolon", "nocolon")
    }

    def "empty or null config produces an empty store"() {
        expect:
        IngestKeyStore.parse(config).isEmpty()

        where:
        config << [null, "", "   "]
    }

    def "exposes configured realm names"() {
        expect:
        IngestKeyStore.parse("master:key-one,customer1:key-two").realms() == ["master", "customer1"] as Set
        IngestKeyStore.parse(null).realms().isEmpty()
    }
}
