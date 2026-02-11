package org.eclipse.edc.opcuamqtt.edr;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.spi.monitor.Monitor;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API controller for MQTT Endpoint Data References (EDR).
 * Provides endpoints for consumers to retrieve MQTT broker connection details and credentials.
 *
 * The EDR serves as a cache of currently active MQTT push operations, indexed by transfer ID.
 * Once a consumer has a valid transfer ID and auth token, they can query this endpoint to get:
 * - MQTT broker URL (endpoint)
 * - MQTT topic (usually = assetId)
 * - Optional MQTT credentials (username/password)
 */
@Path("/api/management/v3/edrs")
public class MqttEdrApiController {

    private final MqttEdrService edrService;
    private final Monitor monitor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MqttEdrApiController(MqttEdrService edrService, Monitor monitor) {
        this.edrService = edrService;
        this.monitor = monitor;
    }

    /**
     * Retrieve MQTT connection metadata for a transfer.
     * The consumer uses this to connect to the MQTT broker and subscribe to the asset topic.
     * Endpoint: GET /api/management/v3/edrs/{transferId}/mqtt
     *
     * @param transferId Transfer process ID
     * @param authHeader Authorization header with token (must match stored auth token)
     * @return Response with MQTT broker details
     */
    @GET
    @Path("/{transferId}/mqtt")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMqttMetadata(
            @PathParam("transferId") String transferId,
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authHeader) {

        // Validate authorization
        var expectedToken = edrService.getAuthToken(transferId);
        if (expectedToken == null) {
            monitor.warning("EDR request for unknown transfer: " + transferId);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Transfer not found\"}")
                    .build();
        }

        if (authHeader == null || !authHeader.equals("Bearer " + expectedToken)) {
            monitor.warning("Unauthorized EDR request for transfer: " + transferId);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\": \"Invalid authorization token\"}")
                    .build();
        }

        try {
            // Retrieve MQTT connection details from cache
            var brokerUrl = edrService.getBrokerUrl(transferId);
            var topic = edrService.getTopic(transferId);
            var username = edrService.getUsername(transferId);
            var password = edrService.getPassword(transferId);

            if (brokerUrl == null || topic == null) {
                monitor.warning("Incomplete MQTT EDR for transfer: " + transferId);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\": \"Missing MQTT connection details\"}")
                        .build();
            }

            // Build response with MQTT metadata
            Map<String, Object> response = new HashMap<>();
            response.put("endpoint", brokerUrl);
            response.put("topic", topic);
            response.put("brokerUrl", brokerUrl);

            if (username != null) {
                response.put("username", username);
            }
            if (password != null) {
                response.put("password", password);
            }

            response.put("pushActive", true);
            response.put("transferId", transferId);

            monitor.debug("Serving MQTT EDR for transfer: " + transferId + ", topic: " + topic);
            return Response.ok(objectMapper.writeValueAsString(response))
                    .build();

        } catch (Exception e) {
            monitor.warning("Failed to retrieve MQTT EDR for transfer: " + transferId, e);
            return Response.serverError()
                    .entity("{\"error\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * Alternative endpoint path for compatibility with standard EDR endpoints.
     * Returns the same MQTT metadata in a standard format.
     * Endpoint: GET /api/management/v3/edrs/{transferId}/dataaddress
     */
    @GET
    @Path("/{transferId}/dataaddress")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDataAddress(
            @PathParam("transferId") String transferId,
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authHeader) {

        // Reuse the MQTT metadata endpoint
        return getMqttMetadata(transferId, authHeader);
    }
}

