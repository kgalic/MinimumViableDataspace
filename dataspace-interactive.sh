#!/bin/bash

################################################################################
# Dataspace Interactive Management Script
# Orchestrates Docker Compose startup, seeding, and provides interactive
# dataspace operations menu for catalog, negotiation, transfer, and EDR
################################################################################

set -e

# Script directory and paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOYMENT_DIR="${SCRIPT_DIR}/deployment"
COMPOSE_FILE="${DEPLOYMENT_DIR}/docker-compose.dataspace.yml"
SEED_SCRIPT="${SCRIPT_DIR}/seed-mqtt-docker.sh"

# API Configuration
API_KEY="password"
HEADER_API_KEY="X-Api-Key"
HEADER_CONTENT_TYPE="Content-Type: application/json"

# Connector URLs (local access)
CONSUMER_MANAGEMENT="http://localhost:8081"
PROVIDER_QNA_MANAGEMENT="http://localhost:8191"
PROVIDER_QNA_DSP="http://localhost:8192"

# IDs and other environment variables (defaults from Postman environment)
CONSUMER_ID="did:web:localhost%3A7083"
PROVIDER_ID="did:web:localhost%3A7093"
PROVIDER_QNA_DSP_INTERNAL="http://provider-connector-qna:8192"

# Runtime variables
ASSET_ID=""
POLICY_ID=""
FULL_POLICY=""
CONTRACT_NEGOTIATION_ID=""
CONTRACT_AGREEMENT_ID=""
TRANSFER_PROCESS_ID=""

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

################################################################################
# Helper Functions
################################################################################

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

wait_for_service() {
    local url=$1
    local name=$2
    local max_attempts=60
    local attempt=0

    log_info "Waiting for $name ($url)..."

    while [ $attempt -lt $max_attempts ]; do
        if curl -s -f -o /dev/null -w "%{http_code}" "$url" 2>/dev/null | grep -q "200"; then
            log_success "$name is ready"
            return 0
        fi

        attempt=$((attempt + 1))
        echo -n "."
        sleep 1
    done

    log_warning "$name did not respond within timeout"
    return 1
}

# Check if required tools are installed
check_requirements() {
    local required_tools=("docker" "docker-compose" "curl" "jq")

    for tool in "${required_tools[@]}"; do
        if ! command -v "$tool" &> /dev/null; then
            log_error "$tool is not installed or not in PATH"
            exit 1
        fi
    done

    log_success "All required tools are available"
}

# Pretty print JSON response
pretty_json() {
    echo "$1" | jq '.' 2>/dev/null || echo "$1"
}

################################################################################
# Docker Compose Management
################################################################################

start_dataspace() {
    log_info "Starting Dataspace Docker Compose..."

    if [ ! -f "$COMPOSE_FILE" ]; then
        log_error "Docker Compose file not found: $COMPOSE_FILE"
        exit 1
    fi

    cd "$DEPLOYMENT_DIR"

    # Start docker compose
    log_info "Building and starting services..."
    docker-compose -f docker-compose.dataspace.yml up -d

    cd "$SCRIPT_DIR"
    echo $SCRIPT_DIR

    log_success "Dataspace is up and running"
}

stop_dataspace() {
    log_info "Stopping Dataspace Docker Compose..."
    cd "$DEPLOYMENT_DIR"
    docker-compose -f docker-compose.dataspace.yml down
    cd "$SCRIPT_DIR"
    log_success "Dataspace stopped"
}

################################################################################
# Seeding
################################################################################

run_seed_script() {
    echo "Running seed script..."

    # Execute the seed script
    bash ./seed-mqtt-docker.sh

    if [ $? -eq 0 ]; then
        echo "✓ Seed script completed successfully"
        return 0
    else
        echo "✗ Seed script failed"
        return 1
    fi
}

################################################################################
# API Operations
################################################################################

# 1. Get Asset Catalog
get_asset_catalog() {
    log_info "Querying Asset Catalog from Provider QnA..."

    local request_body='{
        "@context": [
            "https://w3id.org/edc/connector/management/v0.0.1"
        ],
        "@type": "CatalogRequest",
        "counterPartyAddress": "'$PROVIDER_QNA_DSP_INTERNAL'/api/dsp",
        "counterPartyId": "'$PROVIDER_ID'",
        "protocol": "dataspace-protocol-http"
    }'

    local response=$(curl -s -X POST \
        -H "$HEADER_CONTENT_TYPE" \
        -H "$HEADER_API_KEY: $API_KEY" \
        -d "$request_body" \
        "$CONSUMER_MANAGEMENT/api/management/v3/catalog/request")

    echo ""
    pretty_json "$response"

    # Extract asset and policy information for next steps
    if echo "$response" | jq -e '.["dcat:dataset"]' > /dev/null 2>&1; then
        if [ $(echo "$response" | jq '.["dcat:dataset"] | length') -gt 0 ]; then
            local dataset=$(echo "$response" | jq '.["dcat:dataset"][0]')
            ASSET_ID=$(echo "$dataset" | jq -r '.["@id"]')

            local policy=$(echo "$dataset" | jq '.["odrl:hasPolicy"]')
            POLICY_ID=$(echo "$policy" | jq -r '.["@id"]')

            # Add required assigner and target fields to policy for contract negotiation
            FULL_POLICY=$(echo "$policy" | jq -c '. + {"assigner": "'$PROVIDER_ID'", "target": "'$ASSET_ID'"}')

            log_success "Asset ID extracted: $ASSET_ID"
            log_success "Policy ID extracted: $POLICY_ID"

            echo ""
            log_info "Asset Details:"
            echo "$dataset" | jq '.'
        fi
    else
        log_warning "No datasets found in catalog response"
    fi
}

