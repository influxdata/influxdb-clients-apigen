#!/bin/bash

set -ex

SCRIPT_PATH="$( cd "$(dirname "$0")" ; pwd -P )"

rm -f ./build/influxdb-client-java/client/src/generated/java/com/influxdb/client/domain/*.java
rm -f ./build/influxdb-client-java/client/src/generated/java/com/influxdb/client/service/*.java

mvn -f ./openapi-generator/pom-java.xml -DswaggerLocation="${SCRIPT_PATH}/oss.yml" org.openapitools:openapi-generator-maven-plugin:generate
