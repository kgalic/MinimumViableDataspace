package org.eclipse.edc.opcua.edr;

import java.util.Optional;

public interface EdrStore {
    void save(EdrEntry entry);

    Optional<EdrEntry> findByTransferProcessId(String transferProcessId);

    Optional<EdrEntry> findByAssetId(String assetId);

    Optional<EdrEntry> findByContractAgreementId(String contractAgreementId);
}