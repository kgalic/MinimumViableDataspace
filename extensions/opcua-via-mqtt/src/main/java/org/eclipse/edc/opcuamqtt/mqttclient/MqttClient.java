
package org.eclipse.edc.opcuamqtt.mqttclient;

import org.eclipse.paho.client.mqttv3.MqttCallback;

/**
 * MQTT client abstraction used for both publishing and subscribing to MQTT messages.
 * Implementations are responsible for handling connection lifecycle internally.
 */
public interface MqttClient {

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

    /**
     * Publishes the given payload to the specified topic using stored credentials.
     *
     * @param brokerUrl the MQTT broker URL (e.g., tcp://localhost:1883)
     * @param topic the MQTT topic to publish to
     * @param payload the payload bytes to send
     * @throws Exception if publishing fails
     */
    void publish(String brokerUrl, String topic, byte[] payload) throws Exception;

    /**
     * Subscribes to the specified topic on the given broker URL.
     *
     * @param brokerUrl the MQTT broker URL (e.g., tcp://localhost:1883)
     * @param topic the MQTT topic to subscribe to
     * @param username optional username for broker auth (may be null)
     * @param password optional password/token for broker auth (may be null)
     * @throws Exception if subscription fails
     */
    void subscribe(String brokerUrl, String topic, String username, String password) throws Exception;

    /**
     * Subscribes to the specified topic using stored credentials.
     *
     * @param brokerUrl the MQTT broker URL (e.g., tcp://localhost:1883)
     * @param topic the MQTT topic to subscribe to
     * @throws Exception if subscription fails
     */
    void subscribe(String brokerUrl, String topic) throws Exception;

    /**
     * Sets the callback handler for MQTT events on the specified broker connection.
     *
     * @param brokerUrl the MQTT broker URL to set callback for
     * @param callback the MQTT callback handler
     * @param username optional username for broker auth (may be null)
     * @param password optional password/token for broker auth (may be null)
     * @throws Exception if setting callback fails
     */
    void setCallback(String brokerUrl, MqttCallback callback, String username, String password) throws Exception;

    /**
     * Sets the callback handler for MQTT events using stored credentials.
     *
     * @param brokerUrl the MQTT broker URL to set callback for
     * @param callback the MQTT callback handler
     * @throws Exception if setting callback fails
     */
    void setCallback(String brokerUrl, MqttCallback callback) throws Exception;

    /**
     * Disconnects and closes the connection to the specified broker.
     *
     * @param brokerUrl the MQTT broker URL to disconnect from
     * @throws Exception if disconnection fails
     */
    void disconnect(String brokerUrl) throws Exception;
}