# 2. Initiate Contract Negotiation
initiate_contract_negotiation() {
    if [ -z "$ASSET_ID" ] || [ -z "$POLICY_ID" ]; then
        log_error "Asset ID or Policy ID not set. Please run 'Get Asset Catalog' first."
        return 1
    fi

    log_info "Initiating Contract Negotiation..."
    log_info "Using Asset ID: $ASSET_ID"
    log_info "Using Policy ID: $POLICY_ID"

    local request_body='{
        "@context": [
            "https://w3id.org/edc/connector/management/v0.0.1"
        ],
        "@type": "ContractRequest",
        "counterPartyAddress": "'$PROVIDER_QNA_DSP_INTERNAL'/api/dsp",
        "counterPartyId": "'$PROVIDER_ID'",
        "protocol": "dataspace-protocol-http",
        "policy": '$FULL_POLICY'
    }'

    local response=$(curl -s -X POST \
        -H "$HEADER_CONTENT_TYPE" \
        -H "$HEADER_API_KEY: $API_KEY" \
        -d "$request_body" \
        "$CONSUMER_MANAGEMENT/api/management/v3/contractnegotiations")

    echo ""
    pretty_json "$response"

    # Extract contract negotiation ID
    if echo "$response" | jq -e '.["@id"]' > /dev/null 2>&1; then
        CONTRACT_NEGOTIATION_ID=$(echo "$response" | jq -r '.["@id"]')
        log_success "Contract Negotiation ID: $CONTRACT_NEGOTIATION_ID"
    else
        log_error "Failed to extract Contract Negotiation ID from response"
    fi
}

# 3. Check Contract Negotiation Status
check_contract_negotiation_status() {
    if [ -z "$CONTRACT_NEGOTIATION_ID" ]; then
        log_error "Contract Negotiation ID not set. Please run 'Initiate Contract Negotiation' first."
        return 1
    fi

    log_info "Checking Contract Negotiation Status..."
    log_info "Contract Negotiation ID: $CONTRACT_NEGOTIATION_ID"

    local response=$(curl -s -X GET \
        -H "$HEADER_API_KEY: $API_KEY" \
        "$CONSUMER_MANAGEMENT/api/management/v3/contractnegotiations/$CONTRACT_NEGOTIATION_ID")

    echo ""
    pretty_json "$response"

    # Extract contract agreement ID if negotiation is finalized
    if echo "$response" | jq -e '.contractAgreementId' > /dev/null 2>&1; then
        CONTRACT_AGREEMENT_ID=$(echo "$response" | jq -r '.contractAgreementId')
        log_success "Contract Agreement ID: $CONTRACT_AGREEMENT_ID"

        local state=$(echo "$response" | jq -r '.state')
        log_success "Negotiation State: $state"
    else
        log_warning "No Contract Agreement ID yet. Negotiation might still be pending."

        if echo "$response" | jq -e '.state' > /dev/null 2>&1; then
            local state=$(echo "$response" | jq -r '.state')
            log_info "Current State: $state"
        fi
    fi
}

# 4. Initiate Transfer
initiate_transfer() {
    if [ -z "$CONTRACT_AGREEMENT_ID" ] || [ -z "$ASSET_ID" ]; then
        log_error "Contract Agreement ID or Asset ID not set. Please complete negotiation first."
        return 1
    fi

    log_info "Initiating Data Transfer..."
    log_info "Contract Agreement ID: $CONTRACT_AGREEMENT_ID"
    log_info "Asset ID: $ASSET_ID"

    local request_body='{
        "@context": [
            "https://w3id.org/edc/connector/management/v0.0.1"
        ],
        "@type": "TransferRequest",
        "counterPartyAddress": "'$PROVIDER_QNA_DSP_INTERNAL'/api/dsp",
        "contractId": "'$CONTRACT_AGREEMENT_ID'",
        "assetId": "'$ASSET_ID'",
        "protocol": "dataspace-protocol-http",
        "transferType": "MQTT-PUSH",
        "callbackAddresses": [],
        "dataDestination": {
            "@type": "DataAddress",
            "type": "MQTT"
        }
    }'

    local response=$(curl -s -X POST \
        -H "$HEADER_CONTENT_TYPE" \
        -H "$HEADER_API_KEY: $API_KEY" \
        -d "$request_body" \
        "$CONSUMER_MANAGEMENT/api/management/v3/transferprocesses")

    echo ""
    pretty_json "$response"

    # Extract transfer process ID
    if echo "$response" | jq -e '.["@id"]' > /dev/null 2>&1; then
        TRANSFER_PROCESS_ID=$(echo "$response" | jq -r '.["@id"]')
        log_success "Transfer Process ID: $TRANSFER_PROCESS_ID"
    else
        log_error "Failed to extract Transfer Process ID from response"
    fi
}

