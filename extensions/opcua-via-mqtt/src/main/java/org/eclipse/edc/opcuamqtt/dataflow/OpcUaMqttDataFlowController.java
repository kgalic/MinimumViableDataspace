package org.eclipse.edc.opcuamqtt.dataflow;

import org.eclipse.edc.connector.controlplane.asset.spi.domain.Asset;
import org.eclipse.edc.connector.controlplane.transfer.spi.flow.DataFlowController;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.DataFlowResponse;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferProcess;
import org.eclipse.edc.opcuamqtt.edr.MqttEdrService;
import org.eclipse.edc.opcuamqtt.mqttpush.MqttBrokerConfig;
import org.eclipse.edc.opcuamqtt.mqttpush.OpcUaMqttPushService;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.response.ResponseStatus;
import org.eclipse.edc.spi.response.StatusResult;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;

public class OpcUaMqttDataFlowController implements DataFlowController {

    private static final String OPCUAMQTT_TYPE = "opcuamqtt";
    private final OpcUaMqttPushService opcUaPushService;
    private final MqttBrokerConfig brokerConfig;
    private final MqttEdrService edrService;
    private final Monitor monitor;

    public OpcUaMqttDataFlowController(OpcUaMqttPushService opcUaPushService,
                                      MqttBrokerConfig brokerConfig,
                                      MqttEdrService edrService,
                                      Monitor monitor) {
        this.opcUaPushService = opcUaPushService;
        this.brokerConfig = brokerConfig;
        this.edrService = edrService;
        this.monitor = monitor;
    }

    @Override
    public boolean canHandle(@NotNull TransferProcess transferProcess) {
        var contentDataAddress = transferProcess.getContentDataAddress();
        if (contentDataAddress == null) {
            return false;
        }

        // Handle MQTT-PUSH transfers for OPC UA MQTT data source
        boolean isOpcUaMqttSource = OPCUAMQTT_TYPE.equalsIgnoreCase(contentDataAddress.getType());
        String transferType = transferProcess.getTransferType();

        return isOpcUaMqttSource && (transferType == null || "MQTT-PUSH".equalsIgnoreCase(transferType));
    }

    @Override
    @NotNull
    public StatusResult<DataFlowResponse> start(@NotNull TransferProcess transferProcess, @NotNull Policy policy) {
        var contentDataAddress = transferProcess.getContentDataAddress();
        if (contentDataAddress == null) {
            return StatusResult.failure(ResponseStatus.FATAL_ERROR, "No content data address available");
        }

        String transferType = transferProcess.getTransferType();

        if ("MQTT-PUSH".equalsIgnoreCase(transferType)) {
            return handlePushTransfer(transferProcess);
        } else {
            return StatusResult.failure(ResponseStatus.FATAL_ERROR, "Unsupported transfer type: " + transferType);
        }
    }

    @Override
    public StatusResult<Void> suspend(@NotNull TransferProcess transferProcess) {
        return StatusResult.success();
    }

    @Override
    public StatusResult<Void> terminate(@NotNull TransferProcess transferProcess) {
        String transferId = transferProcess.getId();
        opcUaPushService.stopPushing(transferId);
        // Clean up EDR entry when transfer is terminated
        edrService.removeEdr(transferId);
        monitor.info("Removed MQTT EDR for transfer " + transferId);
        return StatusResult.success();
    }

    @Override
    public Set<String> transferTypesFor(@NotNull Asset asset) {
        return Set.of(OPCUAMQTT_TYPE);
    }

    /**
     * Handle MQTT-PUSH transfer by starting the push service and returning EDR with MQTT broker details.
     */
    private StatusResult<DataFlowResponse> handlePushTransfer(TransferProcess transferProcess) {
        // Get the assetId from the transfer process
        String assetId = transferProcess.getAssetId();
        if (assetId == null) {
            return StatusResult.failure(ResponseStatus.FATAL_ERROR, "No asset ID available for push transfer");
        }

        String transferId = transferProcess.getId();

        // Start pushing OPC UA data to MQTT topic (topic = assetId)
        opcUaPushService.startPushing(
                transferId,
                assetId,
                transferProcess.getContentDataAddress()
        );

        // Get broker URL from configuration or use default
        String brokerUrl = brokerConfig != null && brokerConfig.isConfigured()
                ? brokerConfig.getBrokerUrl()
                : "tcp://localhost:1883";

        // Generate auth token for EDR access
        String authToken = UUID.randomUUID().toString();

        // Store MQTT connection details in EDR cache
        // This allows consumers to retrieve broker details via EDR endpoint
        String mqttUsername = brokerConfig != null ? brokerConfig.getUsername() : null;
        String mqttPassword = brokerConfig != null ? brokerConfig.getPassword() : null;

        edrService.storeEdr(transferId, assetId, brokerUrl, assetId,
                           mqttUsername, mqttPassword, authToken);

        monitor.info("Stored MQTT EDR for transfer " + transferId +
                    " - Topic: " + assetId + ", Broker: " + brokerUrl);

        // Return success response with MQTT broker details for EDR
        // The DataFlowResponse contains the DataAddress that will be returned to consumer
        var response = DataFlowResponse.Builder.newInstance()
                .dataAddress(DataAddress.Builder.newInstance()
                        .type("MQTT")
                        .property("endpoint", brokerUrl)           // MQTT broker endpoint
                        .property("topic", assetId)                // Topic = assetId (singleton pattern)
                        .property("brokerUrl", brokerUrl)          // Alternative property name
                        .property("pushActive", "true")
                        .property("status", "active")
                        .property("authToken", authToken)          // Auth token for EDR endpoint
                        .property("transferId", transferId)        // Transfer ID for EDR lookup
                        .build())
                .build();

        return StatusResult.success(response);
    }
}

