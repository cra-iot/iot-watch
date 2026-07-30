# HTTP ingest endpoint

Receives device messages pushed by the CRA IoT Platform HTTP egress
(`rest-sender`), as an alternative to the MQTT egress → MQTT agent path.
Pilot feature.

## Endpoint

`POST https://<hostname>/api/{realm}/ingest`

- Header `X-API-Key: <key>` — per-realm key, see configuration below.
- Body: the platform integration envelope (`data` is a JSON-encoded string).
- Responses: `200` accepted, `400` unparseable payload or missing `EUI`,
  `401` wrong/missing key, `404` no asset with matching `devEui` in the
  realm, `409` more than one asset with that `devEui`.

The parsed inner message is written to the matched asset's `rawValue`
attribute (event timestamp = inner `ts`, device time). Groovy rules in the
Manager UI decode the hex `data` field into typed attributes. Note that
OpenRemote discards events older than the attribute's last-updated
timestamp, so late-arriving messages older than the newest stored one are
dropped (the endpoint still returns `200` — dispatch is asynchronous).

## Asset requirements

The target asset must have its `devEui` attribute set to the device EUI
(case does not matter). All IoT Watch asset types declare `devEui` as a
required attribute; instances created before this feature must have the
value filled in manually in the Manager UI. The attribute exists on every
new asset instance, but its value must be filled in; an asset with an empty
devEui is never matched.

## Configuration

Keys live in the `OR_IOTWATCH_INGEST_KEYS` env var of the manager pod
(Kubernetes Secret in the Helm repository, never in this repository):

    OR_IOTWATCH_INGEST_KEYS=realm1:key1,realm2:key2

Generate a key with `openssl rand -hex 32`. Rotation = update the Secret
and restart the manager pod. If the variable is not set, the endpoint is
not registered (404).

**Warning:** do not enable `OR_WEBSERVER_DUMP_REQUESTS` on an environment
serving this endpoint — Undertow request dumping would write the `X-API-Key`
header to logs.

On the platform side, configure a customer endpoint (Datový tok →
HTTP endpoint) with the URL above and the `X-API-Key` header.

## Metrics

With `OR_METRICS_ENABLED=true` the manager's Prometheus scrape server
(`OR_METRICS_PORT`, default 8405, run by the built-in HealthService)
additionally exposes:

- `or_iotwatch_ingest_requests_total{realm,outcome}` — one increment per
  request; `outcome` is `accepted`, `unauthorized`, `bad_request`,
  `unknown_device` or `conflict`. Unauthorized requests for realms that
  have no configured key are bucketed as `realm="unknown"`.
- `or_iotwatch_ingest_cache_fallback_total{realm}` — devEui cache misses
  that fell back to a database query; a rising rate under steady traffic
  means persistence events are being missed.
- `or_iotwatch_ingest_cache_size{realm}` — devEuis currently cached per
  configured realm.

Enabling `OR_METRICS_ENABLED`/`OR_METRICS_PORT` and the Prometheus scrape
configuration is deployment-side work in the Helm repository.

## Manual verification (local dev stack)

    curl -i -X POST "http://localhost:8080/api/master/ingest" \
      -H "Content-Type: application/json" \
      -H "X-API-Key: <key from OR_IOTWATCH_INGEST_KEYS>" \
      -d '{
        "data": "{\"cmd\":\"gw\",\"ts\":1773397633008,\"data\":\"cbe006e001c10106aa7fff\",\"bat\":254,\"EUI\":\"00112233AABBCCDD\"}",
        "tags": [],
        "tech": "L",
        "type": "D"
      }'

Expect `200 OK` and the `rawValue` attribute of the asset whose `devEui` is
`00112233AABBCCDD` updated in the Manager UI.
