package org.eclipse.edc.industrial.connector.resolver.security;

public interface SecurityServiceProvisionerService<T extends Credentials, R extends SecurityRequest> {
    SecurityService<T, R> getSecurityService();
}
