package org.openremote.model.iotwatch;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Receives device messages pushed by the CRA IoT Platform HTTP egress on
 * {@code POST /api/{realm}/ingest}. Authenticated with a per-realm API key in
 * the {@code X-API-Key} header (no OAuth2), see the design spec
 * docs/superpowers/specs/2026-07-27-http-ingest-api-key-endpoint-design.md.
 */
@Path("ingest")
public interface IngestResource {

    String API_KEY_HEADER = "X-API-Key";

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response ingest(@HeaderParam(API_KEY_HEADER) String apiKey, IngestEnvelope envelope);
}
