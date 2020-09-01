#!/bin/bash

set -ex

SCRIPT_PATH="$( cd "$(dirname "$0")" ; pwd -P )"

	rm -rf ./build/influxdb-client-php/generated
	mvn -f ./openapi-generator/pom-php.xml -DswaggerLocation=./swagger.yml org.openapitools:openapi-generator-maven-plugin:generate
	#### sync generated php files to src

	# delete old sources
	rm -f ./build/influxdb-client-php/src/InfluxDB2/API/*
	rm -f ./build/influxdb-client-php/src/InfluxDB2/Model/*

	#cp -r ./build/influxdb-client-php/generated/lib/ApiException.php ./build/influxdb-client-php/src/InfluxDB2
	cp -r ./build/influxdb-client-php/generated/lib/ObjectSerializer.php ./build/influxdb-client-php/src/InfluxDB2

	#mkdir -p ./build/influxdb-client-php/src/InfluxDB2/API
	#cp -r ./build/influxdb-client-php/generated/lib/API/*.php ./build/influxdb-client-php/src/InfluxDB2/API

	mkdir -p ./build/influxdb-client-php/src/InfluxDB2/Model
	cp -r ./build/influxdb-client-php/generated/lib/Model/WritePrecision.php ./build/influxdb-client-php/src/InfluxDB2/Model
	cp -r ./build/influxdb-client-php/generated/lib/Model/Query.php ./build/influxdb-client-php/src/InfluxDB2/Model
	cp -r ./build/influxdb-client-php/generated/lib/Model/Dialect.php ./build/influxdb-client-php/src/InfluxDB2/Model
	cp -r ./build/influxdb-client-php/generated/lib/Model/ModelInterface.php ./build/influxdb-client-php/src/InfluxDB2/Model
	cp -r ./build/influxdb-client-php/generated/lib/Model/HealthCheck.php ./build/influxdb-client-php/src/InfluxDB2/Model

	rm -rf ./build/influxdb-client-php/generated
