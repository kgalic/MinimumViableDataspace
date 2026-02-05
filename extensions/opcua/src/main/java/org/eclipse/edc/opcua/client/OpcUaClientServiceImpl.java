package org.eclipse.edc.opcua.client;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.identity.AnonymousProvider;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;

public class OpcUaClientServiceImpl implements OpcUaClientService {

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
                        .setApplicationName(LocalizedText.english("EDC OPC UA Client"))
                        .setApplicationUri("urn:edc:opcua:client")
                        .setIdentityProvider(new AnonymousProvider())
                        .setRequestTimeout(UInteger.valueOf(15000))
                        .build()
        );

        client.connect().get();

        try {
            var dataValue = client.readValue(0, TimestampsToReturn.Both, node).get();
            return dataValue.getValue().getValue();
        } finally {
            client.disconnect().get();
        }
    }
}