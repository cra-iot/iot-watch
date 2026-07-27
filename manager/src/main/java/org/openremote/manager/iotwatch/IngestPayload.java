package org.openremote.manager.iotwatch;

import com.fasterxml.jackson.databind.JsonNode;
import org.openremote.model.iotwatch.IngestEnvelope;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.value.ValueType;

import java.util.Locale;

/**
 * Parsed inner message of the platform integration envelope. The envelope's
 * "data" field is a JSON-encoded string containing the device message with
 * "EUI" (device identifier) and optional "ts" (device time, epoch millis).
 */
public class IngestPayload {

    public static class InvalidPayloadException extends Exception {
        public InvalidPayloadException(String message) {
            super(message);
        }
    }

    protected final String eui;
    protected final Long timestamp;
    protected final ValueType.ObjectMap message;

    protected IngestPayload(String eui, Long timestamp, ValueType.ObjectMap message) {
        this.eui = eui;
        this.timestamp = timestamp;
        this.message = message;
    }

    public static IngestPayload parse(IngestEnvelope envelope) throws InvalidPayloadException {
        if (envelope == null || envelope.getData() == null) {
            throw new InvalidPayloadException("Envelope or its data field is missing");
        }
        JsonNode node;
        try {
            node = ValueUtil.JSON.readTree(envelope.getData());
        } catch (Exception e) {
            throw new InvalidPayloadException("Envelope data is not valid JSON");
        }
        if (node == null || !node.isObject()) {
            throw new InvalidPayloadException("Envelope data is not a JSON object");
        }
        JsonNode euiNode = node.get("EUI");
        if (euiNode == null || !euiNode.isTextual() || euiNode.asText().isBlank()) {
            throw new InvalidPayloadException("EUI is missing from the message");
        }
        Long timestamp = node.hasNonNull("ts") && node.get("ts").isNumber() ? node.get("ts").asLong() : null;
        ValueType.ObjectMap message = ValueUtil.JSON.convertValue(node, ValueType.ObjectMap.class);
        return new IngestPayload(euiNode.asText().trim().toUpperCase(Locale.ROOT), timestamp, message);
    }

    public String getEui() {
        return eui;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public ValueType.ObjectMap getMessage() {
        return message;
    }
}
