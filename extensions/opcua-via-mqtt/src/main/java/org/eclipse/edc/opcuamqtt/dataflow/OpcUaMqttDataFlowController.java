package org.eclipse.edc.opcuamqtt.dataflow;

import org.eclipse.edc.connector.controlplane.asset.spi.domain.Asset;
import org.eclipse.edc.connector.controlplane.transfer.spi.flow.DataFlowController;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.DataFlowResponse;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferProcess;
import org.eclipse.edc.industrial.wss.IndustrialWebSocketService;
import org.eclipse.edc.opcuamqtt.mqttpush.MqttBrokerConfig;
import org.eclipse.edc.opcuamqtt.mqttpush.OpcUaMqttPushService;
import org.eclipse.edc.opcuamqtt.pki.PkiCertificateService;
import org.eclipse.edc.opcuamqtt.security.SecurityService;
import org.eclipse.edc.opcuamqtt.security.mqtt.MosquittoCredentials;
import org.eclipse.edc.opcuamqtt.security.mqtt.MosquittoSecurityRequest;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.response.ResponseStatus;
import org.eclipse.edc.spi.response.StatusResult;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;

public class OpcUaMqttDataFlowController implements DataFlowController {

    private static final String OPCUAMQTT_TYPE = "opcuamqtt";
    private static final String EDC_NAMESPACE = "https://w3id.org/edc/v0.0.1/ns/";
    private final OpcUaMqttPushService opcUaPushService;
    private final MqttBrokerConfig brokerConfig;
    private final SecurityService securityService;
    private final PkiCertificateService pkiCertificateService;
    private final Monitor monitor;
    private IndustrialWebSocketService webSocketService;

    public OpcUaMqttDataFlowController(OpcUaMqttPushService opcUaPushService,
                                      MqttBrokerConfig brokerConfig,
                                      SecurityService securityService,
                                      Monitor monitor) {
        this.opcUaPushService = opcUaPushService;
        this.brokerConfig = brokerConfig;
        this.securityService = securityService;
        this.monitor = monitor;
        this.pkiCertificateService = null;
        this.webSocketService = null;
    }

    public OpcUaMqttDataFlowController(OpcUaMqttPushService opcUaPushService,
                                       PkiCertificateService pkiCertificateService,
                                       MqttBrokerConfig brokerConfig,
                                       SecurityService securityService,
                                       Monitor monitor) {
        this.opcUaPushService = opcUaPushService;
        this.brokerConfig = brokerConfig;
        this.securityService = securityService;
        this.pkiCertificateService = pkiCertificateService;
        this.webSocketService = null;
        this.monitor = monitor;
    }

