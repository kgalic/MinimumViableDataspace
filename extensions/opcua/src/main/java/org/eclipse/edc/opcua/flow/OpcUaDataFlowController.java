
package org.eclipse.edc.opcua.flow;

import org.eclipse.edc.connector.controlplane.asset.spi.domain.Asset;
import org.eclipse.edc.connector.controlplane.transfer.spi.flow.DataFlowController;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.DataFlowResponse;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferProcess;
import org.eclipse.edc.opcua.edr.EdrService;
import org.eclipse.edc.opcua.push.OpcUaPushService;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.response.ResponseStatus;
import org.eclipse.edc.spi.response.StatusResult;
import org.eclipse.edc.spi.types.domain.DataAddress;

import java.util.Set;
import java.util.UUID;

public class OpcUaDataFlowController implements DataFlowController {

    private static final String OPCUA_TYPE = "opcua";
    private static final String EDC_NAMESPACE = "https://w3id.org/edc/v0.0.1/ns/";

    private final String publicEndpointBase;
    private final EdrService edrService;
    private final OpcUaPushService opcUaPushService;

    public OpcUaDataFlowController(String publicEndpointBase, EdrService edrService, OpcUaPushService opcUaPushService) {
        this.publicEndpointBase = publicEndpointBase;
        this.edrService = edrService;
        this.opcUaPushService = opcUaPushService;
    }

    @Override
    public boolean canHandle(TransferProcess transferProcess) {
        var contentDataAddress = transferProcess.getContentDataAddress();
        if (contentDataAddress == null) {
            return false;
        }

        // Handle both PULL and PUSH scenarios for OPC UA
        boolean isOpcUaSource = OPCUA_TYPE.equalsIgnoreCase(contentDataAddress.getType());
        String transferType = transferProcess.getTransferType();

        return isOpcUaSource && (transferType == null ||
                "HttpData-PULL".equalsIgnoreCase(transferType) ||
                "HttpData-PUSH".equalsIgnoreCase(transferType));
    }

    @Override
    public StatusResult<DataFlowResponse> start(TransferProcess transferProcess, Policy policy) {
        var contentDataAddress = transferProcess.getContentDataAddress();
        if (contentDataAddress == null) {
            return StatusResult.failure(ResponseStatus.FATAL_ERROR, "No content data address available");
        }

        String transferType = transferProcess.getTransferType();

        if ("HttpData-PUSH".equalsIgnoreCase(transferType)) {
            return handlePushTransfer(transferProcess);
        } else {
            return handlePullTransfer(transferProcess);
        }
    }

    private StatusResult<DataFlowResponse> handlePushTransfer(TransferProcess transferProcess) {
        var dataDestination = transferProcess.getDataDestination();
        if (dataDestination == null) {
            return StatusResult.failure(ResponseStatus.FATAL_ERROR, "No data destination for push transfer");
        }

        String consumerUrl = dataDestination.getStringProperty("baseUrl");
        String authToken = dataDestination.getStringProperty("token");
        String method = dataDestination.getStringProperty("method");

        if (consumerUrl == null) {
            return StatusResult.failure(ResponseStatus.FATAL_ERROR, "Consumer baseUrl not specified");
        }

        opcUaPushService.startPushing(
                transferProcess.getId(),
                transferProcess.getContentDataAddress(),
                consumerUrl,
                authToken,
                method != null ? method : "POST"
        );

        var response = DataFlowResponse.Builder.newInstance()
                .dataAddress(DataAddress.Builder.newInstance()
                        .type("opcua-push")
                        .property("status", "active")
                        .build())
                .build();

        return StatusResult.success(response);
    }

    private StatusResult<DataFlowResponse> handlePullTransfer(TransferProcess transferProcess) {
        var transferId = transferProcess.getId();
        var authToken = UUID.randomUUID().toString();

        edrService.storeEdr(transferId, transferProcess.getContentDataAddress(), authToken);

        var dataAddress = DataAddress.Builder.newInstance()
                .type(OPCUA_TYPE)
                .property(EDC_NAMESPACE + "endpoint", publicEndpointBase + "/" + transferId + "/data")
                .property(EDC_NAMESPACE + "authorization", authToken)
                .property("endpoint", publicEndpointBase + "/" + transferId + "/data")
                .property("authorization", authToken)
                .build();

        var response = DataFlowResponse.Builder.newInstance()
                .dataAddress(dataAddress)
                .build();

        return StatusResult.success(response);
    }

    @Override
    public StatusResult<Void> terminate(TransferProcess transferProcess) {
        String transferType = transferProcess.getTransferType();

        if ("HttpData-PUSH".equalsIgnoreCase(transferType)) {
            opcUaPushService.stopPushing(transferProcess.getId());
        } else {
            edrService.removeEdr(transferProcess.getId());
        }

        return StatusResult.success();
    }

    @Override
    public StatusResult<Void> suspend(TransferProcess transferProcess) {
        return StatusResult.success();
    }

    @Override
    public Set<String> transferTypesFor(Asset asset) {
        return Set.of(OPCUA_TYPE);
    }
}