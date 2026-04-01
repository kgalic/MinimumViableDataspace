package org.eclipse.edc.industrial.connector.resolver;

import org.eclipse.edc.common.spi.config.mosquitto.IndustrialConnectorResolverConfigServiceImpl;
import org.eclipse.edc.common.spi.pki.PkiCertificateService;
import org.eclipse.edc.common.spi.pki.custom.PkiCertificateServiceImpl;
import org.eclipse.edc.common.spi.pki.custom.PkiConfig;
import org.eclipse.edc.common.spi.security.SecurityService;
import org.eclipse.edc.common.spi.security.mosquitto.MosquittoSecurityProvisionerServiceImpl;
import org.eclipse.edc.connector.controlplane.transfer.spi.flow.DataFlowManager;
import org.eclipse.edc.industrial.connector.resolver.dataflow.IndustrialConnectorDataFlow;
import org.eclipse.edc.industrial.connector.resolver.datatypes.IndustrialConnectorDataTypes;
import org.eclipse.edc.industrial.connector.resolver.datatypes.implementation.IndustrialConnectorDataTypesImpl;
import org.eclipse.edc.opcuamqtt.dataflow.TransferFlowService;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import static org.eclipse.edc.industrial.connector.resolver.IndustrialConnectorResolverExtension.NAME;

@Extension(value = NAME)
public class IndustrialConnectorResolverExtension implements ServiceExtension {
    public static final String NAME = "Industrial Connector Resolver Extension";

    private static final String EXTENSION_ENABLED = "edc.industrial.connector.extension.enabled";
    private static final String WEBSOCKET_INTEGRATION_ENABLED = "edc.opcua.mosquitto.websocket.enabled";

    private ServiceExtensionContext context;
    private IndustrialConnectorDataFlow industrialDataFlow;

    @Override
    public String name() {
        return NAME;
    }

    @Inject(required = false)
    private DataFlowManager dataFlowManager;

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor().withPrefix("INDUSTRIAL");
        monitor.info("Industrial Connector Resolver Extension initialized");
        this.context = context;
        boolean extensionEnabled = context.getSetting(EXTENSION_ENABLED, false);
        if (!extensionEnabled) {
            monitor.info("Resolver Extension is disabled via configuration");
            return;
        }

        // Read the config
        var configServiceImplementation = new IndustrialConnectorResolverConfigServiceImpl(context.getConfig());
        var config = configServiceImplementation.getConfig();

        if (dataFlowManager != null) {
            monitor.info("Industrial WebSocket Extension is enabled");


            if (config.getPkiEndpointKey() == null || config.getPkiEndpointUrl() == null) {
                monitor.warning("PKI endpoint URL or key is not configured. PKI Certificate Service will not be registered.");
                return;
            }

            // Register the data types service
            var dataTypesService = new IndustrialConnectorDataTypesImpl();
            context.registerService(IndustrialConnectorDataTypes.class, dataTypesService);

            // Register the SecurityService for MQTT access control
            var securityServiceProvisioner = new MosquittoSecurityProvisionerServiceImpl(monitor, configServiceImplementation);
            var securityService = securityServiceProvisioner.getSecurityService();
            context.registerService(SecurityService.class, securityService);

            // Register the PkiCertificateService
            var pkiConfig = new PkiConfig(config.getPkiEndpointUrl(), config.getPkiEndpointKey());
            var pkiService = new PkiCertificateServiceImpl(pkiConfig, monitor);
            context.registerService(PkiCertificateService.class, pkiService);

            this.industrialDataFlow = new IndustrialConnectorDataFlow(pkiService, dataTypesService, configServiceImplementation, securityService);
            dataFlowManager.register(this.industrialDataFlow);
        } else {
            monitor.info("Industrial WebSocket Extension is disabled");
        }
    }

    @Override
    public void start() {
        var transferFlowServiceInstance = this.context.getService(TransferFlowService.class);
        if (transferFlowServiceInstance == null) {
            context.getMonitor().warning("TransferFlowService is not available. Industrial Connector Resolver Extension will not start.");
            return;
        }
        this.industrialDataFlow.setDataFlow(transferFlowServiceInstance);
        context.getMonitor().info("Industrial Connector Resolver Extension started successfully");
    }
}
