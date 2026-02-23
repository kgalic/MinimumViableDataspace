package org.eclipse.edc.opcuamqtt.edr;

import org.eclipse.edc.spi.types.domain.DataAddress;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of MqttEdrService.
 * Stores MQTT EDR entries as a cache for active transfers.
 */
public class InMemoryMqttEdrService implements MqttEdrService {

    private final Map<String, MqttEdrEntry> entries = new ConcurrentHashMap<>();

    @Override
    public void storeEdr(String transferId, DataAddress dataAddress) {
        var entry = new MqttEdrEntry(
                transferId,
                (String) dataAddress.getProperty("topic"),  // assetId is stored as topic
                null,  // contractAgreementId can be set later if needed
                (String) dataAddress.getProperty("brokerUrl"),
                (String) dataAddress.getProperty("topic"),
                (String) dataAddress.getProperty("username"),
                (String) dataAddress.getProperty("password"),
                (String) dataAddress.getProperty("authToken"),
                Instant.now()
        );
        entries.put(transferId, entry);
    }

    @Override
    public String getBrokerUrl(String transferId) {
        var entry = entries.get(transferId);
        return entry != null ? entry.brokerUrl() : null;
    }

    @Override
    public String getTopic(String transferId) {
        var entry = entries.get(transferId);
        return entry != null ? entry.topic() : null;
    }

    @Override
    public String getUsername(String transferId) {
        var entry = entries.get(transferId);
        return entry != null ? entry.username() : null;
    }

    @Override
    public String getPassword(String transferId) {
        var entry = entries.get(transferId);
        return entry != null ? entry.password() : null;
    }

    @Override
    public String getAuthToken(String transferId) {
        var entry = entries.get(transferId);
        return entry != null ? entry.authToken() : null;
    }

    @Override
    public MqttEdrEntry getEdrEntry(String transferId) {
        return entries.get(transferId);
    }

    @Override
    public void removeEdr(String transferId) {
        entries.remove(transferId);
    }

    @Override
    public boolean exists(String transferId) {
        return entries.containsKey(transferId);
    }
}

