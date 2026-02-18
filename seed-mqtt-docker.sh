#!/bin/bash

#
# Combined seed script for Docker Compose dataspace setup
# Seeds identity data, participant contexts, and OPC UA via MQTT assets
#

set -e

# Configuration
API_KEY="c3VwZXItdXNlcg==.c3VwZXItc2VjcmV0LWtleQo="
MANAGEMENT_API_KEY="password"

# OPC UA Server Configuration
OPC_SERVER_URL="${OPC_SERVER_URL:-opc.tcp://opcua-server:4840}"
OPC_SERVER_USERNAME="${OPC_SERVER_USERNAME:-}"
OPC_SERVER_PASSWORD="${OPC_SERVER_PASSWORD:-}"

# MQTT Broker Configuration (container name for Docker network)
MQTT_BROKER_URL="${MQTT_BROKER_URL:-tcp://emqx:1883}"

# Docker network host mappings (use localhost for host access, container names internally)
CONSUMER_IH_IDENTITY="http://localhost:7082"
CONSUMER_IH_CREDENTIALS="http://localhost:7081"
CONSUMER_MANAGEMENT="http://localhost:8081"

PROVIDER_IH_IDENTITY="http://localhost:7092"
PROVIDER_IH_CREDENTIALS="http://localhost:7091"
PROVIDER_CATALOG_MANAGEMENT="http://localhost:8091"
PROVIDER_QNA_MANAGEMENT="http://localhost:8191"
PROVIDER_MFG_MANAGEMENT="http://localhost:8291"

ISSUER_IDENTITY="http://localhost:10015"
ISSUER_ADMIN="http://localhost:10013"

echo "=========================================="
echo "Seeding Dataspace (Docker Compose)"
echo "=========================================="
echo ""

###############################################
# WAIT FOR SERVICES
###############################################

echo "Waiting for services to be ready..."

wait_for_service() {
  local url=$1
  local name=$2
  local max_attempts=30
  local attempt=1

  while [ $attempt -le $max_attempts ]; do
    http_code=$(curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null)
    if [ "$http_code" -gt 0 ] && [ "$http_code" -lt 500 ]; then
      echo "✓ $name is ready (HTTP $http_code)"
      return 0
    fi
    echo "  Waiting for $name... (attempt $attempt/$max_attempts)"
    sleep 2attempt=$((attempt + 1))
  done

  echo "✗ $name failed to start"
  return 1
}

wait_for_service "$CONSUMER_IH_IDENTITY/api/identity/v1alpha/participants/" "Consumer IdentityHub"
wait_for_service "$PROVIDER_IH_IDENTITY/api/identity/v1alpha/participants/" "Provider IdentityHub"
wait_for_service "$ISSUER_IDENTITY/api/identity/v1alpha/participants/" "Issuer Service"
wait_for_service "$CONSUMER_MANAGEMENT/api/management/v3/assets" "Consumer Connector"
wait_for_service "$PROVIDER_QNA_MANAGEMENT/api/management/v3/assets" "Provider QnA Connector"
wait_for_service "$PROVIDER_MFG_MANAGEMENT/api/management/v3/assets" "Provider Manufacturing Connector"

echo ""

###############################################
# SEED ASSETS/POLICIES/CONTRACTS
###############################################

echo "Seeding assets, policies, and contract definitions..."

for url in "$PROVIDER_QNA_MANAGEMENT" "$PROVIDER_MFG_MANAGEMENT"
do
  newman run \
    --folder "Seed" \
    --env-var "HOST=$url" \
    ./deployment/postman/MVD.postman_collection.json > /dev/null
done

echo "✓ Assets, policies, and contracts seeded"

###############################################
# SEED CATALOG SERVER
###############################################

echo "Seeding linked assets to Catalog Server..."

newman run \
  --folder "Seed Catalog Server" \
  --env-var "HOST=http://localhost:8091" \
  --env-var "PROVIDER_QNA_DSP_URL=http://provider-connector-qna:8192" \
  --env-var "PROVIDER_MF_DSP_URL=http://provider-connector-manufacturing:8292" \
  ./deployment/postman/MVD.postman_collection.json > /dev/null

echo "✓ Catalog Server seeded"

###############################################
# SEED CONSUMER PARTICIPANT
###############################################

echo ""
echo "Creating consumer participant context in IdentityHub..."

DATA_CONSUMER=$(jq -n '{
  "roles":[],
  "serviceEndpoints":[
    {
      "type":"CredentialService",
      "serviceEndpoint":"http://identityhub-consumer:7081/api/credentials/v1/participants/ZGlkOndlYjpsb2NhbGhvc3QlM0E3MDgz",
      "id":"consumer-credentialservice-1"
    },
    {
      "type":"ProtocolEndpoint",
      "serviceEndpoint":"http://consumer-connector:8082/api/dsp",
      "id":"consumer-dsp"
    }
  ],
  "active":true,
  "participantId":"did:web:localhost%3A7083",
  "did":"did:web:localhost%3A7083",
  "key":{
    "keyId":"did:web:localhost%3A7083#key-1",
    "privateKeyAlias":"key-1",
    "keyGeneratorParams":{
      "algorithm":"EdDSA"
    }
  }
}')

