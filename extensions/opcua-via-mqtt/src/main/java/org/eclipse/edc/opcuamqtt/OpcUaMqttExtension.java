package org.eclipse.edc.opcuamqtt;

import org.eclipse.edc.connector.controlplane.transfer.spi.flow.DataFlowManager;
import org.eclipse.edc.opcua.client.OpcUaClientService;
import org.eclipse.edc.opcuamqtt.client.OpcUaMqttClient;
import org.eclipse.edc.opcuamqtt.client.PahoOpcUaMqttClientImpl;
import org.eclipse.edc.opcuamqtt.dataflow.OpcUaMqttDataFlowController;
import org.eclipse.edc.opcuamqtt.edr.InMemoryMqttEdrService;
import org.eclipse.edc.opcuamqtt.edr.MqttEdrApiController;
import org.eclipse.edc.opcuamqtt.edr.MqttEdrService;
import org.eclipse.edc.opcuamqtt.mqttpush.MqttBrokerConfig;
import org.eclipse.edc.opcuamqtt.mqttpush.OpcUaMqttPushService;
import org.eclipse.edc.opcuamqtt.mqttpush.OpcUaMqttPushServiceImpl;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.web.spi.WebService;

public class OpcUaMqttExtension implements ServiceExtension {

    private static final String MQTT_BROKER_URL_ENV = "EDC_OPCUA_MQTT_BROKER_URL";
    private static final String MQTT_USERNAME_ENV = "EDC_OPCUA_MQTT_USERNAME";
    private static final String MQTT_PASSWORD_ENV = "EDC_OPCUA_MQTT_PASSWORD";

    @Inject
    private DataFlowManager dataFlowManager;

    @Inject
    private WebService webService;

    @Override
    public String name() {
        return "OPC UA MQTT Extension";
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();

        // Create MQTT client implementation
        OpcUaMqttClient mqttClient = new PahoOpcUaMqttClientImpl(monitor);
        context.registerService(OpcUaMqttClient.class, mqttClient);
        monitor.info("Registered OpcUaMqttClient (Paho implementation)");

        // Get OPC UA client service from the registry
        var opcUaClientService = context.getService(OpcUaClientService.class);
        if (opcUaClientService == null) {
            monitor.severe("OpcUaClientService not found in context. Make sure opcua extension is loaded before opcua-via-mqtt.");
            throw new RuntimeException("OpcUaClientService not available");
        }

        // Load MQTT broker configuration from environment variables
        String brokerUrl = System.getenv(MQTT_BROKER_URL_ENV);
        String mqttUsername = System.getenv(MQTT_USERNAME_ENV);
        String mqttPassword = System.getenv(MQTT_PASSWORD_ENV);

        if (brokerUrl == null || brokerUrl.trim().isEmpty()) {
            monitor.warning("MQTT broker URL not configured. Set '" + MQTT_BROKER_URL_ENV + "' environment variable. " +
                    "Example: tcp://mqtt-broker:1883");
        } else {
            monitor.info("MQTT broker configured: " + brokerUrl);
        }

        MqttBrokerConfig brokerConfig = new MqttBrokerConfig(brokerUrl, mqttUsername, mqttPassword);

        // Create and register the push service
        OpcUaMqttPushService pushService = new OpcUaMqttPushServiceImpl(opcUaClientService, mqttClient, brokerConfig, monitor);
        context.registerService(OpcUaMqttPushService.class, pushService);
        monitor.info("Registered OpcUaMqttPushService with provider-managed MQTT broker");

        // Create and register the EDR service (acts as a cache for active transfers)
        MqttEdrService edrService = new InMemoryMqttEdrService();
        context.registerService(MqttEdrService.class, edrService);
        monitor.info("Registered MqttEdrService for caching active MQTT transfers");

        // Create and register the data flow controller
        // The DataFlowController handles MQTT-PUSH transfers and stores EDR data
        OpcUaMqttDataFlowController flowController = new OpcUaMqttDataFlowController(pushService, brokerConfig, edrService, monitor);
        dataFlowManager.register(flowController);
        monitor.info("Registered OpcUaMqttDataFlowController with DataFlowManager for MQTT-PUSH transfers");

        // Register the EDR API controller for consumer queries
        var edrController = new MqttEdrApiController(edrService, monitor);
        webService.registerResource("default", edrController);
        monitor.info("Registered MqttEdrApiController for serving MQTT EDR requests at /edr endpoint");
    }
}