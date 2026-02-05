package org.eclipse.edc.opcua.edr;

import java.time.Instant;
import java.util.Map;

public record EdrEntry(
        String transferProcessId,
        String assetId,
        String contractAgreementId,
        String endpoint,
        Map<String, String> auth,
        Instant createdAt
) { }