resp=$(curl -sS --fail --location "$CONSUMER_IH_IDENTITY/api/identity/v1alpha/participants/" \
  --header 'Content-Type: application/json' \
  --header "x-api-key: $API_KEY" \
  --data "$DATA_CONSUMER")

clientSecret=$(printf '%s' "$resp" | jq -er '.clientSecret')

SECRETS_DATA=$(jq -n --arg secret "$clientSecret" \
'{
  "@context" : {
    "edc" : "https://w3id.org/edc/v0.0.1/ns/"
  },
  "@type" : "https://w3id.org/edc/v0.0.1/ns/Secret",
  "@id" : "did:web:localhost%3A7083-sts-client-secret",
  "https://w3id.org/edc/v0.0.1/ns/value": "\($secret)"
}')

curl -sL -X POST "$CONSUMER_MANAGEMENT/api/management/v3/secrets" \
  -H "x-api-key: $MANAGEMENT_API_KEY" \
  -H "Content-Type: application/json" \
  -d "$SECRETS_DATA"

echo "✓ Consumer participant created"

###############################################
# SEED PROVIDER PARTICIPANT
###############################################

echo ""
echo "Creating provider participant context in IdentityHub..."

DATA_PROVIDER=$(jq -n '{
  "roles":[],
  "serviceEndpoints":[
    {
      "type":"CredentialService",
      "serviceEndpoint":"http://identityhub-provider:7091/api/credentials/v1/participants/ZGlkOndlYjpsb2NhbGhvc3QlM0E3MDkz",
      "id":"provider-credentialservice-1"
    },
    {
      "type":"ProtocolEndpoint",
      "serviceEndpoint":"http://provider-catalog-server:8092/api/dsp",
      "id":"provider-catalogserver-dsp"
    }
  ],
  "active":true,
  "participantId":"did:web:localhost%3A7093",
  "did":"did:web:localhost%3A7093",
  "key":{
    "keyId":"did:web:localhost%3A7093#key-1",
    "privateKeyAlias":"key-1",
    "keyGeneratorParams":{
      "algorithm":"EdDSA"
    }
  }
}')

resp=$(curl -sS --fail --location "$PROVIDER_IH_IDENTITY/api/identity/v1alpha/participants/" \
  --header 'Content-Type: application/json' \
  --header "x-api-key: $API_KEY" \
  --data "$DATA_PROVIDER")

clientSecret=$(printf '%s' "$resp" | jq -er '.clientSecret')

SECRETS_DATA=$(jq -n --arg secret "$clientSecret" \
'{
  "@context" : {
    "edc" : "https://w3id.org/edc/v0.0.1/ns/"
  },
  "@type" : "https://w3id.org/edc/v0.0.1/ns/Secret",
  "@id" : "did:web:localhost%3A7093-sts-client-secret",
  "https://w3id.org/edc/v0.0.1/ns/value": "\($secret)"
}')

curl -sL -X POST "$PROVIDER_CATALOG_MANAGEMENT/api/management/v3/secrets" \
  -H "x-api-key: $MANAGEMENT_API_KEY" \
  -H "Content-Type: application/json" \
  -d "$SECRETS_DATA"

curl -sL -X POST "$PROVIDER_QNA_MANAGEMENT/api/management/v3/secrets" \
  -H "x-api-key: $MANAGEMENT_API_KEY" \
  -H "Content-Type: application/json" \
  -d "$SECRETS_DATA"

curl -sL -X POST "$PROVIDER_MFG_MANAGEMENT/api/management/v3/secrets" \
  -H "x-api-key: $MANAGEMENT_API_KEY" \
  -H "Content-Type: application/json" \
  -d "$SECRETS_DATA"

echo "✓ Provider participant created"

###############################################
# SEED ISSUER
###############################################

echo ""
echo "Creating dataspace issuer..."

DATA_ISSUER=$(jq -n '{
  "roles":["admin"],
  "serviceEndpoints":[
    {
       "type": "IssuerService",
       "serviceEndpoint": "http://issuer-service:10012/api/issuance/v1alpha/participants/ZGlkOndlYjpsb2NhbGhvc3QlM0ExMDEwMA==",
       "id": "issuer-service-1"
    }
  ],
  "active": true,
  "participantId": "did:web:localhost%3A10100",
  "did": "did:web:localhost%3A10100",
  "key":{
      "keyId": "did:web:localhost%3A10100#key-1",
      "privateKeyAlias": "key-1",
      "keyGeneratorParams":{
        "algorithm": "EdDSA"
      }}
}')

