package org.eclipse.edc.opcua.push;

import org.eclipse.edc.spi.types.domain.DataAddress;

public interface OpcUaPushService {

    void startPushing(String transferId, DataAddress opcUaSource, String consumerUrl, String authToken, String method);

    void stopPushing(String transferId);

    boolean isActive(String transferId);
}