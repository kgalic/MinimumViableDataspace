package org.eclipse.edc.opcuamqtt.opcua;

import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.identity.AnonymousProvider;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;

/**
 * OPC UA client implementation for reading values from OPC UA servers.
 * Uses Eclipse Milo SDK and is completely independent of other OPC UA extensions.
 */
public class OpcUaClientImpl implements MqttOpcUaClient {

    private final Monitor monitor;

    public OpcUaClientImpl(Monitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public Object readValue(String endpoint, String nodeId) throws Exception {
        var endpointUrl = endpoint == null ? null : endpoint.trim();
        if (endpointUrl == null || endpointUrl.isEmpty()) {
            throw new IllegalArgumentException("OPC UA endpoint must not be null/blank");
        }

        var node = NodeId.parse(nodeId.trim());

        var client = OpcUaClient.create(
                endpointUrl,
                endpoints -> endpoints.stream().findFirst(),
                configBuilder -> configBuilder
                        .setApplicationName(LocalizedText.english("EDC OPC UA MQTT Client"))
                        .setApplicationUri("urn:edc:opcuamqtt:client")
                        .setIdentityProvider(new AnonymousProvider())
                        .setRequestTimeout(UInteger.valueOf(15000))
                        .build()
        );

        try {
            client.connect().get();
            var dataValue = client.readValue(0, TimestampsToReturn.Both, node).get();
            return dataValue.getValue().getValue();
        } finally {
            try {
                client.disconnect().get();
            } catch (Exception e) {
                monitor.warning("Error disconnecting OPC UA client from " + endpoint, e);
            }
        }
    }
}

