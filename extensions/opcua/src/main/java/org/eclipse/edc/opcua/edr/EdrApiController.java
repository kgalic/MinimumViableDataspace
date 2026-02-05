package org.eclipse.edc.opcua.edr;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.opcua.client.OpcUaClientService;
import org.eclipse.edc.spi.monitor.Monitor;

@Path("/edr")
public class EdrApiController {

    private static final String EDC_NAMESPACE = "https://w3id.org/edc/v0.0.1/ns/";

    private final EdrService edrService;
    private final OpcUaClientService opcUaClientService;
    private final Monitor monitor;

    public EdrApiController(EdrService edrService, OpcUaClientService opcUaClientService, Monitor monitor) {
        this.edrService = edrService;
        this.opcUaClientService = opcUaClientService;
        this.monitor = monitor;
    }

    @GET
    @Path("/{transferId}/data")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getData(
            @PathParam("transferId") String transferId,
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authHeader) {

        // Validate authorization
        var expectedToken = edrService.getAuthToken(transferId);
        if (expectedToken == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Transfer not found\"}")
                    .build();
        }

        if (authHeader == null || !authHeader.equals(expectedToken)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\": \"Invalid authorization\"}")
                    .build();
        }

        // Get stored OPC UA connection details
        var opcUaAddress = edrService.getOpcUaAddress(transferId);
        if (opcUaAddress == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"OPC UA address not found\"}")
                    .build();
        }

        try {
            var endpoint = firstNonBlank(
                    opcUaAddress.getStringProperty("serverUrl"),
                    opcUaAddress.getStringProperty(EDC_NAMESPACE + "serverUrl")
            );
            var nodeId = firstNonBlank(
                    opcUaAddress.getStringProperty("nodeId"),
                    opcUaAddress.getStringProperty(EDC_NAMESPACE + "nodeId")
            );

            if (isBlank(endpoint) || isBlank(nodeId)) {
                monitor.warning("Missing OPC UA properties for transfer " + transferId +
                        " (endpoint=" + endpoint + ", nodeId=" + nodeId + ")");
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\": \"Missing OPC UA endpoint and/or nodeId in stored data address\"}")
                        .build();
            }

            monitor.debug("Reading OPC UA data from endpoint: " + endpoint + ", nodeId: " + nodeId);

            var value = opcUaClientService.readValue(endpoint, nodeId);

            return Response.ok("{\"value\": \"" + value + "\", \"nodeId\": \"" + nodeId + "\"}")
                    .build();
        } catch (Exception e) {
            monitor.warning("Failed to read OPC UA data for transfer " + transferId, e);
            return Response.serverError()
                    .entity("{\"error\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    private static String firstNonBlank(String a, String b) {
        return isBlank(a) ? b : a;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}