#!/bin/bash

set -ex

SCRIPT_PATH="$( cd "$(dirname "$0")" ; pwd -P )"

rm -rf ./build/influxdb-client-php/generated
mvn -f ./openapi-generator/pom-php.xml -DswaggerLocation="${SCRIPT_PATH}/oss.yml" org.openapitools:openapi-generator-maven-plugin:generate
#### sync generated php files to src

# delete old sources
rm -f ./build/influxdb-client-php/src/InfluxDB2/Service/*
rm -f ./build/influxdb-client-php/src/InfluxDB2/Model/*

#cp -r ./build/influxdb-client-php/generated/lib/ApiException.php ./build/influxdb-client-php/src/InfluxDB2
cp -r ./build/influxdb-client-php/generated/lib/ObjectSerializer.php ./build/influxdb-client-php/src/InfluxDB2
cp -r ./build/influxdb-client-php/generated/lib/HeaderSelector.php ./build/influxdb-client-php/src/InfluxDB2

mkdir -p ./build/influxdb-client-php/src/InfluxDB2/Model
mkdir -p ./build/influxdb-client-php/src/InfluxDB2/Service

cp -r ./build/influxdb-client-php/generated/lib/Service/*.php ./build/influxdb-client-php/src/InfluxDB2/Service
cp -r ./build/influxdb-client-php/generated/lib/Model/*.php ./build/influxdb-client-php/src/InfluxDB2/Model

#rm -rf ./build/influxdb-client-php/generated
