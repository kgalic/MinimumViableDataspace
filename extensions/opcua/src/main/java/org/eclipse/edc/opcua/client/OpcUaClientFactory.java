
package org.eclipse.edc.opcua.client;

import org.eclipse.edc.opcua.model.OpcUaCredentials;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.sdk.client.api.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class OpcUaClientFactory {


    /**
     * Create and connect an OpcUaClient based on the provided credentials.
     * If security policy/mode are absent, this will attempt SecurityPolicy.None and Anonymous.
     */
    public CompletableFuture<OpcUaClient> connect(OpcUaCredentials c) {
        Objects.requireNonNull(c, "credentials");
        Objects.requireNonNull(c.getServerUrl(), "serverUrl");

        return DiscoveryClient.getEndpoints(c.getServerUrl())
                .thenApply(endpoints -> {
                    Optional<EndpointDescription> match = endpoints.stream()
                            .filter(e -> {
                                boolean policyMatches = c.getSecurityPolicy() == null ||
                                        e.getSecurityPolicyUri().endsWith(c.getSecurityPolicy());
                                boolean modeMatches = c.getMessageSecurityMode() == null ||
                                        e.getSecurityMode().name().equalsIgnoreCase(c.getMessageSecurityMode());
                                return policyMatches && modeMatches;
                            })
                            .findFirst();

                    if (match.isEmpty()) {
                        // fallback to SecurityPolicy.None
                        match = endpoints.stream()
                                .filter(e -> SecurityPolicy.None.getUri().equals(e.getSecurityPolicyUri()))
                                .findFirst();
                    }

                    return match.orElseThrow(() -> new IllegalStateException("No matching OPC UA endpoint found for " + c.getServerUrl()));
                })
                .thenCompose(endpoint -> {
                    var builder = OpcUaClientConfig.builder()
                            .setEndpoint(endpoint);

                    if (c.hasUsername() && c.hasPassword()) {
                        builder.setIdentityProvider(new UsernameProvider(c.getUsername(), c.getPassword()));
                    } else {
                        builder.setIdentityProvider(new AnonymousProvider());
                    }

                    // If explicit security mode provided but not present in endpoint above, caller should adjust policy/mode.
                    if (c.getMessageSecurityMode() != null) {
                        try {
                            MessageSecurityMode.valueOf(c.getMessageSecurityMode());
                        } catch (IllegalArgumentException ignored) {
                            // do nothing, Milo will use the endpoint's mode as-is
                        }
                    }

                    try {
                        OpcUaClient client = OpcUaClient.create(builder.build());
                        return client.connect().orTimeout(10, TimeUnit.SECONDS)
                                .thenApply(x -> client);
                    } catch (UaException e) {
                        return CompletableFuture.failedFuture(e);
                    }
                });
    }
}