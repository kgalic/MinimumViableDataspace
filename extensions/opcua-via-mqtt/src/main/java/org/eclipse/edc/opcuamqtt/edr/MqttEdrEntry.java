package org.eclipse.edc.opcuamqtt.edr;

import java.time.Instant;

/**
 * Represents an MQTT Endpoint Data Reference (EDR) entry.
 * Stores MQTT connection details for a specific transfer process.
 */
public record MqttEdrEntry(
        String transferProcessId,
        String assetId,
        String contractAgreementId,
        String brokerUrl,              // MQTT broker endpoint
        String topic,                  // MQTT topic (typically = assetId)
        String username,               // Optional MQTT username
        String password,               // Optional MQTT password
        String authToken,              // Authorization token for EDR endpoint access
        Instant createdAt
) { }

