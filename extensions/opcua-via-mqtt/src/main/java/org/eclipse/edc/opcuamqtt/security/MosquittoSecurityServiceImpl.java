package org.eclipse.edc.opcuamqtt.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.opcuamqtt.client.OpcUaMqttClient;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
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

    private final OpcUaMqttClient mqttClient;
    private final String brokerUrl;
    private final Monitor monitor;
    private final ObjectMapper objectMapper;

    public MosquittoSecurityServiceImpl(String brokerUrl, OpcUaMqttClient mqttClient, Monitor monitor) {
        this.mqttClient = mqttClient;
        this.monitor = monitor;
        this.brokerUrl = brokerUrl;
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
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);

            final CompletableFuture<String>[] responseFutureHolder = new CompletableFuture[]{new CompletableFuture<>()};
            this.mqttClient.setCallback(brokerUrl, new MqttCallback() {
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

            // Subscribe to topic
            this.mqttClient.subscribe(brokerUrl, RESPONSE_TOPIC);

            // Step 1: Create user
            monitor.debug("Step 1: Creating user " + username);
            publishCommand(this.mqttClient, MosquittoCommand.Command.createClient(username, password));
            waitForResponse(responseFutureHolder[0], "Create user");

            // Step 2: Create role
            monitor.debug("Step 2: Creating role " + roleName);
            responseFutureHolder[0] = new CompletableFuture<>();
            publishCommand(this.mqttClient, MosquittoCommand.Command.createRole(roleName));
            waitForResponse(responseFutureHolder[0], "Create role");

            // Step 3: Add subscribe permission
            monitor.debug("Step 3: Adding subscribe permission for topic: " + topic);
            responseFutureHolder[0] = new CompletableFuture<>();
            publishCommand(this.mqttClient, MosquittoCommand.Command.addRoleAcl(roleName, "subscribePattern", topic, true));
            waitForResponse(responseFutureHolder[0], "Add subscribe permission");

            // Step 4: Add publishClientReceive permission
            monitor.debug("Step 4: Adding publishClientReceive permission for topic: " + topic);
            responseFutureHolder[0] = new CompletableFuture<>();
            publishCommand(this.mqttClient, MosquittoCommand.Command.addRoleAcl(roleName, "publishClientReceive", topic, true));
            waitForResponse(responseFutureHolder[0], "Add publishClientReceive permission");

            // Step 5: Assign role to user
            monitor.debug("Step 5: Assigning role to user");
            responseFutureHolder[0] = new CompletableFuture<>();
            publishCommand(this.mqttClient, MosquittoCommand.Command.addClientRole(username, roleName));
            waitForResponse(responseFutureHolder[0], "Assign role to user");

            // Clean up
            this.mqttClient.disconnect(brokerUrl);

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
            // Use array holder to make it effectively final for use in lambda
            final CompletableFuture<String>[] responseFutureHolder = new CompletableFuture[]{new CompletableFuture<>()};
            this.mqttClient.setCallback(brokerUrl, new MqttCallback() {
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

            this.mqttClient.subscribe(brokerUrl, RESPONSE_TOPIC);

            // Delete user
            MosquittoCommand.Command deleteUserCmd = new MosquittoCommand.Command();
            deleteUserCmd.setCommand("deleteClient");
            deleteUserCmd.setUsername(username);
            publishCommand(this.mqttClient, deleteUserCmd);
            waitForResponse(responseFutureHolder[0], "Delete user");

            // Delete role
            responseFutureHolder[0] = new CompletableFuture<>();
            MosquittoCommand.Command deleteRoleCmd = new MosquittoCommand.Command();
            deleteRoleCmd.setCommand("deleteRole");
            deleteRoleCmd.setRolename(roleName);
            publishCommand(this.mqttClient, deleteRoleCmd);
            waitForResponse(responseFutureHolder[0], "Delete role");

            this.mqttClient.disconnect(brokerUrl);

            monitor.info("Successfully removed Mosquitto user and role");
            return Result.success();

        } catch (Exception e) {
            monitor.warning("Failed to remove Mosquitto user and role: " + e.getMessage(), e);
            return Result.failure("Failed to remove Mosquitto user: " + e.getMessage());
        }
    }

    private void publishCommand(OpcUaMqttClient client, MosquittoCommand.Command command) throws Exception {
        MosquittoCommand wrapper = new MosquittoCommand(List.of(command));
        String json = objectMapper.writeValueAsString(wrapper);

        monitor.debug("Publishing command to " + CONTROL_TOPIC + ": " + json);

        MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
        message.setQos(1); // At least once delivery
        client.publish(brokerUrl, CONTROL_TOPIC, message.getPayload());
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