    public IndustrialWebSocketService setWebSocketService(IndustrialWebSocketService webSocketService) {
        this.webSocketService = webSocketService;
        return webSocketService;
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
        monitor.info("Cleaning up Mosquitto user " + username + " and role " + roleName);
        var revokeRequest = new MosquittoSecurityRequest();
        revokeRequest.setUserName(username);
        revokeRequest.setRoleName(roleName);
        revokeRequest.setTopic(transferProcess.getAssetId());
        var cleanupResult = securityService.revokeAccess(revokeRequest);
        if (cleanupResult.failed()) {
            monitor.warning("Failed to clean up Mosquitto user and role: " + cleanupResult.getFailureDetail());
        } else {
            monitor.info("Cleaned up Mosquitto user " + username + " and role " + roleName);
        }

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

        var isCertificateBasedAuthentication = brokerConfig.isCertificateAuth();

        // Get broker URL from configuration or use default
        String brokerUrl = brokerConfig != null && brokerConfig.isConfigured()
                ? brokerConfig.getBrokerUrl()
                : "tcp://localhost:1883";

        // Create user and permissions using Mosquitto Dynamic Security
        // Topic pattern for this asset (allow wildcard subscriptions)
        String topicPattern = assetId + "/#";

        // Check if WebSocket service is available for client-side execution
        if (webSocketService != null && webSocketService.getActiveSessionCount() > 0) {
            monitor.info("WebSocket service available with " + webSocketService.getActiveSessionCount() + " clients - sending OPC-UA read command");

            // Send WebSocket command instead of direct OPC-UA connection
            String opcUaCommand = buildOpcUaReadCommand(transferId, transferProcess.getContentDataAddress(), assetId, brokerUrl);
            webSocketService.broadcast(opcUaCommand);
            monitor.info("Sent OPC-UA read command to all WebSocket clients for transfer: " + transferId);
        } else {
            monitor.info("No WebSocket clients available - using direct OPC-UA connection");
            // Fallback to direct OPC UA data pushing
            opcUaPushService.startPushing(
                    transferId,
                    assetId,
                    transferProcess.getContentDataAddress()
            );
        }

        // Generate auth token for EDR access
        String authToken = UUID.randomUUID().toString();

        monitor.info("Stored MQTT EDR for transfer " + transferId +
                " - Topic: " + assetId + ", Broker: " + brokerUrl);

        if (isCertificateBasedAuthentication && pkiCertificateService != null) {
            var csr = transferProcess.getDataDestination().getStringProperty("csr");

            if (csr == null) {
                return StatusResult.failure(ResponseStatus.FATAL_ERROR, "No CSR provided for certificate-based authentication");
            }

            var username = pkiCertificateService.getCommonName(csr).getContent();

            if (username == null) {
                return StatusResult.failure(ResponseStatus.FATAL_ERROR, "Failed to extract username from CSR");
            }

            var securityRequest = new MosquittoSecurityRequest();
            securityRequest.setUserName(username);
            securityRequest.setTopic(topicPattern);
            securityRequest.setTransferId(transferId);
            Result<MosquittoCredentials> credentialsResult = securityService.provisionAccess(securityRequest);
            var credentials = credentialsResult.getContent();
            var signedCertificate = pkiCertificateService.requestCertificate(csr, credentials.getUsername(), 365);
            var caChain = pkiCertificateService.getCertificateChain();
            var dataAddress = DataAddress.Builder.newInstance()
                    .type(OPCUAMQTT_TYPE)
                    .property(EDC_NAMESPACE + "endpoint", brokerUrl)
                    .property(EDC_NAMESPACE + "authToken", authToken)
                    .property(EDC_NAMESPACE + "topic", assetId)
                    .property(EDC_NAMESPACE + "username", credentials.getUsername())
                    .property(EDC_NAMESPACE + "certificate", signedCertificate.getContent())
                    .property(EDC_NAMESPACE + "ca-chain", caChain.getContent())
                    .build();

            var response = DataFlowResponse.Builder.newInstance()
                    .dataAddress(dataAddress)
                    .build();

            return StatusResult.success(response);

        } else {
            var securityRequest = new MosquittoSecurityRequest();
            securityRequest.setTopic(topicPattern);
            securityRequest.setTransferId(transferId);
            Result<MosquittoCredentials> credentialsResult = securityService.provisionAccess(securityRequest);
            var credentials = credentialsResult.getContent();

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

            var response = DataFlowResponse.Builder.newInstance()
                    .dataAddress(dataAddress)
                    .build();

            return StatusResult.success(response);
        }

    }

    /**
     * Build OPC-UA read command JSON for WebSocket clients
     */
    private String buildOpcUaReadCommand(String transferId, DataAddress contentDataAddress, String assetId, String brokerUrl) {
        String serverUrl = firstNonBlank(
                contentDataAddress.getStringProperty("serverUrl"),
                contentDataAddress.getStringProperty(EDC_NAMESPACE + "serverUrl")
        );

        String nodeIdSpec = firstNonBlank(
                contentDataAddress.getStringProperty("nodeId"),
                contentDataAddress.getStringProperty(EDC_NAMESPACE + "nodeId"),
                contentDataAddress.getStringProperty("nodeIds"),
                contentDataAddress.getStringProperty(EDC_NAMESPACE + "nodeIds")
        );
        String pushInterval = getDataAddressProperty(contentDataAddress, "pushInterval", "5000");

        return String.format(
                "{\"type\":\"opcua_read_request\",\"transferId\":\"%s\",\"opcuaServer\":\"%s\",\"nodeIds\":[\"%s\"],\"mqttBroker\":\"%s\",\"mqttTopic\":\"%s\",\"pushInterval\":%s,\"timestamp\":\"%s\"}",
                transferId, serverUrl, nodeIdSpec, brokerUrl, assetId, pushInterval, java.time.Instant.now().toString()
        );
    }

    /**
     * Helper method to get property from DataAddress with fallback
     */
    private String getDataAddressProperty(DataAddress dataAddress, String key, String defaultValue) {
        String value = dataAddress.getStringProperty(key);
        if (value == null) {
            value = dataAddress.getStringProperty(EDC_NAMESPACE + key);
        }
        return value != null ? value : defaultValue;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (var v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }
        return null;
    }
}
