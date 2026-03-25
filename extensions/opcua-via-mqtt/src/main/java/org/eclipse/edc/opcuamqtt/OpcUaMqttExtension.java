package org.eclipse.edc.opcuamqtt;

import org.eclipse.edc.connector.controlplane.transfer.spi.flow.DataFlowManager;
import org.eclipse.edc.industrial.wss.IndustrialWebSocketService;
import org.eclipse.edc.opcuamqtt.dataflow.OpcUaMqttDataFlowController;
import org.eclipse.edc.opcuamqtt.mqttclient.MqttClient;
import org.eclipse.edc.opcuamqtt.mqttclient.PahoMqttClientImpl;
import org.eclipse.edc.opcuamqtt.mqttpush.MqttBrokerConfig;
import org.eclipse.edc.opcuamqtt.mqttpush.OpcUaMqttPushService;
import org.eclipse.edc.opcuamqtt.mqttpush.OpcUaMqttPushServiceImpl;
import org.eclipse.edc.opcuamqtt.opcua.OpcUaClientService;
import org.eclipse.edc.opcuamqtt.opcua.OpcUaClientServiceImpl;
import org.eclipse.edc.opcuamqtt.pki.PkiCertificateService;
import org.eclipse.edc.opcuamqtt.pki.custom.PkiCertificateServiceImpl;
import org.eclipse.edc.opcuamqtt.pki.custom.PkiConfig;
import org.eclipse.edc.opcuamqtt.security.SecurityService;
import org.eclipse.edc.opcuamqtt.security.mqtt.MqttSecurityServiceImpl;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.web.spi.WebService;

public class OpcUaMqttExtension implements ServiceExtension {

    // Add configuration settings for conditional loading
    private static final String EXTENSION_ENABLED = "edc.opcua.mqtt.extension.enabled";
    private static final String WEBSOCKET_INTEGRATION_ENABLED = "edc.opcua.mqtt.websocket.enabled";

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

    // Store context and services for use in start() method
    private ServiceExtensionContext extensionContext;
    private OpcUaMqttPushService pushService;
    private PkiCertificateService pkiCertificateService;
    private SecurityService securityService;
    private MqttBrokerConfig brokerConfig;

    @Override
    public String name() {
        return "OPC UA MQTT Extension";
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();

        // Check if extension should be enabled
        boolean extensionEnabled = context.getSetting(EXTENSION_ENABLED, false);
        if (!extensionEnabled) {
            monitor.info("OPC UA MQTT Extension is disabled via configuration");
            return;
        }

        this.extensionContext = context; // Store context for later use
        monitor.info("Initializing OPC UA MQTT Extension");

        // Create internal OPC UA client - no dependencies on other extensions
        OpcUaClientService opcUaClient = new OpcUaClientServiceImpl(monitor);
        context.registerService(OpcUaClientService.class, opcUaClient);
        monitor.info("Registered internal OpcUaClientService for MQTT extension");

        // Load MQTT broker configuration from EDC settings (environment variables, system properties, or config files)
        monitor.info("Reading MQTT broker configuration from EDC settings...");
        String brokerUrl = context.getSetting(MQTT_BROKER_URL_ENV, null);

        MqttClient pushMqttClient = null;
        MqttClient adminMqttClient = null;
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
            pushMqttClient = new PahoMqttClientImpl(monitor, caChainCertPath, pushUserCertPath, pushUserKeyPath);
            adminMqttClient = new PahoMqttClientImpl(monitor, caChainCertPath, adminCertPath, adminKeyPath, null);
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
            pushMqttClient = new PahoMqttClientImpl(monitor, mqttUsername, mqttPassword);
            adminMqttClient = new PahoMqttClientImpl(monitor, adminUsername, adminPassword);
        }
        monitor.info("MqttBrokerConfig created: " + brokerConfig);

        // Create MQTT mqttclient implementation
        context.registerService(MqttClient.class, pushMqttClient);
        monitor.info("Registered MqttClient (Paho implementation)");

        if (brokerUrl == null || brokerUrl.trim().isEmpty()) {
            monitor.warning("MQTT broker URL not configured. Set '" + MQTT_BROKER_URL_ENV + "' configuration. " +
                    "Example: edc.opcua.mqtt.broker.url=tcp://mqtt-broker:1883");
        } else {
            monitor.info("MQTT broker configured: " + brokerUrl);
        }

        // Create and register the push service
        pushService = new OpcUaMqttPushServiceImpl(opcUaClient, pushMqttClient, brokerConfig, monitor);
        context.registerService(OpcUaMqttPushService.class, pushService);
        monitor.info("Registered OpcUaMqttPushService with provider-managed MQTT broker");

        // Create and register Mosquitto Dynamic Security service

        if (brokerUrl != null && !brokerUrl.trim().isEmpty()) {

            MqttSecurityServiceImpl securityService = new MqttSecurityServiceImpl(brokerUrl, adminMqttClient, monitor);
            context.registerService(SecurityService.class, securityService);
            monitor.info("Registered SecurityService for dynamic user and role management");
        } else {
            monitor.warning("Mosquitto Dynamic Security service not initialized - broker URL not configured");
        }

        // Only register basic services, DataFlowController will be created in start() with WebSocket service
        if (dataFlowManager != null && webService != null) {
            // Get the security service (may be null if broker not configured)
            this.securityService = context.getService(SecurityService.class, true);
            this.pkiCertificateService = new PkiCertificateServiceImpl(pkiConfig, monitor);
            context.registerService(PkiCertificateService.class, this.pkiCertificateService);

            monitor.info("Services registered - DataFlowController will be created in start() phase with WebSocket integration");
        } else {
            monitor.debug("DataFlowManager or WebService not available - running in dataplane-only mode");
        }

        if (dataFlowManager != null && webService != null && pushService != null) {
            OpcUaMqttDataFlowController flowController = new OpcUaMqttDataFlowController(
                    pushService, pkiCertificateService, brokerConfig, securityService, monitor);
            dataFlowManager.register(flowController);
            context.registerService(OpcUaMqttDataFlowController.class, flowController);
        }
    }

    @Override
    public void start() {
        // Check if extension was properly initialized (not disabled via configuration)
        if (extensionContext == null) {
            // Extension was disabled in initialize() - do nothing
            return;
        }

        var monitor = extensionContext.getMonitor();
        monitor.info("Starting OPC UA MQTT Extension - checking for WebSocket service availability");

        // Now that all extensions have completed initialization, check for WebSocket service
        var webSocketService = extensionContext.getService(IndustrialWebSocketService.class, true);
        if (webSocketService != null) {
            monitor.info("Industrial WebSocket Service is available for client communication");
            monitor.info("Active WebSocket clients: " + webSocketService.getActiveSessionCount());
        } else {
            monitor.debug("Industrial WebSocket Service not available - will use direct OPC-UA connections");
        }

        var mqttDataFlowController = extensionContext.getService(OpcUaMqttDataFlowController.class, true);
        if (mqttDataFlowController != null) {
            monitor.info("OPC UA MQTT DataFlowController is available");
            mqttDataFlowController.setWebSocketService(webSocketService);
        }
    }
}
