// extensions/opcua/build.gradle.kts
plugins {
    `java-library`
}

dependencies {
    implementation(libs.edc.boot)
    implementation(libs.edc.core.runtime)

    implementation("org.eclipse.edc:web-spi:0.9.0")
    implementation("org.eclipse.edc:transfer-spi:0.9.0")
    runtimeOnly("org.eclipse.edc:jetty-core:0.9.0")

    implementation("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
    implementation(libs.edc.dataplane.spi)
    implementation(libs.milo.sdk.client)
    implementation(libs.jackson.datatype.jakarta.jsonp)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
