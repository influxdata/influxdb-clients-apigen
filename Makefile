.DEFAULT_GOAL := help
.PHONY: openapi-generator
SHELL := /bin/bash

define git_checkout
@echo "Pull client "$(1)
@mkdir -p ./build
@git clone git@github.com:influxdata/$(1).git ./build/$(1) || true
@git --git-dir ./build/$(1)/.git pull
endef


help:
	@echo "Please use \`make <target>' where <target> is one of"
	@echo "  start-server   	to start the InfluxDB server"
	@echo "  stop-server    	to stop the InfluxDB server"
	@echo "  openapi-generator  to build openapi-generator"
	@echo "  generate-java	    to generate Java API sources from swagger.yml"
	@echo "  generate-csharp	to generate C# API sources from swagger.yml"
	@echo "  generate-python	to generate Python API sources from swagger.yml"
	@echo "  generate-php	    to generate Php API sources from swagger.yml"
	@echo "  generate-all	    to generate all clients API sources from swagger.yml"
	@echo "  pr-java		    to create PR into influxdb-client-java"
	@echo "  pr-csharp		    to create PR into influxdb-client-csharp"
	@echo "  pr-python		    to create PR into influxdb-client-python"
	@echo "  pr-php		    to create PR into influxdb-client-php"

dshell:
	@docker-compose run java bash

git-checkout-all:
	$(call git_checkout,influxdb-client-java)
	$(call git_checkout,influxdb-client-csharp)
	$(call git_checkout,influxdb-client-python)
	$(call git_checkout,influxdb-client-php)

openapi-generator: git-checkout-all
	@docker-compose build
	@docker-compose run java mvn -DskipTests -f openapi-generator/pom.xml clean install

#### Java
generate-java:
	$(call git_checkout,influxdb-client-java)
	@docker-compose run java ./generate-java.sh

check-java:
	@docker-compose run -w /code/build/influxdb-client-java java mvn clean compile

pr-java:
	@create-pr.sh influxdb-client-java

### CSharp
generate-csharp:
	$(call git_checkout,influxdb-client-csharp)
	@docker-compose run java ./generate-csharp.sh

check-csharp:
	@docker-compose run -w /code/build/influxdb-client-csharp csharp dotnet build

pr-csharp:
	@create-pr.sh influxdb-client-csharp

#### Python
generate-python:
	$(call git_checkout,influxdb-client-python)
	@docker-compose run java ./generate-python.sh

check-python:
	@docker-compose  run --workdir=/code/build/influxdb-client-python  python pip install -e .
	@docker-compose  run --workdir=/code/build/influxdb-client-python  python python ./setup.py install

pr-python:
	@create-pr.sh influxdb-client-python

#### Php
generate-php:
	$(call git_checkout,influxdb-client-php)
	@docker-compose run java ./generate-php.sh

check-php:
	@docker-compose run php composer install --working-dir=/code/build/influxdb-client-php
	@docker-compose run php composer run test --working-dir=/code/build/influxdb-client-php

pr-php:
	@create-pr.sh influxdb-client-php

check-all: check-java check-csharp check-python check-php
generate-all: generate-java generate-csharp generate-python generate-php

start-server:
	@docker-compose up -d influxdb_v2
	@scripts/influxdb-onboarding.sh ||:

stop-server:
	@docker-compose stop influxdb_v2

delete-sources:
	@rm -rf ./build
