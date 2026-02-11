// extensions/opcua-via-mqtt/build.gradle.kts
plugins {
    `java-library`
}

dependencies {
    implementation(libs.edc.boot)
    implementation(libs.edc.core.runtime)

    // OPC UA and Transfer SPI for DataFlow support
    implementation(project(":extensions:opcua"))
    implementation("org.eclipse.edc:transfer-spi:0.9.0")
    implementation("org.eclipse.edc:web-spi:0.9.0")
    runtimeOnly("org.eclipse.edc:jetty-core:0.9.0")

    // JAX-RS for REST API endpoints
    implementation("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")

    // MQTT Client - Eclipse Paho v3
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // JSON processing
    implementation(libs.jackson.datatype.jakarta.jsonp)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
