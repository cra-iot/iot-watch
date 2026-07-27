package org.openremote.model.iotwatch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * CRA IoT Platform integration envelope as delivered by the platform HTTP
 * egress (rest-sender). The {@code data} field is a JSON-encoded string (not a
 * nested object) containing the device message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class IngestEnvelope {

    protected String type;
    protected String data;
    protected String tech;
    protected List<String> tags;

    protected IngestEnvelope() {
    }

    public IngestEnvelope(String type, String data, String tech, List<String> tags) {
        this.type = type;
        this.data = data;
        this.tech = tech;
        this.tags = tags;
    }

    public String getType() {
        return type;
    }

    public String getData() {
        return data;
    }

    public String getTech() {
        return tech;
    }

    public List<String> getTags() {
        return tags;
    }
}
