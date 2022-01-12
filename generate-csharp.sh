#!/bin/bash

set -ex

SCRIPT_PATH="$( cd "$(dirname "$0")" ; pwd -P )"

if [ -f "$SCRIPT_PATH"/influxdb-client-csharp.patch ]; then
  (cd "$SCRIPT_PATH"/build/influxdb-client-csharp/ && git apply "$SCRIPT_PATH"/influxdb-client-csharp.patch)
fi

rm ./build/influxdb-client-csharp/Client/InfluxDB.Client.Api/Domain/*.cs || true
rm ./build/influxdb-client-csharp/Client/InfluxDB.Client.Api/Service/*.cs || true
rm ./build/influxdb-client-csharp/Client/InfluxDB.Client.Api/Client/*.cs || true

mvn -f ./openapi-generator/pom-csharp.xml -DswaggerLocation="${SCRIPT_PATH}/oss.yml" org.openapitools:openapi-generator-maven-plugin:generate
