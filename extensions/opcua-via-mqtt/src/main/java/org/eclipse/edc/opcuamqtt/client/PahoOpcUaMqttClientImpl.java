package org.eclipse.edc.opcuamqtt.client;

import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.util.concurrent.ConcurrentHashMap;

/**
 * MQTT client implementation using Eclipse Paho MQTT v3.
 * Manages connection lifecycle internally with connection pooling.
 */
public class PahoOpcUaMqttClientImpl implements OpcUaMqttClient {

    private final Monitor monitor;
    private final ConcurrentHashMap<String, MqttClient> clientCache = new ConcurrentHashMap<>();

    public PahoOpcUaMqttClientImpl(Monitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void publish(String brokerUrl, String topic, byte[] payload, String username, String password) throws Exception {
        if (brokerUrl == null || brokerUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Broker URL must not be null/blank");
        }
        if (topic == null || topic.trim().isEmpty()) {
            throw new IllegalArgumentException("Topic must not be null/blank");
        }
        if (payload == null) {
            throw new IllegalArgumentException("Payload must not be null");
        }

        MqttClient client = getOrCreateClient(brokerUrl, username, password);

        try {
            client.publish(topic, payload, 1, false);
            monitor.debug("Published to MQTT broker " + brokerUrl + " on topic '" + topic + "'");
        } catch (MqttException e) {
            monitor.severe("Failed to publish to MQTT broker " + brokerUrl + " on topic '" + topic + "'", e);
            throw e;
        }
    }

    /**
     * Gets or creates an MQTT client for the given broker URL, reusing connections.
     */
    private MqttClient getOrCreateClient(String brokerUrl, String username, String password) throws MqttException {
        return clientCache.computeIfAbsent(brokerUrl, url -> {
            try {
                String clientId = "edc-opcua-mqtt-" + System.nanoTime();
                MqttClient client = new MqttClient(url, clientId);

                MqttConnectOptions options = new MqttConnectOptions();
                options.setCleanSession(true);
                options.setAutomaticReconnect(true);

                if (username != null && !username.trim().isEmpty()) {
                    options.setUserName(username);
                }
                if (password != null && !password.trim().isEmpty()) {
                    options.setPassword(password.toCharArray());
                }

                client.connect(options);
                monitor.info("Connected to MQTT broker at " + url);
                return client;
            } catch (MqttException e) {
                monitor.severe("Failed to connect to MQTT broker at " + url, e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Closes all cached MQTT connections. Should be called during shutdown.
     */
    public void close() {
        for (var entry : clientCache.entrySet()) {
            try {
                if (entry.getValue().isConnected()) {
                    entry.getValue().disconnect();
                }
                entry.getValue().close();
                monitor.info("Disconnected from MQTT broker: " + entry.getKey());
            } catch (MqttException e) {
                monitor.warning("Error disconnecting from MQTT broker " + entry.getKey(), e);
            }
        }
        clientCache.clear();
    }
}

