
package org.eclipse.edc.opcua.push;

import org.eclipse.edc.opcua.client.OpcUaClientService;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.types.domain.DataAddress;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class OpcUaPushServiceImpl implements OpcUaPushService {

    private static final String EDC_NAMESPACE = "https://w3id.org/edc/v0.0.1/ns/";
    private final ConcurrentHashMap<String, ScheduledFuture<?>> activePushTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);
    private final HttpClient httpClient;

    private final OpcUaClientService opcUaClientService;
    private final Monitor monitor;

    public OpcUaPushServiceImpl(OpcUaClientService opcUaClientService, Monitor monitor) {
        this.opcUaClientService = opcUaClientService;
        this.monitor = monitor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void startPushing(String transferId, DataAddress opcUaSource, String consumerUrl, String authToken, String method) {
        if (activePushTasks.containsKey(transferId)) {
            monitor.warning("Push task already active for transfer: " + transferId);
            return;
        }

        String serverUrl = firstNonBlank(
                opcUaSource.getStringProperty("serverUrl"),
                opcUaSource.getStringProperty(EDC_NAMESPACE + "serverUrl")
        );
        String nodeId = firstNonBlank(
                opcUaSource.getStringProperty("nodeId"),
                opcUaSource.getStringProperty(EDC_NAMESPACE + "nodeId")
        );

        long intervalMs = Long.parseLong(opcUaSource.getStringProperty("pushInterval", "5000"));

        monitor.info("Starting OPC UA push for transfer " + transferId +
                " (server: " + serverUrl + ", node: " + nodeId +
                ", interval: " + intervalMs + "ms, target: " + consumerUrl + ")");

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                Object value = opcUaClientService.readValue(serverUrl, nodeId);

                String jsonPayload = String.format(
                        "{\"nodeId\":\"%s\",\"value\":\"%s\",\"timestamp\":\"%s\",\"transferId\":\"%s\"}",
                        nodeId, String.valueOf(value), java.time.Instant.now().toString(), transferId
                );

                // Build standard Java HttpRequest
                var requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(consumerUrl))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30));

                if (authToken != null) {
                    requestBuilder.header("Authorization", "Bearer " + authToken);
                }

                HttpRequest request;
                if ("POST".equalsIgnoreCase(method)) {
                    request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build();
                } else {
                    request = requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(jsonPayload)).build();
                }

                // Send request using standard HttpClient
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    monitor.debug("Successfully pushed OPC UA data for transfer " + transferId +
                            " (status: " + response.statusCode() + ")");
                } else {
                    monitor.warning("Failed to push OPC UA data for transfer " + transferId +
                            " (status: " + response.statusCode() + ", body: " + response.body() + ")");
                }

            } catch (Exception e) {
                monitor.severe("Failed to push OPC UA data for transfer " + transferId, e);
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);

        activePushTasks.put(transferId, task);
    }

    @Override
    public void stopPushing(String transferId) {
        var task = activePushTasks.remove(transferId);
        if (task != null) {
            task.cancel(true);
            monitor.info("Stopped OPC UA push for transfer: " + transferId);
        }
    }

    @Override
    public boolean isActive(String transferId) {
        return activePushTasks.containsKey(transferId);
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.trim().isEmpty()) ? a : b;
    }
}