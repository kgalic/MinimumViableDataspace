package org.eclipse.edc.example.integration;

import org.eclipse.edc.industrial.wss.IndustrialWebSocketService;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;

/**
 * Example extension showing how to use the Industrial WebSocket Service
 * from other extensions, specifically for OPC-UA via MQTT integration.
 */
public class ExampleWebSocketClientExtension implements ServiceExtension {

    @Inject
    private Monitor monitor;

    @Inject(required = false)
    private IndustrialWebSocketService webSocketService;

    @Override
    public String name() {
        return "Example WebSocket Client Extension";
    }

    @Override
    public void start() {
        if (webSocketService != null) {
            monitor.info("WebSocket service available. Active clients: " + webSocketService.getActiveSessionCount());
            
            // Example: Broadcast an OPC-UA read command to all connected clients
            sendOpcUaReadCommand();
        } else {
            monitor.warning("WebSocket service not available - extension will run without WebSocket integration");
        }
    }

    private void sendOpcUaReadCommand() {
        // Example OPC-UA read command structure
        String opcUaCommand = """
                {
                    "type": "opcua_read_request",
                    "transferId": "transfer-123",
                    "opcuaServer": "opc.tcp://10.0.1.100:4840",
                    "nodeIds": ["ns=2;s=Temperature", "ns=2;s=Pressure"],
                    "mqttBroker": "tcp://mqtt.example.com:1883", 
                    "mqttTopic": "opcua/factory-01/data",
                    "mqttCredentials": {
                        "username": "client-123",
                        "password": "generated-token"
                    },
                    "pushInterval": 5000,
                    "timestamp": "2026-03-23T10:00:00Z"
                }
                """;

        webSocketService.broadcast(opcUaCommand);
        monitor.info("OPC-UA read command broadcasted to all WebSocket clients");
    }

    /**
     * Example method for sending commands to specific clients
     */
    public void sendOpcUaCommandToClient(String clientId, String serverUrl, String[] nodeIds, 
                                         String mqttTopic, String mqttUsername, String mqttPassword) {
        if (webSocketService == null || !webSocketService.hasSession(clientId)) {
            monitor.warning("Cannot send command - client " + clientId + " not connected");
            return;
        }

        String nodeIdsJson = String.join("\",\"", nodeIds);
        String command = String.format("""
                {
                    "type": "opcua_read_request",
                    "transferId": "transfer-%s",
                    "opcuaServer": "%s",
                    "nodeIds": ["%s"],
                    "mqttBroker": "tcp://mqtt.example.com:1883",
                    "mqttTopic": "%s", 
                    "mqttCredentials": {
                        "username": "%s",
                        "password": "%s"
                    },
                    "pushInterval": 5000,
                    "timestamp": "%s"
                }
                """, 
                System.currentTimeMillis(), serverUrl, nodeIdsJson, mqttTopic, 
                mqttUsername, mqttPassword, java.time.Instant.now().toString());

        boolean sent = webSocketService.sendToClient(clientId, command);
        if (sent) {
            monitor.info("OPC-UA command sent to client: " + clientId);
        } else {
            monitor.warning("Failed to send OPC-UA command to client: " + clientId);
        }
    }
}
