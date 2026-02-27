package org.eclipse.edc.opcuamqtt.security;

/**
 * Represents credentials created for a Mosquitto user.
 */
public class MosquittoCredentials {
    private final String username;
    private final String password;
    private final String roleName;
    private final String topic;

    public MosquittoCredentials(String username, String password, String roleName, String topic) {
        this.username = username;
        this.password = password;
        this.roleName = roleName;
        this.topic = topic;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getRoleName() {
        return roleName;
    }

    public String getTopic() {
        return topic;
    }
}
