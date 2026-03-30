/*
 *  Copyright (c) 2024 Metaform Systems, Inc.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Metaform Systems, Inc. - initial API and implementation
 *
 */

package org.eclipse.edc.industrial.connector.resolver;

import org.eclipse.edc.connector.controlplane.transfer.spi.flow.DataFlowManager;
import org.eclipse.edc.industrial.connector.resolver.config.IndustrialConnectorResolverConfigService;
import org.eclipse.edc.industrial.connector.resolver.config.mosquitto.IndustrialConnectorResolverConfigServiceImpl;
import org.eclipse.edc.industrial.connector.resolver.dataflow.IndustrialConnectorDataFlow;
import org.eclipse.edc.industrial.connector.resolver.datatypes.IndustrialConnectorDataTypes;
import org.eclipse.edc.industrial.connector.resolver.datatypes.implementation.IndustrialConnectorDataTypesImpl;
import org.eclipse.edc.industrial.connector.resolver.pki.PkiCertificateService;
import org.eclipse.edc.industrial.connector.resolver.pki.custom.PkiCertificateServiceImpl;
import org.eclipse.edc.industrial.connector.resolver.pki.custom.PkiConfig;
import org.eclipse.edc.industrial.connector.resolver.security.SecurityService;
import org.eclipse.edc.industrial.connector.resolver.security.mosquitto.MosquittoSecurityProvisionerServiceImpl;
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

        boolean extensionEnabled = context.getSetting(EXTENSION_ENABLED, false);
        if (!extensionEnabled) {
            monitor.info("OPC UA MQTT Extension is disabled via configuration");
            return;
        }

        // Register the config service
        var configServiceImplementation = new IndustrialConnectorResolverConfigServiceImpl(context.getConfig());
        context.registerService(IndustrialConnectorResolverConfigService.class, configServiceImplementation);



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
            var securityServiceProvisioner = new MosquittoSecurityProvisionerServiceImpl(monitor, config);
            var securityService = securityServiceProvisioner.getSecurityService();
            context.registerService(SecurityService.class, securityService);

            // Register the PkiCertificateService
            var pkiConfig = new PkiConfig(config.getPkiEndpointUrl(), config.getPkiEndpointKey());
            var pkiService = new PkiCertificateServiceImpl(pkiConfig, monitor);
            context.registerService(PkiCertificateService.class, pkiService);

            var industrialDataFlow = new IndustrialConnectorDataFlow(pkiService, dataTypesService, configServiceImplementation, securityService);
            dataFlowManager.register(industrialDataFlow);
        } else {
            monitor.info("Industrial WebSocket Extension is disabled");
        }
    }
}
