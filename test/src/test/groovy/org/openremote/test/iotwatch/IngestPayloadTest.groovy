package org.openremote.test.iotwatch

import org.openremote.manager.iotwatch.IngestPayload
import org.openremote.model.iotwatch.IngestEnvelope
import spock.lang.Specification

class IngestPayloadTest extends Specification {

    static final String INNER =
        '{"cmd":"gw","ts":1773397633008,"data":"cbe006e001c10106aa7fff","fcnt":2900,"port":2,' +
        '"gws":[{"rssi":-100,"snr":9.25,"gweui":"1122334455667788","ant":0}],"bat":254,"EUI":"00112233aabbccdd"}'

    def "parses EUI (uppercased), device timestamp and full message"() {
        when:
        def payload = IngestPayload.parse(new IngestEnvelope("D", INNER, "L", []))

        then:
        payload.eui == "00112233AABBCCDD"
        payload.timestamp == 1773397633008L
        payload.message.get("cmd") == "gw"
        payload.message.get("data") == "cbe006e001c10106aa7fff"
        payload.message.get("bat") == 254
    }

    def "missing ts yields null timestamp"() {
        when:
        def payload = IngestPayload.parse(new IngestEnvelope("D", '{"EUI":"00112233AABBCCDD"}', "L", []))

        then:
        payload.timestamp == null
    }

    def "invalid payloads are rejected"() {
        when:
        IngestPayload.parse(envelope)

        then:
        thrown(IngestPayload.InvalidPayloadException)

        where:
        envelope << [
            null,
            new IngestEnvelope("D", null, "L", []),
            new IngestEnvelope("D", "not json", "L", []),
            new IngestEnvelope("D", '"just a string"', "L", []),
            new IngestEnvelope("D", '{"cmd":"gw"}', "L", []),          // EUI missing
            new IngestEnvelope("D", '{"EUI":""}', "L", []),            // EUI blank
            new IngestEnvelope("D", '{"EUI":123}', "L", [])            // EUI not textual
        ]
    }
}
