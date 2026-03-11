package org.eclipse.edc.opcuamqtt;

import org.eclipse.edc.connector.controlplane.transfer.spi.flow.DataFlowManager;
import org.eclipse.edc.opcuamqtt.client.OpcUaMqttClient;
import org.eclipse.edc.opcuamqtt.client.PahoOpcUaMqttClientImpl;
import org.eclipse.edc.opcuamqtt.dataflow.OpcUaMqttDataFlowController;
import org.eclipse.edc.opcuamqtt.edr.InMemoryMqttEdrService;
import org.eclipse.edc.opcuamqtt.edr.MqttEdrApiController;
import org.eclipse.edc.opcuamqtt.edr.MqttEdrService;
import org.eclipse.edc.opcuamqtt.mqttpush.MqttBrokerConfig;
import org.eclipse.edc.opcuamqtt.mqttpush.OpcUaMqttPushService;
import org.eclipse.edc.opcuamqtt.mqttpush.OpcUaMqttPushServiceImpl;
import org.eclipse.edc.opcuamqtt.opcua.MqttOpcUaClient;
import org.eclipse.edc.opcuamqtt.opcua.OpcUaClientImpl;
import org.eclipse.edc.opcuamqtt.pki.PkiCertificateService;
import org.eclipse.edc.opcuamqtt.pki.PkiCertificateServiceImpl;
import org.eclipse.edc.opcuamqtt.pki.PkiConfig;
import org.eclipse.edc.opcuamqtt.security.SecurityService;
import org.eclipse.edc.opcuamqtt.security.mqtt.MqttSecurityServiceImpl;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.web.spi.WebService;

public class OpcUaMqttExtension implements ServiceExtension {

    private static final String MQTT_BROKER_URL_ENV = "edc.opcua.mqtt.broker.url";
    private static final String MQTT_USERNAME_ENV = "edc.opcua.mqtt.username";
    private static final String MQTT_PASSWORD_ENV = "edc.opcua.mqtt.password";
    private static final String MQTT_ADMIN_USERNAME_ENV = "edc.opcua.mqtt.admin.username";
    private static final String MQTT_ADMIN_PASSWORD_ENV = "edc.opcua.mqtt.admin.password";
    private static final String MQTT_CERTIFICATE_AUTHENTICATION_ENABLED = "edc.opcua.mqtt.cert.auth.enabled";
    private static final String MQTT_ADMIN_CERTIFICATE_PATH = "edc.opcua.mqtt.admin.cert.path";
    private static final String MQTT_ADMIN_CERTIFICATE_KEY_PATH = "edc.opcua.mqtt.admin.key.path";
    private static final String MQTT_PUSH_USER_CERTIFICATE_PATH = "edc.opcua.mqtt.push.user.cert.path";
    private static final String MQTT_PUSH_USER_CERTIFICATE_KEY_PATH = "edc.opcua.mqtt.push.user.key.path";
    private static final String MQTT_CA_CHAIN_CERTIFICATE_PATH = "edc.opcua.mqtt.ca.cert.path";
    private static final String PKI_ENDPOINT_URL = "edc.opcua.mqtt.pki.endpoint.url";
    private static final String PKI_ENDPOINT_KEY = "edc.opcua.mqtt.pki.endpoint.key";

    @Inject(required = false)
    private DataFlowManager dataFlowManager;

    @Inject(required = false)
    private WebService webService;

