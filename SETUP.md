# Setup and Running

This guide covers installation, configuration, seeding, and runtime operations for the Industrial Connector solution.

If you are looking for the architecture overview and extension design, see [README.md](README.md).

---

## Project Configuration

Configuration for the `provider-connector-qna` is split into two locations depending on the run mode:

| Location | Used for |
|----------|----------|
| `deployment/assets/env/provider_connector_qna.env` | **Docker Compose** run – service hostnames match container names (e.g. `mosquitto-dynsec`, `identityhub-provider`) |
| `deployment/assets/env_local/provider_connector_qna.env` | **Local (host) run** – all hostnames resolve to `localhost` |

The key flags you can toggle in either file are:

| Property | Default | Description |
|----------|---------|-------------|
| `edc.industrial.connector.extension.enabled` | `true` | Master switch for the entire Industrial Connector |
| `edc.industrial.connector.wss.enabled` | `false` | `false` → local mode; `true` → WebSocket / firewall-traversal mode |
| `edc.industrial.connector.cert.auth.enabled` | `false` | `true` enables TLS/certificate-based MQTT authentication |
| `edc.industrial.connector.pki.endpoint.url` | — | URL of the PKI service (required when cert auth is enabled) |
| `edc.industrial.connector.pki.endpoint.key` | — | API key for the PKI service |
| `edc.opcua.mqtt.broker.url` | `tcp://localhost:1883` | MQTT broker URL (`tcp://` for plain, `ssl://` for TLS) |

---

## Running Locally (Host)

### Prerequisites

- JDK 17+
- Docker (for dependency containers)
- `newman` CLI (`npm install -g newman`) for data seeding

### Step 1 – Start Infrastructure Dependencies

Run the following script to start the three required infrastructure services as Docker containers:

```bash
./run-local-dependencies.sh
```

This script will prompt you to choose between:

- **Option 1 – Plain MQTT (port 1883):** Starts Mosquitto using the standard config at `deployment/mosquitto-dynsec/config/`.
- **Option 2 – TLS MQTT (ports 1883 and 8883):** Starts Mosquitto using the TLS config at `deployment/mosquitto-dynsec/config-cert/` and mounts certificates from `deployment/mosquitto-dynsec/certs/`.

In both cases the script also starts:

| Container | Image | Port | Purpose |
|-----------|-------|------|---------|
| `mosquitto-dynsec` | `eclipse-mosquitto:2` | `1883` (plain) / `8883` (TLS) | MQTT broker with Dynamic Security plugin |
| `opcua-server` | `ghcr.io/umati/sample-server:main` | `4840` | Sample OPC UA server exposing machine data |
| `nginx` | `nginx` | `9876` | Serves DID documents for identity resolution |

### Step 2 – Build the Project

```bash
./gradlew build
```

### Step 3 – Launch the Dataspace Runtimes

Start each EDC runtime using its environment file from `deployment/assets/env_local/`. Refer to the individual launcher configurations in the `launchers/` directory for JAR arguments.

### Step 4 – Seed the Dataspace

```bash
./seed.sh       # seeds base dataspace assets, policies, and credentials
./seed-mqtt.sh  # seeds OPC UA MQTT assets to provider-qna and provider-manufacturing
```

`seed-mqtt.sh` uses the Postman collection `deployment/postman/MVD-OPCUAMQTT.postman_collection.json` via `newman` to register:
- OPC UA MQTT assets and policies on `provider-qna` (management API port `8191`)
- OPC UA MQTT assets and policies on `provider-manufacturing` (management API port `8291`)
- Linked catalog entries on the `provider-catalog-server` (management API port `8091`)

### Step 5 – Interact with the Dataspace

Once the runtimes are running and the seed scripts have completed, you can use the interactive script to test the full transfer flow **without** restarting anything or re-seeding:

```bash
./run-dataspace-interactive.sh
```

When prompted, select **option 2 – "Use existing setup"**. The script will skip Docker Compose and seed steps entirely and go straight to the interactive operations menu:

```
================================
Dataspace Interactive Menu
================================
1. Get Asset Catalog
2. Initiate Contract Negotiation
3. Check Contract Negotiation Status
4. Initiate Transfer
5. Get EDR Endpoint
6. Create Self-Signed Certificate
7. Create CSR (Certificate Signing Request)
8. Show Current Status
9. Exit
```

This lets you walk through the full dataspace flow — catalog query → contract negotiation → transfer initiation → EDR retrieval — against the locally running runtimes.

---

## Running with Docker Compose

### Step 0 – Rebuild Docker Images After Configuration Changes

> **Important:** Whenever you change any configuration file (e.g. `.env` files under `deployment/assets/env/`, `docker-compose.dataspace.yml`, or any source code), you must rebuild the Docker images before starting the dataspace. The build command must be run from the `deployment/` folder:

```bash
cd deployment
docker-compose -f docker-compose.dataspace.yml build
```

Skipping this step after a configuration change will cause the containers to run with stale settings. Once the build is complete, return to the project root to run the interactive script or start the stack manually.

