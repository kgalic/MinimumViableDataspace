package org.eclipse.edc.opcua.edr;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryEdrStore implements EdrStore {
    private final Map<String, EdrEntry> byTransferProcessId = new ConcurrentHashMap<>();
    private final Map<String, String> transferByAssetId = new ConcurrentHashMap<>();
    private final Map<String, String> transferByAgreementId = new ConcurrentHashMap<>();

    @Override
    public void save(EdrEntry entry) {
        byTransferProcessId.put(entry.transferProcessId(), entry);
        if (entry.assetId() != null) {
            transferByAssetId.put(entry.assetId(), entry.transferProcessId());
        }
        if (entry.contractAgreementId() != null) {
            transferByAgreementId.put(entry.contractAgreementId(), entry.transferProcessId());
        }
    }

    @Override
    public Optional<EdrEntry> findByTransferProcessId(String transferProcessId) {
        return Optional.ofNullable(byTransferProcessId.get(transferProcessId));
    }

    @Override
    public Optional<EdrEntry> findByAssetId(String assetId) {
        var tpid = transferByAssetId.get(assetId);
        return tpid == null ? Optional.empty() : findByTransferProcessId(tpid);
    }

    @Override
    public Optional<EdrEntry> findByContractAgreementId(String contractAgreementId) {
        var tpid = transferByAgreementId.get(contractAgreementId);
        return tpid == null ? Optional.empty() : findByTransferProcessId(tpid);
    }
}
