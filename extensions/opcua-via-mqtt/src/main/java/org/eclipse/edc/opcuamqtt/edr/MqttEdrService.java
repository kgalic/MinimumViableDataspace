package org.eclipse.edc.opcuamqtt.edr;

import org.eclipse.edc.spi.types.domain.DataAddress;

/**
 * Service interface for managing MQTT Endpoint Data References (EDR).
 * Acts as a cache for active transfers with MQTT connection details.
 */
public interface MqttEdrService {
    /**
     * Store EDR entry using a complete DataAddress.
     *
     * @param transferId  Transfer process ID
     * @param dataAddress Complete DataAddress with all properties
     */
    void storeEdr(String transferId, DataAddress dataAddress);

    /**
     * Retrieve MQTT broker URL for a transfer.
     */
    String getBrokerUrl(String transferId);

    /**
     * Retrieve MQTT topic for a transfer.
     */
    String getTopic(String transferId);

    /**
     * Retrieve MQTT username for a transfer (may be null).
     */
    String getUsername(String transferId);

    /**
     * Retrieve MQTT password for a transfer (may be null).
     */
    String getPassword(String transferId);

    /**
     * Retrieve authorization token for a transfer.
     */
    String getAuthToken(String transferId);

    /**
     * Retrieve the full EDR entry for a transfer.
     */
    MqttEdrEntry getEdrEntry(String transferId);

    /**
     * Remove EDR entry when transfer is complete or terminated.
     */
    void removeEdr(String transferId);

    /**
     * Check if an EDR entry exists for a transfer.
     */
    boolean exists(String transferId);
}

