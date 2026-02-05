package org.eclipse.edc.opcua.edr;

import org.eclipse.edc.spi.types.domain.DataAddress;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryEdrService implements EdrService {

    private final Map<String, DataAddress> opcUaAddresses = new ConcurrentHashMap<>();
    private final Map<String, String> authTokens = new ConcurrentHashMap<>();

    @Override
    public void storeEdr(String transferId, DataAddress opcUaAddress, String authToken) {
        opcUaAddresses.put(transferId, opcUaAddress);
        authTokens.put(transferId, authToken);
    }

    @Override
    public DataAddress getOpcUaAddress(String transferId) {
        return opcUaAddresses.get(transferId);
    }

    @Override
    public String getAuthToken(String transferId) {
        return authTokens.get(transferId);
    }

    @Override
    public void removeEdr(String transferId) {
        opcUaAddresses.remove(transferId);
        authTokens.remove(transferId);
    }
}
