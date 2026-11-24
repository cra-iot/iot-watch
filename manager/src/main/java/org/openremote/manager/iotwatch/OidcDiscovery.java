package org.openremote.manager.iotwatch;

import com.fasterxml.jackson.databind.JsonNode;
import org.openremote.model.util.ValueUtil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The endpoints of an OpenID Connect provider, taken from its discovery document
 * ({@code <issuer>/.well-known/openid-configuration}), as the Keycloak identity provider needs them.
 */
public record OidcDiscovery(String issuer, String authorizationUrl, String tokenUrl, String jwksUrl,
                            String userInfoUrl, String endSessionUrl) {

    public static final String WELL_KNOWN_PATH = "/.well-known/openid-configuration";

    public static OidcDiscovery parse(String json) {
        JsonNode node;
        try {
            node = ValueUtil.JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("Discovery document is not JSON: " + e.getMessage(), e);
        }
        return new OidcDiscovery(
            required(node, "issuer"),
            required(node, "authorization_endpoint"),
            required(node, "token_endpoint"),
            required(node, "jwks_uri"),
            optional(node, "userinfo_endpoint"),
            optional(node, "end_session_endpoint"));
    }

    public static OidcDiscovery fetch(String issuer) throws IOException, InterruptedException {
        URI url = URI.create(issuer + WELL_KNOWN_PATH);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return parse(response.body());
    }

    private static String required(JsonNode node, String field) {
        String value = optional(node, field);
        if (value == null) {
            throw new IllegalArgumentException("Discovery document lacks '" + field + "'");
        }
        return value;
    }

    private static String optional(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
    }
}
