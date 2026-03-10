package org.eclipse.edc.opcuamqtt.security;

import org.eclipse.edc.spi.result.Result;

/**
 * Service for managing Mosquitto Dynamic Security plugin operations.
 * Handles creation of users, roles, permissions, and role assignments.
 */
public interface MosquittoSecurityService {

    /**
     * Creates a complete user setup with role and permissions for a specific topic.
     * This includes:
     * 1. Creating the user with credentials
     * 2. Creating a role
     * 3. Adding subscribe permissions to the role
     * 4. Adding publishClientReceive permissions to the role
     * 5. Assigning the role to the user
     *
     * @param topic the MQTT topic pattern (e.g., "factory/companyA/telemetry/#")
     * @param transferId unique identifier for this transfer (used to generate unique names)
     * @return Result containing the generated credentials or failure
     */
    Result<MosquittoCredentials> createUserWithPermissions(String topic, String transferId);

    /**
     * Creates a complete user setup with role and permissions for a specific topic.
     * This includes:
     * 1. Creating the user that will authenticate via certificate
     * 2. Creating a role
     * 3. Adding subscribe permissions to the role
     * 4. Adding publishClientReceive permissions to the role
     * 5. Assigning the role to the user
     *
     * @param topic the MQTT topic pattern (e.g., "factory/companyA/telemetry/#")
     * @param transferId unique identifier for this transfer (used to generate unique names)
     * @return Result containing the generated credentials or failure
     */
    Result<MosquittoCredentials> createUserWithPermissions(String username, String topic, String transferId);

    /**
     * Removes a user and associated role.
     *
     * @param username the username to remove
     * @param roleName the role name to remove
     * @return Result indicating success or failure
     */
    Result<Void> removeUserAndRole(String username, String roleName);
}
