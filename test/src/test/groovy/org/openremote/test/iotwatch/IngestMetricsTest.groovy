package org.openremote.test.iotwatch

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.openremote.manager.iotwatch.IngestMetrics
import spock.lang.Specification

import java.util.function.Supplier

class IngestMetricsTest extends Specification {

    def registry = new SimpleMeterRegistry()
    def metrics = IngestMetrics.create(registry)

    def "counts requests per realm and outcome"() {
        when:
        metrics.countRequest("master", IngestMetrics.OUTCOME_ACCEPTED)
        metrics.countRequest("master", IngestMetrics.OUTCOME_ACCEPTED)
        metrics.countRequest("master", IngestMetrics.OUTCOME_CONFLICT)

        then:
        registry.get(IngestMetrics.REQUESTS_METER_NAME)
            .tags("realm", "master", "outcome", IngestMetrics.OUTCOME_ACCEPTED).counter().count() == 2.0d
        registry.get(IngestMetrics.REQUESTS_METER_NAME)
            .tags("realm", "master", "outcome", IngestMetrics.OUTCOME_CONFLICT).counter().count() == 1.0d
    }

    def "maps null realm to the unknown bucket"() {
        when:
        metrics.countRequest(null, IngestMetrics.OUTCOME_UNAUTHORIZED)

        then:
        registry.get(IngestMetrics.REQUESTS_METER_NAME)
            .tags("realm", IngestMetrics.UNKNOWN_REALM, "outcome", IngestMetrics.OUTCOME_UNAUTHORIZED).counter().count() == 1.0d
    }

    def "counts cache fallbacks per realm"() {
        when:
        metrics.countFallback("master")

        then:
        registry.get(IngestMetrics.FALLBACK_METER_NAME).tags("realm", "master").counter().count() == 1.0d
    }

    def "cache size gauge reflects the supplier value"() {
        when:
        metrics.registerCacheGauge("master", ({ -> 7 } as Supplier<Number>))

        then:
        registry.get(IngestMetrics.CACHE_SIZE_METER_NAME).tags("realm", "master").gauge().value() == 7.0d
    }

    def "null registry is a complete no-op"() {
        given:
        def disabled = IngestMetrics.create(null)

        when:
        disabled.countRequest("master", IngestMetrics.OUTCOME_ACCEPTED)
        disabled.countFallback("master")
        disabled.registerCacheGauge("master", ({ -> 1 } as Supplier<Number>))

        then:
        noExceptionThrown()
        registry.find(IngestMetrics.REQUESTS_METER_NAME).counter() == null
    }
}
