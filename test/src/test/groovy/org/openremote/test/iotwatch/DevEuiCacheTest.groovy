package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.DevEuiCache
import spock.lang.Specification

class DevEuiCacheTest extends Specification {

    def cache = new DevEuiCache()

    def "resolves case-insensitively within a realm"() {
        given:
        cache.put("master", "00112233aabbccdd", "asset1")

        expect:
        cache.resolve("master", "00112233AABBCCDD") == ["asset1"] as Set
        cache.resolve("master", "00112233aabbccdd") == ["asset1"] as Set
        cache.resolve("other", "00112233AABBCCDD").isEmpty()
        cache.resolve("master", "0000000000000000").isEmpty()
        cache.resolve(null, "00112233AABBCCDD").isEmpty()
        cache.resolve("master", null).isEmpty()
    }

    def "two assets with the same EUI are both reported (conflict)"() {
        given:
        cache.put("master", "00112233AABBCCDD", "asset1")
        cache.put("master", "00112233AABBCCDD", "asset2")

        expect:
        cache.resolve("master", "00112233AABBCCDD") == ["asset1", "asset2"] as Set
    }

    def "re-putting an asset with a new EUI removes the old mapping"() {
        given:
        cache.put("master", "AAAAAAAAAAAAAAAA", "asset1")

        when:
        cache.put("master", "BBBBBBBBBBBBBBBB", "asset1")

        then:
        cache.resolve("master", "AAAAAAAAAAAAAAAA").isEmpty()
        cache.resolve("master", "BBBBBBBBBBBBBBBB") == ["asset1"] as Set
    }

    def "remove deletes the asset's mapping"() {
        given:
        cache.put("master", "AAAAAAAAAAAAAAAA", "asset1")
        cache.put("master", "AAAAAAAAAAAAAAAA", "asset2")

        when:
        cache.remove("asset1")

        then:
        cache.resolve("master", "AAAAAAAAAAAAAAAA") == ["asset2"] as Set

        when:
        cache.remove("asset2")
        cache.remove("unknown")   // no-op, must not throw

        then:
        cache.resolve("master", "AAAAAAAAAAAAAAAA").isEmpty()
    }

    def "reports cache size per realm"() {
        given:
        cache.put("master", "AAAAAAAAAAAAAAAA", "asset1")
        cache.put("master", "BBBBBBBBBBBBBBBB", "asset2")
        cache.put("other", "CCCCCCCCCCCCCCCC", "asset3")

        expect:
        cache.size("master") == 2
        cache.size("other") == 1
        cache.size("nothing") == 0
    }
}
