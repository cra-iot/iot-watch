package org.openremote.model.iotwatch;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Fleet search and overview, served at {@code POST /api/{realm}/fleet/query}.
 * Exists because the stock AssetQuery cannot express free-text OR across name and attributes,
 * and cannot filter, sort or page by attribute timestamps - see the design spec.
 */
@Path("fleet")
public interface FleetResource {

    @POST
    @Path("query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"read:assets"})
    FleetQueryResult query(FleetQuery query);

    /** The export dialog's options for the devices the search matches; paging and grouping are ignored. */
    @POST
    @Path("export/options")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"read:assets"})
    FleetExportOptions exportOptions(FleetQuery query);

    /** Daily values of the matched devices: one CSV for a single type, a ZIP of CSVs for several. */
    @POST
    @Path("export")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({"text/csv", "application/zip"})
    @RolesAllowed({"read:assets"})
    Response export(FleetExportRequest request);
}
