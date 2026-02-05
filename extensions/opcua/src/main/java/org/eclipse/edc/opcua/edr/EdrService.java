package org.eclipse.edc.opcua.edr;

import org.eclipse.edc.spi.types.domain.DataAddress;

public interface EdrService {

    void storeEdr(String transferId, DataAddress opcUaAddress, String authToken);

    DataAddress getOpcUaAddress(String transferId);

    String getAuthToken(String transferId);

    void removeEdr(String transferId);
}
