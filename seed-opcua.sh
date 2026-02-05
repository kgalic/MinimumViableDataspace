#!/bin/bash

## This script seeds OPC UA assets to the MVD dataspace
## It assumes the dataspace is already running and seeded with the main seed.sh script

set -e

# OPC UA Server Configuration
OPC_SERVER_URL="${OPC_SERVER_URL:-opc.tcp://localhost:4840}"
OPC_SERVER_USERNAME="${OPC_SERVER_USERNAME:-}"
OPC_SERVER_PASSWORD="${OPC_SERVER_PASSWORD:-}"

echo "Seeding OPC UA assets to dataspace..."
echo "OPC UA Server URL: $OPC_SERVER_URL"

## Seed OPC UA assets to "provider-qna"
newman run \
  --folder "Seed OPC UA Provider QnA" \
  --env-var "HOST=http://127.0.0.1:8191" \
  --env-var "OPC_SERVER_URL=$OPC_SERVER_URL" \
  --env-var "OPC_SERVER_USERNAME=$OPC_SERVER_USERNAME" \
  --env-var "OPC_SERVER_PASSWORD=$OPC_SERVER_PASSWORD" \
  ./deployment/postman/MVD-OPCUA.postman_collection.json > /dev/null

## Seed OPC UA assets to "provider-manufacturing"
newman run \
  --folder "Seed OPC UA Provider Manufacturing" \
  --env-var "HOST=http://127.0.0.1:8291" \
  --env-var "OPC_SERVER_URL=$OPC_SERVER_URL" \
  --env-var "OPC_SERVER_USERNAME=$OPC_SERVER_USERNAME" \
  --env-var "OPC_SERVER_PASSWORD=$OPC_SERVER_PASSWORD" \
  ./deployment/postman/MVD-OPCUA.postman_collection.json > /dev/null

## Seed linked OPC UA assets to Catalog Server
newman run \
  --folder "Seed OPC UA Catalog Server" \
  --env-var "HOST=http://127.0.0.1:8091" \
  --env-var "PROVIDER_QNA_DSP_URL=http://localhost:8192" \
  --env-var "PROVIDER_MF_DSP_URL=http://localhost:8292" \
  ./deployment/postman/MVD-OPCUA.postman_collection.json > /dev/null

echo "OPC UA assets seeded successfully!"
