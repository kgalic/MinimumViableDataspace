package org.eclipse.edc.opcuamqtt.mqttpush;

/**
 * Configuration for the provider-managed MQTT broker.
 * Retrieved from connector environment variables.
 */
public class MqttBrokerConfig {

    private final String brokerUrl;
    private final String username;
    private final String password;

    public MqttBrokerConfig(String brokerUrl, String username, String password) {
        this.brokerUrl = brokerUrl;
        this.username = username;
        this.password = password;
    }

    public String getBrokerUrl() {
        return brokerUrl;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public boolean isConfigured() {
        return brokerUrl != null && !brokerUrl.trim().isEmpty();
    }

    @Override
    public String toString() {
        return "MqttBrokerConfig{" +
                "brokerUrl='" + brokerUrl + '\'' +
                ", username='" + (username != null ? "***" : "null") + '\'' +
                ", password='" + (password != null ? "***" : "null") + '\'' +
                '}';
    }
}

