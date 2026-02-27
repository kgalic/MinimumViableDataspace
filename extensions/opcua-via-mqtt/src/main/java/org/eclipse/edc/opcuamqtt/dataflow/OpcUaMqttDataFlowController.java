package org.eclipse.edc.opcuamqtt.dataflow;

import org.eclipse.edc.connector.controlplane.asset.spi.domain.Asset;
import org.eclipse.edc.connector.controlplane.transfer.spi.flow.DataFlowController;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.DataFlowResponse;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferProcess;
import org.eclipse.edc.opcuamqtt.edr.MqttEdrService;
import org.eclipse.edc.opcuamqtt.mqttpush.MqttBrokerConfig;
import org.eclipse.edc.opcuamqtt.mqttpush.OpcUaMqttPushService;
import org.eclipse.edc.opcuamqtt.security.MosquittoCredentials;
import org.eclipse.edc.opcuamqtt.security.MosquittoSecurityService;
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
    private static final String EDC_NAMESPACE = "https://w3id.org/edc/v0.0.1/ns/";
    private final OpcUaMqttPushService opcUaPushService;
    private final MqttBrokerConfig brokerConfig;
    private final MqttEdrService edrService;
    private final MosquittoSecurityService securityService;
    private final Monitor monitor;

    public OpcUaMqttDataFlowController(OpcUaMqttPushService opcUaPushService,
                                      MqttBrokerConfig brokerConfig,
                                      MqttEdrService edrService,
                                      MosquittoSecurityService securityService,
                                      Monitor monitor) {
        this.opcUaPushService = opcUaPushService;
        this.brokerConfig = brokerConfig;
        this.edrService = edrService;
        this.securityService = securityService;
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

        boolean canHandle = isOpcUaMqttSource && (transferType == null || "MQTT-PUSH".equalsIgnoreCase(transferType));
        monitor.debug(() -> "Can handle transfer " + transferProcess.getId() + ": " + canHandle);
        return canHandle;
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

        // Clean up Mosquitto user and role
        String username = "edc-user-" + transferId;
        String roleName = "edc-role-" + transferId;
        var cleanupResult = securityService.removeUserAndRole(username, roleName);
        if (cleanupResult.failed()) {
            monitor.warning("Failed to clean up Mosquitto user and role: " + cleanupResult.getFailureDetail());
        } else {
            monitor.info("Cleaned up Mosquitto user " + username + " and role " + roleName);
        }

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

        // Get broker URL from configuration or use default
        String brokerUrl = brokerConfig != null && brokerConfig.isConfigured()
                ? brokerConfig.getBrokerUrl()
                : "tcp://localhost:1883";

        // Create user and permissions using Mosquitto Dynamic Security
        // Topic pattern for this asset (allow wildcard subscriptions)
        String topicPattern = assetId + "/#";

        monitor.info("Creating Mosquitto user with permissions for topic: " + topicPattern);
        var credentialsResult = securityService.createUserWithPermissions(topicPattern, transferId);

        if (credentialsResult.failed()) {
            return StatusResult.failure(ResponseStatus.FATAL_ERROR, 
                    "Failed to create Mosquitto user and permissions: " + credentialsResult.getFailureDetail());
        }

        MosquittoCredentials credentials = credentialsResult.getContent();
        monitor.info("Created Mosquitto user: " + credentials.getUsername() + " with role: " + credentials.getRoleName());

        // Start pushing OPC UA data to MQTT topic (topic = assetId)
        opcUaPushService.startPushing(
                transferId,
                assetId,
                transferProcess.getContentDataAddress()
        );

        // Generate auth token for EDR access
        String authToken = UUID.randomUUID().toString();

        monitor.info("Stored MQTT EDR for transfer " + transferId +
                    " - Topic: " + assetId + ", Broker: " + brokerUrl);

        // Return success response with MQTT broker details for EDR
        // The DataFlowResponse contains the DataAddress that will be returned to consumer
        var dataAddress = DataAddress.Builder.newInstance()
                .type(OPCUAMQTT_TYPE)
                .property(EDC_NAMESPACE + "endpoint", brokerUrl)
                .property(EDC_NAMESPACE + "authToken", authToken)
                .property(EDC_NAMESPACE + "topic", assetId)
                .property(EDC_NAMESPACE + "username", credentials.getUsername())
                .property(EDC_NAMESPACE + "password", credentials.getPassword())
                .build();

        // Store the complete DataAddress in EDR
        edrService.storeEdr(transferId, dataAddress);

        var response = DataFlowResponse.Builder.newInstance()
                .dataAddress(dataAddress)
                .build();

        return StatusResult.success(response);
    }
}

