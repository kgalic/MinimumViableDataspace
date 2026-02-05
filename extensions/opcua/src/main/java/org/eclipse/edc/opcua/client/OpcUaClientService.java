package org.eclipse.edc.opcua.client;

public interface OpcUaClientService {
    Object readValue(String endpoint, String nodeId) throws Exception;
}