### Step 1 – Start the Full Dataspace Interactively

The `run-dataspace-interactive.sh` script automates the entire Docker Compose lifecycle and provides an interactive menu for all dataspace operations:

```bash
./run-dataspace-interactive.sh
```

On first run it will ask whether to:
1. **Start fresh** – stops any existing containers, starts Docker Compose with the selected profile, and runs the full seed sequence.
2. **Use existing setup** – skip startup/seeding and go directly to the operations menu.

If starting fresh, it also asks which MQTT profile to use:
- **plain** – starts `mosquitto` service (port `1883`)
- **tls** – starts `mosquitto-tls` service (port `8883` with certificates)

The script then launches the following menu:

```
================================
Dataspace Interactive Menu
================================
1. Get Asset Catalog
2. Initiate Contract Negotiation
3. Check Contract Negotiation Status
4. Initiate Transfer
5. Get EDR Endpoint
6. Create Self-Signed Certificate
7. Create CSR (Certificate Signing Request)
8. Show Current Status
9. Exit
```

The menu guides you through the full transfer lifecycle sequentially. State (Asset ID, Policy ID, Contract IDs, Transfer ID, CSR) is preserved across steps within the same session.

### Step 2 – Transfer Workflow

#### Plain MQTT (no TLS)

1. **Get Asset Catalog** – queries the provider catalog and extracts asset and policy IDs.
2. **Initiate Contract Negotiation** – submits a contract offer to the provider.
3. **Check Contract Negotiation Status** – poll until state is `FINALIZED` and the Contract Agreement ID is extracted.
4. **Initiate Transfer** – submits an `MQTT-PUSH` transfer request. The connector reads OPC UA data and starts pushing to the configured topic.
5. **Get EDR Endpoint** – retrieves the MQTT broker URL, topic, username, and password from the EDR. Use these to subscribe to the data stream.

#### TLS / Certificate-Based MQTT

The same steps apply, plus the following must be performed **between steps 3 and 4**:

1. **Create Self-Signed Certificate** (menu option `6`) – calls the PKI service to generate a certificate for the consumer identity.
2. **Create CSR** (menu option `7`) – generates a CSR via the PKI service. The CSR PEM is stored in session state and automatically included in the transfer request body (option `4`).

The connector signs the CSR, creates a per-transfer MQTT ACL entry, and returns the signed certificate plus CA chain in the EDR so the consumer can connect to the broker over mutual TLS.

### Docker Compose Services

The `deployment/docker-compose.dataspace.yml` defines the following services:

| Service | Ports | Description |
|---------|-------|-------------|
| `identityhub-consumer` | `7080–7083`, `7085–7086` | Consumer identity hub (W3C DID, VC) |
| `identityhub-provider` | `7090–7093`, `7095–7096` | Provider identity hub |
| `issuer-service` | `10010–10015` | VC issuer (credential issuance) |
| `provider-catalog-server` | `8091–8092` | Provider catalog aggregator |
| `provider-connector-qna` | `8190–8195`, `8181`, `12001` | Industrial connector; also exposes WebSocket on `8181` (WSS mode) |
| `provider-connector-manufacturing` | `8290–8295`, `12002` | Standard provider connector |
| `consumer-connector` | `8080–8085`, `11001` | Consumer connector |
| `mosquitto` *(profile: plain)* | `1883` | MQTT broker, plain config |
| `mosquitto-tls` *(profile: tls)* | `8883` | MQTT broker, TLS config |
| `opcua-server` | `4840` | Sample OPC UA server |
| `nginx` | `9876` | DID document server |

---

## Monitoring the Data Stream

Once a transfer is active you can verify data is flowing by subscribing to the MQTT broker:

```bash
# Plain MQTT
mosquitto_sub -h localhost -p 1883 -u <username> -P <password> -t '<assetId>/#' -v

# TLS MQTT
mosquitto_sub -h localhost -p 8883 \
  --cafile deployment/mosquitto-dynsec/certs/ca-chain.cert.pem \
  --cert <consumer-cert.pem> \
  --key <consumer-key.pem> \
  -t '<assetId>/#' -v
```

The broker URL, topic, and credentials are all returned in the EDR (step 5 of the interactive menu).

---

## Troubleshooting

| Symptom | Likely Cause |
|---------|-------------|
| Transfer stays in `STARTING` | OPC UA server unreachable; check `edc.opcua.*` server URL settings |
| Transfer fails with "No CSR provided" | TLS auth is enabled but no CSR was generated before initiating the transfer |
| WebSocket transfer immediately fails | WSS client application is not connected; ensure it is running and connected to port `8181` |
| Mosquitto Dynamic Security error | Check admin credentials (`edc.opcua.mqtt.admin.*`) match the `dynamic-security.json` config |
| DID resolution failure | Ensure `nginx` container is running on port `9876` serving the correct `did.json` |

## RUNNING the Solution
For running, see [RUNNING.md](./docs/RUNNING.md).

