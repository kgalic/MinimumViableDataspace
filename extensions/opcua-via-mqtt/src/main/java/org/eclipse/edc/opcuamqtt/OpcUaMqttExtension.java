package org.eclipse.edc.opcuamqtt;

import org.eclipse.edc.common.spi.config.mosquitto.IndustrialConnectorResolverConfigServiceImpl;
import org.eclipse.edc.opcuamqtt.dataflow.TransferFlowService;
import org.eclipse.edc.opcuamqtt.dataflow.mosquitto.OpcUaMosquittoMqttTransferFlowProvisionerServiceImpl;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

public class OpcUaMqttExtension implements ServiceExtension {

    // Add configuration settings for conditional loading
    private static final String EXTENSION_ENABLED = "edc.opcua.mqtt.extension.enabled";

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

        var configServiceImplementation = new IndustrialConnectorResolverConfigServiceImpl(context.getConfig());
        var config = configServiceImplementation.getConfig();

        var transferFlowServiceProvisioner = new OpcUaMosquittoMqttTransferFlowProvisionerServiceImpl(monitor, configServiceImplementation);
        var transferFlowService = transferFlowServiceProvisioner.getTransferFlowService();
        context.registerService(TransferFlowService.class, transferFlowService);
        monitor.info("Registered TransferFlowService provisioner for OPC UA MQTT with Mosquitto broker");
    }
}
