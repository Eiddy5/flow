# Plugins path default to pwd & must be exported as env var
FLOW_PLUGINS_PATH=${FLOW_PLUGINS_PATH:-"$(dirname "$0")/plugins"}
export FLOW_PLUGINS_PATH=${FLOW_PLUGINS_PATH}

# Flow configuration env vars
FLOW_CONFIGURATION_PATH=${FLOW_CONFIGURATION_PATH:-"$(dirname "$0")/confs"}
if [ "${FLOW_CONFIGURATION}" ]; then
    echo "${FLOW_CONFIGURATION}" > "${FLOW_CONFIGURATION_PATH}/application.yml"
    export MICRONAUT_CONFIG_FILES="${FLOW_CONFIGURATION_PATH}/application.yml"
fi

# Opens java.nio due to https://github.com/snowflakedb/snowflake-jdbc/issues/589
# Opens java.util due to https://github.com/Azure/azure-sdk-for-java/issues/27806
# Opens java.lang due to upstream issue #1755, see https://github.com/micronaut-projects/micronaut-core/issues/9573
JAVA_ADD_OPENS="--add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED"

# Java options that Flow engineers think are best for Flow, they should be added before JAVA_OPTS so they are overridable:
# -XX:MaxRAMPercentage=50.0: configure max heap to 50% of available RAM (default 25%)
FLOW_JAVA_OPTS="-XX:MaxRAMPercentage=50.0"

# Exec
exec java ${FLOW_JAVA_OPTS} ${JAVA_OPTS} ${JAVA_ADD_OPENS} --enable-native-access=ALL-UNNAMED -jar "$0" "$@"
exit 127