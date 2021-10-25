#!/bin/bash

set -ex

SCRIPT_PATH="$( cd "$(dirname "$0")" ; pwd -P )"

if [ -f "$SCRIPT_PATH"/influxdb-client-java.patch ]; then
  (cd "$SCRIPT_PATH"/build/influxdb-client-java/ && git apply "$SCRIPT_PATH"/influxdb-client-java.patch)
fi

rm -f ./build/influxdb-client-java/client/src/generated/java/com/influxdb/client/domain/*.java
rm -f ./build/influxdb-client-java/client/src/generated/java/com/influxdb/client/service/*.java

mvn -f ./openapi-generator/pom-java.xml -DswaggerLocation="${SCRIPT_PATH}/oss.yml" org.openapitools:openapi-generator-maven-plugin:generate
