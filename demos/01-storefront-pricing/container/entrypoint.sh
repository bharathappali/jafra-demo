#!/bin/sh
set -eu

role="service"
if [ "${1:-}" = "service" ] || [ "${1:-}" = "loadgen" ]; then
    role=$1
    shift
fi

if [ "$role" = "loadgen" ]; then
    exec java -cp /opt/pricing/loadgen StorefrontTrafficSimulator "$@"
fi

java_agent=""
if [ "${OTEL_ENABLED:-false}" = "true" ]; then
    java_agent="-javaagent:/opt/otel/opentelemetry-javaagent.jar"
    export OTEL_TRACES_EXPORTER=otlp
    export OTEL_METRICS_EXPORTER=otlp
    export OTEL_LOGS_EXPORTER=none
fi

exec java ${java_agent} ${JAVA_OPTS:-} -jar /opt/pricing/storefront-pricing.jar