    @Override
    public String name() {
        return "OPC UA MQTT Extension";
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();
        monitor.info("Initializing OPC UA MQTT Extension");

        // Create internal OPC UA client - no dependencies on other extensions
        MqttOpcUaClient opcUaClient = new OpcUaClientImpl(monitor);
        context.registerService(MqttOpcUaClient.class, opcUaClient);
        monitor.info("Registered internal MqttOpcUaClient for MQTT extension");

        // Load MQTT broker configuration from EDC settings (environment variables, system properties, or config files)
        monitor.info("Reading MQTT broker configuration from EDC settings...");
        String brokerUrl = context.getSetting(MQTT_BROKER_URL_ENV, null);

        OpcUaMqttClient pushMqttClient = null;
        OpcUaMqttClient adminMqttClient = null;
        MqttBrokerConfig brokerConfig = null;
        PkiConfig pkiConfig = null;

        boolean certBasedAuthEnabled = context.getSetting(MQTT_CERTIFICATE_AUTHENTICATION_ENABLED, false);
        monitor.debug("Certificate-based authentication enabled: " + certBasedAuthEnabled);

        if (certBasedAuthEnabled) {
            var adminCertPath = context.getSetting(MQTT_ADMIN_CERTIFICATE_PATH, null);
            var adminKeyPath = context.getSetting(MQTT_ADMIN_CERTIFICATE_KEY_PATH, null);
            var caChainCertPath = context.getSetting(MQTT_CA_CHAIN_CERTIFICATE_PATH, null);
            var pushUserCertPath = context.getSetting(MQTT_PUSH_USER_CERTIFICATE_PATH, null);
            var pushUserKeyPath = context.getSetting(MQTT_PUSH_USER_CERTIFICATE_KEY_PATH, null);
            var pkiEndpoint = context.getSetting(PKI_ENDPOINT_URL, null);
            var pkiKey = context.getSetting(PKI_ENDPOINT_KEY, null);

            if (adminCertPath == null || adminKeyPath == null || caChainCertPath == null || pushUserCertPath == null || pushUserKeyPath == null || pkiEndpoint == null || pkiKey == null) {
                monitor.warning("Certificate-based authentication is enabled, but some required settings are missing. " +
                        "Please check your configuration.");
                return;
            }

            pkiConfig = new PkiConfig(pkiEndpoint, pkiKey);
            brokerConfig = new MqttBrokerConfig(brokerUrl, caChainCertPath, pushUserCertPath, pushUserKeyPath, null);
            pushMqttClient = new PahoOpcUaMqttClientImpl(monitor, caChainCertPath, pushUserCertPath, pushUserKeyPath);
            adminMqttClient = new PahoOpcUaMqttClientImpl(monitor, caChainCertPath, adminCertPath, adminKeyPath, null);
        } else {
            var mqttUsername = context.getSetting(MQTT_USERNAME_ENV, null);
            var mqttPassword = context.getSetting(MQTT_PASSWORD_ENV, null);
            var adminUsername = context.getSetting(MQTT_ADMIN_USERNAME_ENV, null);
            var adminPassword = context.getSetting(MQTT_ADMIN_PASSWORD_ENV, null);

            if (mqttUsername == null || mqttPassword == null || adminUsername == null || adminPassword == null) {
                monitor.warning("Certificate-based authentication is disabled, but some required settings are missing. " +
                        "Please check your configuration.");
                return;
            }

            brokerConfig = new MqttBrokerConfig(brokerUrl, mqttUsername, mqttPassword);
            pushMqttClient = new PahoOpcUaMqttClientImpl(monitor, mqttUsername, mqttPassword);
            adminMqttClient = new PahoOpcUaMqttClientImpl(monitor, adminUsername, adminPassword);
        }
        monitor.info("MqttBrokerConfig created: " + brokerConfig);

        // Create MQTT client implementation
        context.registerService(OpcUaMqttClient.class, pushMqttClient);
        monitor.info("Registered OpcUaMqttClient (Paho implementation)");

        if (brokerUrl == null || brokerUrl.trim().isEmpty()) {
            monitor.warning("MQTT broker URL not configured. Set '" + MQTT_BROKER_URL_ENV + "' configuration. " +
                    "Example: edc.opcua.mqtt.broker.url=tcp://mqtt-broker:1883");
        } else {
            monitor.info("MQTT broker configured: " + brokerUrl);
        }

        // Create and register the push service
        OpcUaMqttPushService pushService = new OpcUaMqttPushServiceImpl(opcUaClient, pushMqttClient, brokerConfig, monitor);
        context.registerService(OpcUaMqttPushService.class, pushService);
        monitor.info("Registered OpcUaMqttPushService with provider-managed MQTT broker");

        // Create and register the EDR service (acts as a cache for active transfers)
        MqttEdrService edrService = new InMemoryMqttEdrService();
        monitor.info("Registered MqttEdrService for caching active MQTT transfers");

        // Create and register Mosquitto Dynamic Security service

        if (brokerUrl != null && !brokerUrl.trim().isEmpty()) {

            MqttSecurityServiceImpl securityService = new MqttSecurityServiceImpl(brokerUrl, adminMqttClient, monitor);
            context.registerService(SecurityService.class, securityService);
            monitor.info("Registered SecurityService for dynamic user and role management");
        } else {
            monitor.warning("Mosquitto Dynamic Security service not initialized - broker URL not configured");
        }

        // Only register dataflow controller if we're in control plane (DataFlowManager available)
        if (dataFlowManager != null && webService != null) {
            // Get the security service (may be null if broker not configured)
            var securityService = context.getService(SecurityService.class, true);

            PkiCertificateService pkiCertificateService = new PkiCertificateServiceImpl(pkiConfig, monitor);
            context.registerService(PkiCertificateService.class, pkiCertificateService);
            // Create and register the data flow controller
            // The DataFlowController handles MQTT-PUSH transfers and stores EDR data
            OpcUaMqttDataFlowController flowController = new OpcUaMqttDataFlowController(
                    pushService, pkiCertificateService, brokerConfig, edrService, securityService, monitor);
            dataFlowManager.register(flowController);
            monitor.info("Registered OpcUaMqttDataFlowController with DataFlowManager for MQTT-PUSH transfers");

            // Register the EDR API controller for consumer queries
            var edrController = new MqttEdrApiController(edrService, monitor);
            webService.registerResource("default", edrController);
            monitor.info("Registered MqttEdrApiController for serving MQTT EDR requests at /edr endpoint");
        } else {
            monitor.debug("DataFlowManager or WebService not available - running in dataplane-only mode");
        }
    }
}