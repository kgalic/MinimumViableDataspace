package org.eclipse.edc.industrial.connector.resolver.security.mosquitto;

import org.eclipse.edc.common.spi.mqttclient.MqttClient;
import org.eclipse.edc.common.spi.mqttclient.PahoMqttClientImpl;
import org.eclipse.edc.industrial.connector.resolver.config.IndustrialConnectorResolverConfig;
import org.eclipse.edc.industrial.connector.resolver.security.SecurityService;
import org.eclipse.edc.industrial.connector.resolver.security.SecurityServiceProvisionerService;
import org.eclipse.edc.spi.monitor.Monitor;

/**
 * Provisioner service for Mosquitto-based security.
 *
 * Based on the configuration, this service creates either a certificate-based or
 * credential-based MQTT client and sets up the security service for dynamic user
 * and role provisioning via the Mosquitto Dynamic Security plugin.
 */
public class MosquittoSecurityProvisionerServiceImpl implements SecurityServiceProvisionerService<MosquittoCredentials, MosquittoSecurityRequest> {

    private final SecurityService<MosquittoCredentials, MosquittoSecurityRequest> securityService;

    /**
     * Creates a MosquittoSecurityProvisionerServiceImpl that instantiates the appropriate
     * MQTT client based on configuration (certificate-based or credential-based auth).
     *
     * @param monitor for logging
     * @param config the industrial connector resolver configuration
     */
    public MosquittoSecurityProvisionerServiceImpl(Monitor monitor, IndustrialConnectorResolverConfig config) {
        MqttClient mqttClient = createMqttClient(monitor, config);
        this.securityService = new MqttSecurityServiceImpl(mqttClient, monitor);
    }

    /**
     * Creates the appropriate MQTT client based on the configuration.
     * If certificate-based authentication is enabled, creates a cert-based client.
     * Otherwise, creates a credential-based client with admin credentials.
     *
     * @param monitor for logging
     * @param config the configuration containing auth details
     * @return an MQTT client configured with either certificates or credentials
     */
    private static MqttClient createMqttClient(Monitor monitor, IndustrialConnectorResolverConfig config) {
        String brokerUrl = config.getSinkServiceUrl();

        // Check if certificate-based authentication is enabled
        if (config.getCertBasedAuthenticationEnabled() != null && config.getCertBasedAuthenticationEnabled()) {
            // Certificate-based authentication
            String caCertPath = config.getSinkServiceCaChainCertificatePath();
            String clientCertPath = config.getSinkServiceAdminCertificatePath();
            String clientKeyPath = config.getSinkServiceAdminCertificateKeyPath();

            monitor.info("Creating MQTT client with certificate-based authentication");
            monitor.debug("CA Cert: " + caCertPath + ", Client Cert: " + clientCertPath + ", Client Key: " + clientKeyPath);

            return new PahoMqttClientImpl(
                    monitor,
                    brokerUrl,
                    caCertPath,
                    clientCertPath,
                    clientKeyPath
            );
        } else {
            // Credential-based authentication with admin username/password
            String adminUsername = config.getSinkServiceAdminUsername();
            String adminPassword = config.getSinkServiceAdminPassword();

            monitor.info("Creating MQTT client with credential-based authentication (admin user)");
            monitor.debug("Admin Username: " + adminUsername);

            return new PahoMqttClientImpl(
                    monitor,
                    brokerUrl,
                    adminUsername,
                    adminPassword
            );
        }
    }

    @Override
    public SecurityService<MosquittoCredentials, MosquittoSecurityRequest> getSecurityService() {
        return securityService;
    }
}
