package org.eclipse.edc.opcuamqtt.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of Mosquitto Dynamic Security service.
 * Communicates with Mosquitto broker's Dynamic Security plugin via control topics.
 */
public class MosquittoSecurityServiceImpl implements MosquittoSecurityService {

    private static final String CONTROL_TOPIC = "$CONTROL/dynamic-security/v1";
    private static final String RESPONSE_TOPIC = "$CONTROL/dynamic-security/v1/response";
    private static final int RESPONSE_TIMEOUT_SECONDS = 10;

    private final String brokerUrl;
    private final String adminUsername;
    private final String adminPassword;
    private final Monitor monitor;
    private final ObjectMapper objectMapper;

    public MosquittoSecurityServiceImpl(String brokerUrl, String adminUsername, String adminPassword, Monitor monitor) {
        this.brokerUrl = brokerUrl;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.monitor = monitor;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Result<MosquittoCredentials> createUserWithPermissions(String topic, String transferId) {
        // Generate unique username, password, and role name
        String username = "edc-user-" + transferId;
        String password = UUID.randomUUID().toString();
        String roleName = "edc-role-" + transferId;

        monitor.info("Creating Mosquitto user and role for transfer: " + transferId);

        try {
            // Create dedicated MQTT client for control messages
            String clientId = "edc-security-" + UUID.randomUUID();
            MqttClient client = new MqttClient(brokerUrl, clientId);
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);

            if (adminUsername != null && !adminUsername.isEmpty()) {
                options.setUserName(adminUsername);
                options.setPassword(adminPassword != null ? adminPassword.toCharArray() : new char[0]);
            }

            client.connect(options);
            monitor.debug("Connected to Mosquitto for security operations");

            // Subscribe to response topic
            // Use array holder to make it effectively final for use in lambda
            final CompletableFuture<String>[] responseFutureHolder = new CompletableFuture[]{new CompletableFuture<>()};
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    monitor.warning("Connection lost during security operation", cause);
                    responseFutureHolder[0].completeExceptionally(cause);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    if (RESPONSE_TOPIC.equals(topic)) {
                        String response = new String(message.getPayload(), StandardCharsets.UTF_8);
                        monitor.debug("Received security response: " + response);
                        responseFutureHolder[0].complete(response);
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // Not needed for this implementation
                }
            });

            client.subscribe(RESPONSE_TOPIC);

            // Step 1: Create user
            monitor.debug("Step 1: Creating user " + username);
            publishCommand(client, MosquittoCommand.Command.createClient(username, password));
            waitForResponse(responseFutureHolder[0], "Create user");

            // Step 2: Create role
            monitor.debug("Step 2: Creating role " + roleName);
            responseFutureHolder[0] = new CompletableFuture<>();
            publishCommand(client, MosquittoCommand.Command.createRole(roleName));
            waitForResponse(responseFutureHolder[0], "Create role");

            // Step 3: Add subscribe permission
            monitor.debug("Step 3: Adding subscribe permission for topic: " + topic);
            responseFutureHolder[0] = new CompletableFuture<>();
            publishCommand(client, MosquittoCommand.Command.addRoleAcl(roleName, "subscribePattern", topic, true));
            waitForResponse(responseFutureHolder[0], "Add subscribe permission");

            // Step 4: Add publishClientReceive permission
            monitor.debug("Step 4: Adding publishClientReceive permission for topic: " + topic);
            responseFutureHolder[0] = new CompletableFuture<>();
            publishCommand(client, MosquittoCommand.Command.addRoleAcl(roleName, "publishClientReceive", topic, true));
            waitForResponse(responseFutureHolder[0], "Add publishClientReceive permission");

            // Step 5: Assign role to user
            monitor.debug("Step 5: Assigning role to user");
            responseFutureHolder[0] = new CompletableFuture<>();
            publishCommand(client, MosquittoCommand.Command.addClientRole(username, roleName));
            waitForResponse(responseFutureHolder[0], "Assign role to user");

            // Clean up
            client.disconnect();
            client.close();

            monitor.info("Successfully created Mosquitto user " + username + " with role " + roleName);
            return Result.success(new MosquittoCredentials(username, password, roleName, topic));

        } catch (Exception e) {
            monitor.severe("Failed to create Mosquitto user and permissions", e);
            return Result.failure("Failed to create Mosquitto user: " + e.getMessage());
        }
    }

    @Override
    public Result<Void> removeUserAndRole(String username, String roleName) {
        monitor.info("Removing Mosquitto user " + username + " and role " + roleName);

        try {
            String clientId = "edc-security-cleanup-" + UUID.randomUUID();
            MqttClient client = new MqttClient(brokerUrl, clientId);
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);

            if (adminUsername != null && !adminUsername.isEmpty()) {
                options.setUserName(adminUsername);
                options.setPassword(adminPassword != null ? adminPassword.toCharArray() : new char[0]);
            }

            client.connect(options);

            // Subscribe to response topic
            // Use array holder to make it effectively final for use in lambda
            final CompletableFuture<String>[] responseFutureHolder = new CompletableFuture[]{new CompletableFuture<>()};
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    responseFutureHolder[0].completeExceptionally(cause);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    if (RESPONSE_TOPIC.equals(topic)) {
                        responseFutureHolder[0].complete(new String(message.getPayload(), StandardCharsets.UTF_8));
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });

            client.subscribe(RESPONSE_TOPIC);

            // Delete user
            MosquittoCommand.Command deleteUserCmd = new MosquittoCommand.Command();
            deleteUserCmd.setCommand("deleteClient");
            deleteUserCmd.setUsername(username);
            publishCommand(client, deleteUserCmd);
            waitForResponse(responseFutureHolder[0], "Delete user");

            // Delete role
            responseFutureHolder[0] = new CompletableFuture<>();
            MosquittoCommand.Command deleteRoleCmd = new MosquittoCommand.Command();
            deleteRoleCmd.setCommand("deleteRole");
            deleteRoleCmd.setRolename(roleName);
            publishCommand(client, deleteRoleCmd);
            waitForResponse(responseFutureHolder[0], "Delete role");

            client.disconnect();
            client.close();

            monitor.info("Successfully removed Mosquitto user and role");
            return Result.success();

        } catch (Exception e) {
            monitor.warning("Failed to remove Mosquitto user and role: " + e.getMessage(), e);
            return Result.failure("Failed to remove Mosquitto user: " + e.getMessage());
        }
    }

    private void publishCommand(MqttClient client, MosquittoCommand.Command command) throws Exception {
        MosquittoCommand wrapper = new MosquittoCommand(List.of(command));
        String json = objectMapper.writeValueAsString(wrapper);

        monitor.debug("Publishing command to " + CONTROL_TOPIC + ": " + json);

        MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
        message.setQos(1); // At least once delivery
        client.publish(CONTROL_TOPIC, message);
    }

    private void waitForResponse(CompletableFuture<String> future, String operation) throws Exception {
        try {
            String response = future.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            monitor.debug(operation + " response: " + response);

            // Check if response contains error
            if (response.toLowerCase().contains("error")) {
                throw new RuntimeException(operation + " failed: " + response);
            }
        } catch (Exception e) {
            monitor.severe(operation + " failed or timed out", e);
            throw new Exception(operation + " failed: " + e.getMessage(), e);
        }
    }
}
