package org.eclipse.edc.opcua.model;

public class OpcUaCredentials {
    private String serverUrl;           // e.g., opc.tcp://localhost:53530/OPCUA/SimulationServer
    private String username;            // optional
    private String password;            // optional
    private String securityPolicy;      // e.g., None, Basic256Sha256 (optional)
    private String messageSecurityMode; // e.g., None, Sign, SignAndEncrypt (optional)

    public OpcUaCredentials() {
    }

    public OpcUaCredentials(String serverUrl, String username, String password, String securityPolicy, String messageSecurityMode) {
        this.serverUrl = serverUrl;
        this.username = username;
        this.password = password;
        this.securityPolicy = securityPolicy;
        this.messageSecurityMode = messageSecurityMode;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getUsername() {
        return username;
    }

    public boolean hasUsername() {
        return username != null && !username.isBlank();
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public boolean hasPassword() {
        return password != null;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getSecurityPolicy() {
        return securityPolicy;
    }

    public void setSecurityPolicy(String securityPolicy) {
        this.securityPolicy = securityPolicy;
    }

    public String getMessageSecurityMode() {
        return messageSecurityMode;
    }

    public void setMessageSecurityMode(String messageSecurityMode) {
        this.messageSecurityMode = messageSecurityMode;
    }
}
