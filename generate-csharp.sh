#!/bin/bash

set -ex

SCRIPT_PATH="$( cd "$(dirname "$0")" ; pwd -P )"

rm ./build/influxdb-client-csharp/Client/InfluxDB.Client.Api/Domain/*.cs || true
rm ./build/influxdb-client-csharp/Client/InfluxDB.Client.Api/Service/*.cs || true
rm ./build/influxdb-client-csharp/Client/InfluxDB.Client.Api/Client/*.cs || true

mvn -f ./openapi-generator/pom-csharp.xml -DswaggerLocation=./swagger.yml org.openapitools:openapi-generator-maven-plugin:generate
