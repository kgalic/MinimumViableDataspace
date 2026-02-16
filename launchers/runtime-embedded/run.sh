#!/bin/sh
# Generate configuration properties file from environment variables
# EDC applications automatically load dataspaceconnector-configuration.properties

CONFIG_FILE=/app/dataspaceconnector-configuration.properties

# Write all edc.* and web.* environment variables to the properties file
env | grep -E "^(edc|web)\." > "$CONFIG_FILE"

echo "Generated configuration with $(wc -l < "$CONFIG_FILE") properties"

# Run the application
exec ./bin/runtime-embedded --log-level=${LOG_LEVEL:-debug}