# 5. Get EDR (Endpoint Data Reference)
get_edr_endpoint() {
    if [ -z "$TRANSFER_PROCESS_ID" ]; then
        log_error "Transfer Process ID not set. Please run 'Initiate Transfer' first."
        return 1
    fi

    log_info "Retrieving EDR (Endpoint Data Reference)..."
    log_info "Transfer Process ID: $TRANSFER_PROCESS_ID"

    local response=$(curl -s -X GET \
        -H "$HEADER_API_KEY: $API_KEY" \
        "$CONSUMER_MANAGEMENT/api/management/v3/edrs/$TRANSFER_PROCESS_ID/dataaddress")

    echo ""
    pretty_json "$response"

    # Extract MQTT endpoint information
    if echo "$response" | jq -e '.endpoint' > /dev/null 2>&1; then
        local endpoint=$(echo "$response" | jq -r '.endpoint')
        log_success "MQTT Broker Endpoint: $endpoint"
    fi

    if echo "$response" | jq -e '.topic' > /dev/null 2>&1; then
        local topic=$(echo "$response" | jq -r '.topic')
        log_success "MQTT Topic: $topic"
    fi
}

################################################################################
# Interactive Menu
################################################################################

show_menu() {
    echo ""
    echo "================================"
    echo "Dataspace Interactive Menu"
    echo "================================"
    echo "1. Get Asset Catalog"
    echo "2. Initiate Contract Negotiation"
    echo "3. Check Contract Negotiation Status"
    echo "4. Initiate Transfer"
    echo "5. Get EDR Endpoint"
    echo "6. Show Current Status"
    echo "7. Exit"
    echo "================================"
    echo ""
}

show_status() {
    echo ""
    echo "Current Status:"
    echo "  Asset ID: ${ASSET_ID:-(not set)}"
    echo "  Policy ID: ${POLICY_ID:-(not set)}"
    echo "  Contract Negotiation ID: ${CONTRACT_NEGOTIATION_ID:-(not set)}"
    echo "  Contract Agreement ID: ${CONTRACT_AGREEMENT_ID:-(not set)}"
    echo "  Transfer Process ID: ${TRANSFER_PROCESS_ID:-(not set)}"
    echo ""
}

interactive_menu() {
    while true; do
        show_menu
        read -p "Select an option [1-7]: " choice

        case $choice in
            1)
                get_asset_catalog
                ;;
            2)
                initiate_contract_negotiation
                ;;
            3)
                check_contract_negotiation_status
                ;;
            4)
                initiate_transfer
                ;;
            5)
                get_edr_endpoint
                ;;
            6)
                show_status
                ;;
            7)
                log_info "Exiting interactive menu"
                break
                ;;
            *)
                log_error "Invalid option. Please select 1-7."
                ;;
        esac
    done
}

################################################################################
# Main Execution
################################################################################

main() {
    log_info "Starting Dataspace Interactive Management"
    echo ""

    # Check requirements
    check_requirements

    # Ask user if they want to start fresh or use existing setup
    echo ""
    echo "Would you like to:"
    echo "1. Start fresh (restart Docker Compose and run seed script)"
    echo "2. Use existing setup (skip Docker Compose and seed)"
    read -p "Select [1-2]: " setup_choice

    case $setup_choice in
        1)
            # Check if containers are already running
            if docker-compose -f "$COMPOSE_FILE" ps 2>/dev/null | grep -q "Up"; then
                log_warning "Docker Compose containers are already running. Stopping them first..."
                stop_dataspace
                sleep 5
            fi

            start_dataspace
            echo ""
            sleep 10
            run_seed_script
            ;;
        2)
            log_info "Skipping Docker Compose startup and seed script"
            ;;
        *)
            log_error "Invalid choice"
            exit 1
            ;;
    esac

    # Launch interactive menu
    log_success "Ready to interact with the dataspace"
    interactive_menu

    log_success "Dataspace Interactive session completed"
}

# Run main function
main

