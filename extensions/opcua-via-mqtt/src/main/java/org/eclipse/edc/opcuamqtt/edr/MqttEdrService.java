package org.eclipse.edc.opcuamqtt.edr;

/**
 * Service interface for managing MQTT Endpoint Data References (EDR).
 * Acts as a cache for active transfers with MQTT connection details.
 */
public interface MqttEdrService {

    /**
     * Store MQTT connection details for a transfer.
     *
     * @param transferId     Transfer process ID
     * @param assetId        Asset ID
     * @param brokerUrl      MQTT broker URL
     * @param topic          MQTT topic (usually = assetId)
     * @param username       Optional MQTT username
     * @param password       Optional MQTT password
     * @param authToken      Authorization token for accessing this EDR
     */
    void storeEdr(String transferId, String assetId, String brokerUrl, String topic,
                  String username, String password, String authToken);

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

