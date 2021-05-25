#!/bin/bash

set -ex

SCRIPT_PATH="$( cd "$(dirname "$0")" ; pwd -P )"

rm ./build/influxdb-client-python/influxdb_client/domain/*.py || true
rm ./build/influxdb-client-python/influxdb_client/service/*.py || true

mvn -f ./openapi-generator/pom-python.xml -DclientVersion=1.11.0dev -DswaggerLocation="${SCRIPT_PATH}/oss.yml" org.openapitools:openapi-generator-maven-plugin:generate
