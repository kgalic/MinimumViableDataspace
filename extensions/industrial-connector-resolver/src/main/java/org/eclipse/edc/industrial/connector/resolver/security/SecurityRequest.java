package org.eclipse.edc.industrial.connector.resolver.security;

/**
 * Generic request describing a security provisioning operation.
 */
public class SecurityRequest {

    private String transferId;

    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }
}