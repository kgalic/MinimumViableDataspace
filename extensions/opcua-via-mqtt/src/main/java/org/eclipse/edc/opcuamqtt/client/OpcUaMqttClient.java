package org.eclipse.edc.opcuamqtt.client;

/**
 * Minimal MQTT client abstraction used by the OpcUaMqttPushServiceImpl to publish messages.
 * Implementations are responsible for handling connection lifecycle internally.
 */
public interface OpcUaMqttClient {

    /**
     * Publishes the given payload to the specified topic on the given broker URL.
     *
     * @param brokerUrl the MQTT broker URL (e.g., tcp://localhost:1883)
     * @param topic the MQTT topic to publish to
     * @param payload the payload bytes to send
     * @param username optional username for broker auth (may be null)
     * @param password optional password/token for broker auth (may be null)
     * @throws Exception if publishing fails
     */
    void publish(String brokerUrl, String topic, byte[] payload, String username, String password) throws Exception;
}