curl -s --location "$ISSUER_IDENTITY/api/identity/v1alpha/participants/" \
  --header 'Content-Type: application/json' \
  --header "x-api-key: $API_KEY" \
  --data "$DATA_ISSUER"

newman run \
  --folder "Seed Issuer" \
  --env-var "ISSUER_ADMIN_URL=$ISSUER_ADMIN" \
  --env-var "CONSUMER_ID=did:web:localhost%3A7083" \
  --env-var "CONSUMER_NAME=MVD Consumer Participant" \
  --env-var "PROVIDER_ID=did:web:localhost%3A7093" \
  --env-var "PROVIDER_NAME=MVD Provider Participant" \
  ./deployment/postman/MVD.postman_collection.json

echo "✓ Issuer created"

###############################################
# SEED OPC UA VIA MQTT ASSETS
###############################################
echo ""
echo "Seeding OPC UA via MQTT assets to dataspace (Docker)..."
echo "OPC UA Server URL: $OPC_SERVER_URL"
echo "MQTT Broker URL: $MQTT_BROKER_URL"

## Seed OPC UA MQTT assets to "provider-qna"
echo "Seeding Provider QnA with OPC UA MQTT assets..."
newman run \
  --folder "Seed OPC UA MQTT Provider QnA" \
  --env-var "HOST=$PROVIDER_QNA_MANAGEMENT" \
  --env-var "OPC_SERVER_URL=$OPC_SERVER_URL" \
  --env-var "OPC_SERVER_USERNAME=$OPC_SERVER_USERNAME" \
  --env-var "OPC_SERVER_PASSWORD=$OPC_SERVER_PASSWORD" \
  --env-var "MQTT_BROKER_URL=$MQTT_BROKER_URL" \
  ./deployment/postman/MVD-OPCUAMQTT.postman_collection.json

echo "✓ Provider QnA OPC UA MQTT assets seeded"

## Seed OPC UA MQTT assets to "provider-manufacturing"
echo "Seeding Provider Manufacturing with OPC UA MQTT assets..."
newman run \
  --folder "Seed OPC UA MQTT Provider Manufacturing" \
  --env-var "HOST=$PROVIDER_MFG_MANAGEMENT" \
  --env-var "OPC_SERVER_URL=$OPC_SERVER_URL" \
  --env-var "OPC_SERVER_USERNAME=$OPC_SERVER_USERNAME" \
  --env-var "OPC_SERVER_PASSWORD=$OPC_SERVER_PASSWORD" \
  --env-var "MQTT_BROKER_URL=$MQTT_BROKER_URL" \
  ./deployment/postman/MVD-OPCUAMQTT.postman_collection.json

echo "✓ Provider Manufacturing OPC UA MQTT assets seeded"

## Seed linked OPC UA MQTT assets to Catalog Server
echo "Seeding Catalog Server with linked OPC UA MQTT assets..."
newman run \
  --folder "Seed OPC UA MQTT Catalog Server" \
  --env-var "HOST=$PROVIDER_CATALOG_MANAGEMENT" \
  --env-var "PROVIDER_QNA_DSP_URL=http://provider-connector-qna:8192" \
  --env-var "PROVIDER_MF_DSP_URL=http://provider-connector-manufacturing:8292" \
  --env-var "MQTT_BROKER_URL=$MQTT_BROKER_URL" \
  ./deployment/postman/MVD-OPCUAMQTT.postman_collection.json

echo "✓ Catalog Server OPC UA MQTT assets seeded"

echo ""
echo "=========================================="
echo "Dataspace seeded successfully!"
echo "=========================================="


###############################################
# SUMMARY
###############################################

echo ""
echo "=========================================="
echo "Dataspace seeding completed successfully!"
echo "=========================================="
echo ""
echo "Services:"
echo "  - Consumer Connector:      http://localhost:8081 (management)"
echo "  - Provider QnA:            http://localhost:8191 (management)"
echo "  - Provider Manufacturing:  http://localhost:8291 (management)"
echo "  - Provider Catalog Server: http://localhost:8091 (management)"
echo "  - Consumer IdentityHub:    http://localhost:7082 (identity)"
echo "  - Provider IdentityHub:    http://localhost:7092 (identity)"
echo "  - Issuer Service:          http://localhost:10013 (admin)"
echo "  - EMQX Dashboard:          http://localhost:18083"
echo "  - OPC UA Server:           $OPC_SERVER_URL"
echo ""
echo "OPC UA MQTT Assets:"
echo "  - opcua-mqtt-asset-qna-001 (Provider QnA)"
echo "  - opcua-mqtt-asset-mfg-001 (Provider Manufacturing)"
echo ""
echo "Next steps:"
echo "  1. Monitor MQTT: mosquitto_sub -h localhost -p 1883 -t '#' -v"
echo "  2. Check catalog: curl http://localhost:8081/api/management/v3/catalog/request"
echo ""