plugins {
    `java-library`
}

dependencies {
    implementation(libs.edc.boot)
    implementation(libs.edc.core.runtime)

    // MQTT Client - Eclipse Paho v3
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // BouncyCastle for PEM certificate/key parsing
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}