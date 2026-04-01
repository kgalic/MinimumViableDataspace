package org.eclipse.edc.industrial.connector.resolver.dataflow;

import org.eclipse.edc.common.spi.config.IndustrialConnectorResolverConfigService;
import org.eclipse.edc.connector.controlplane.transfer.spi.flow.DataFlowController;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.DataFlowResponse;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferProcess;
import org.eclipse.edc.industrial.connector.resolver.datatypes.IndustrialConnectorDataTypes;
import org.eclipse.edc.industrial.connector.resolver.pki.PkiCertificateService;
import org.eclipse.edc.industrial.connector.resolver.security.SecurityService;
import org.eclipse.edc.industrial.connector.resolver.security.mosquitto.MosquittoCredentials;
import org.eclipse.edc.industrial.connector.resolver.security.mosquitto.MosquittoSecurityRequest;
import org.eclipse.edc.spi.response.ResponseStatus;
import org.eclipse.edc.spi.response.StatusResult;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public class IndustrialConnectorDataFlow implements DataFlowController {
    private static final String EDC_NAMESPACE = "https://w3id.org/edc/v0.0.1/ns/";

    private final PkiCertificateService certificateService;
    private final IndustrialConnectorResolverConfigService configService;
    private final IndustrialConnectorDataTypes industrialConnectorDataTypes;
    private final SecurityService securityService;

    public IndustrialConnectorDataFlow(PkiCertificateService pkiCertificateService,
                                       IndustrialConnectorDataTypes industrialConnectorDataTypes,
                                       IndustrialConnectorResolverConfigService configService,
                                       SecurityService securityService) {
        this.certificateService = pkiCertificateService;
        this.configService = configService;
        this.industrialConnectorDataTypes = industrialConnectorDataTypes;
        this.securityService = securityService;
    }

    @Override
    public boolean canHandle(TransferProcess transferProcess) {
        var contentDataAddress = transferProcess.getContentDataAddress();
        if (contentDataAddress == null || industrialConnectorDataTypes == null) {
            return false;
        }

        // Handle defined data types only
        return industrialConnectorDataTypes.isSupported(contentDataAddress.getType());
    }

    @Override
    public org.eclipse.edc.spi.response.StatusResult<Void> suspend(TransferProcess transferProcess) {
        return null;
    }

    @Override
    public org.eclipse.edc.spi.response.StatusResult<Void> terminate(TransferProcess transferProcess) {
        return null;
    }

    @Override
    public Set<String> transferTypesFor(org.eclipse.edc.connector.controlplane.asset.spi.domain.Asset asset) {
        return Set.of();
    }

    @Override
    public org.eclipse.edc.spi.response.@NotNull StatusResult<DataFlowResponse> start(TransferProcess transferProcess, org.eclipse.edc.policy.model.Policy policy) {
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

    private StatusResult<DataFlowResponse> handlePushTransfer(TransferProcess transferProcess) {
        // Get the assetId from the transfer process
        String assetId = transferProcess.getAssetId();
        if (assetId == null) {
            return StatusResult.failure(ResponseStatus.FATAL_ERROR, "No asset ID available for push transfer");
        }

        var transferId = transferProcess.getId();
        var transferType = transferProcess.getTransferType();

        var config = configService.getConfig();
        var isCertificateBasedAuthentication = config.getCertBasedAuthenticationEnabled();

        // Create user and permissions using Mosquitto Dynamic Security
        // Topic pattern for this asset (allow wildcard subscriptions)
        String topicPattern = assetId + "/#";

        if (isCertificateBasedAuthentication && this.certificateService != null) {
            var csr = transferProcess.getDataDestination().getStringProperty("csr");

            if (csr == null) {
                return StatusResult.failure(ResponseStatus.FATAL_ERROR, "No CSR provided for certificate-based authentication");
            }

            var username = certificateService.getCommonName(csr).getContent();

            if (username == null) {
                return StatusResult.failure(ResponseStatus.FATAL_ERROR, "Failed to extract username from CSR");
            }

            // Use the injected SecurityService to provision access
            var securityRequest = new MosquittoSecurityRequest();
            securityRequest.setUserName(username);
            securityRequest.setTopic(topicPattern);
            securityRequest.setTransferId(transferId);
            Result<MosquittoCredentials> credentialsResult = securityService.provisionAccess(securityRequest);
            var credentials = credentialsResult.getContent();
            var signedCertificate = certificateService.requestCertificate(csr, credentials.getUsername(), 365);
            var caChain = certificateService.getCertificateChain();
            var dataAddress = DataAddress.Builder.newInstance()
                    .type(transferType)
                    .property(EDC_NAMESPACE + "endpoint", config.getSinkServiceUrl())
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
                    .type(transferType)
                    .property(EDC_NAMESPACE + "endpoint", config.getSinkServiceUrl())
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
}
