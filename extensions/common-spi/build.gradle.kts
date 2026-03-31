plugins {
    `java-library`
}

dependencies {
    implementation(libs.edc.boot)
    implementation(libs.edc.core.runtime)

    // MQTT Client - Eclipse Paho v3
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}