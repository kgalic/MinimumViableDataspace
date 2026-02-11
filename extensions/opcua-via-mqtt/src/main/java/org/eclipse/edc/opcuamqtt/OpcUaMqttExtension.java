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
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.web.spi.WebService;

public class OpcUaMqttExtension implements ServiceExtension {

    private static final String MQTT_BROKER_URL_ENV = "edc.opcua.mqtt.broker.url";
    private static final String MQTT_USERNAME_ENV = "edc.opcua.mqtt.username";
    private static final String MQTT_PASSWORD_ENV = "edc.opcua.mqtt.password";

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

        // Create MQTT client implementation
        OpcUaMqttClient mqttClient = new PahoOpcUaMqttClientImpl(monitor);
        context.registerService(OpcUaMqttClient.class, mqttClient);
        monitor.info("Registered OpcUaMqttClient (Paho implementation)");

        // Load MQTT broker configuration from EDC settings (environment variables, system properties, or config files)
        monitor.info("Reading MQTT broker configuration from EDC settings...");
        String brokerUrl = context.getSetting(MQTT_BROKER_URL_ENV, null);
        String mqttUsername = context.getSetting(MQTT_USERNAME_ENV, null);
        String mqttPassword = context.getSetting(MQTT_PASSWORD_ENV, null);

        monitor.debug("Configuration read - brokerUrl: " + (brokerUrl != null ? brokerUrl : "null") +
                    ", username: " + (mqttUsername != null ? "***" : "null") +
                    ", password: " + (mqttPassword != null ? "***" : "null"));

        if (brokerUrl == null || brokerUrl.trim().isEmpty()) {
            monitor.warning("MQTT broker URL not configured. Set '" + MQTT_BROKER_URL_ENV + "' configuration. " +
                    "Example: edc.opcua.mqtt.broker.url=tcp://mqtt-broker:1883");
        } else {
            monitor.info("MQTT broker configured: " + brokerUrl);
        }

        MqttBrokerConfig brokerConfig = new MqttBrokerConfig(brokerUrl, mqttUsername, mqttPassword);
        monitor.info("MqttBrokerConfig created: " + brokerConfig);

        // Create and register the push service
        OpcUaMqttPushService pushService = new OpcUaMqttPushServiceImpl(opcUaClient, mqttClient, brokerConfig, monitor);
        context.registerService(OpcUaMqttPushService.class, pushService);
        monitor.info("Registered OpcUaMqttPushService with provider-managed MQTT broker");

        // Create and register the EDR service (acts as a cache for active transfers)
        MqttEdrService edrService = new InMemoryMqttEdrService();
        context.registerService(MqttEdrService.class, edrService);
        monitor.info("Registered MqttEdrService for caching active MQTT transfers");

        // Only register dataflow controller if we're in control plane (DataFlowManager available)
        if (dataFlowManager != null && webService != null) {
            // Create and register the data flow controller
            // The DataFlowController handles MQTT-PUSH transfers and stores EDR data
            OpcUaMqttDataFlowController flowController = new OpcUaMqttDataFlowController(pushService, brokerConfig, edrService, monitor);
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