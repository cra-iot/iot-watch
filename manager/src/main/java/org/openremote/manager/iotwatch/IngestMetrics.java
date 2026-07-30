package org.openremote.manager.iotwatch;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.function.Supplier;

/**
 * Micrometer meters for the HTTP ingest endpoint, published through the
 * manager's Prometheus registry (OR_METRICS_ENABLED) and scraped from the
 * built-in HealthService metrics server (OR_METRICS_PORT). When metrics are
 * disabled the registry is null and every method is a no-op, so callers stay
 * branch-free. Labels are bounded: realm (one per customer, unknown realms
 * collapse into "unknown") and outcome — never per-device values.
 */
public class IngestMetrics {

    public static final String REQUESTS_METER_NAME = "or.iotwatch.ingest.requests";
    public static final String FALLBACK_METER_NAME = "or.iotwatch.ingest.cache.fallback";
    public static final String CACHE_SIZE_METER_NAME = "or.iotwatch.ingest.cache.size";

    public static final String OUTCOME_ACCEPTED = "accepted";
    public static final String OUTCOME_UNAUTHORIZED = "unauthorized";
    public static final String OUTCOME_BAD_REQUEST = "bad_request";
    public static final String OUTCOME_UNKNOWN_DEVICE = "unknown_device";
    public static final String OUTCOME_CONFLICT = "conflict";

    public static final String UNKNOWN_REALM = "unknown";

    protected final MeterRegistry registry;

    protected IngestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public static IngestMetrics create(MeterRegistry registryOrNull) {
        return new IngestMetrics(registryOrNull);
    }

    public void countRequest(String realmOrNull, String outcome) {
        if (registry == null) {
            return;
        }
        registry.counter(REQUESTS_METER_NAME,
            "realm", realmOrNull != null ? realmOrNull : UNKNOWN_REALM,
            "outcome", outcome).increment();
    }

    public void countFallback(String realm) {
        if (registry == null) {
            return;
        }
        registry.counter(FALLBACK_METER_NAME, "realm", realm).increment();
    }

    public void registerCacheGauge(String realm, Supplier<Number> sizeSupplier) {
        if (registry == null) {
            return;
        }
        // strongReference: Micrometer holds suppliers weakly by default and a
        // collected supplier silently freezes the gauge at NaN
        Gauge.builder(CACHE_SIZE_METER_NAME, sizeSupplier)
            .tag("realm", realm)
            .strongReference(true)
            .register(registry);
    }
}
