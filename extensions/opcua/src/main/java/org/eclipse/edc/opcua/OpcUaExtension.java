
package org.eclipse.edc.opcua;

import org.eclipse.edc.connector.controlplane.transfer.spi.flow.DataFlowManager;
import org.eclipse.edc.opcua.client.OpcUaClientService;
import org.eclipse.edc.opcua.client.OpcUaClientServiceImpl;
import org.eclipse.edc.opcua.edr.EdrApiController;
import org.eclipse.edc.opcua.edr.EdrService;
import org.eclipse.edc.opcua.edr.InMemoryEdrService;
import org.eclipse.edc.opcua.flow.OpcUaDataFlowController;
import org.eclipse.edc.opcua.push.OpcUaPushService;
import org.eclipse.edc.opcua.push.OpcUaPushServiceImpl;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.web.spi.WebService;

public class OpcUaExtension implements ServiceExtension {

    @Inject
    private DataFlowManager dataFlowManager;

    @Inject
    private WebService webService;

    @Override
    public String name() {
        return "OPC UA Extension";
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var monitor = context.getMonitor();

        // Initialize EDR service for HTTP-PULL mechanism
        var edrService = new InMemoryEdrService();
        context.registerService(EdrService.class, edrService);

        // Initialize OPC UA client service
        var opcUaClientService = new OpcUaClientServiceImpl();
        context.registerService(OpcUaClientService.class, opcUaClientService);

        // Initialize OPC UA push service for HTTP-PUSH mechanism (without EdcHttpClient)
        var opcUaPushService = new OpcUaPushServiceImpl(opcUaClientService, monitor);
        context.registerService(OpcUaPushService.class, opcUaPushService);

        // Get public endpoint configuration
        var publicEndpoint = context.getSetting("edc.opcua.edr.endpoint", "http://localhost:19291/api/edr");

        // Create and register the data flow controller that handles both PULL and PUSH
        var opcUaDataFlowController = new OpcUaDataFlowController(publicEndpoint, edrService, opcUaPushService);
        dataFlowManager.register(opcUaDataFlowController);

        // Register EDR API controller for HTTP-PULL endpoints
        var edrApiController = new EdrApiController(edrService, opcUaClientService, monitor);
        webService.registerResource("default", edrApiController);

        monitor.info("OPC UA Extension initialized with EDR endpoint: " + publicEndpoint);
        monitor.info("OPC UA Extension supports both HTTP-PULL and HTTP-PUSH transfer mechanisms");
        monitor.debug("- HTTP-PULL: Consumer pulls data via EDR endpoints");
        monitor.debug("- HTTP-PUSH: Provider actively pushes data to consumer endpoints");
    }
}