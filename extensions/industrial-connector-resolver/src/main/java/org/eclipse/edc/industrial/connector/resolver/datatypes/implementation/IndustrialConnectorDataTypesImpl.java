package org.eclipse.edc.industrial.connector.resolver.datatypes.implementation;

import org.eclipse.edc.industrial.connector.resolver.datatypes.IndustrialConnectorDataTypes;

import java.util.Set;

public class IndustrialConnectorDataTypesImpl implements IndustrialConnectorDataTypes {

    private static final String OPCUAMQTT_TYPE = "opcuamqtt";
    private static final String OPCUA_TYPE = "opcua";
    private static final String MQTT_PUSH = "mqtt-push";

    @Override
    public Set<String> getSupportedDataTypes() {
        return Set.of(OPCUAMQTT_TYPE, OPCUA_TYPE, MQTT_PUSH);
    }

    @Override
    public Boolean isSupported(String dataType) {
        return getSupportedDataTypes().contains(dataType.toLowerCase());
    }
}
