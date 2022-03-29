# Swagger code generator for InfluxDB 2.x client libraries 

[![CircleCI](https://circleci.com/gh/bonitoo-io/influxdb-clients-apigen.svg?style=svg)](https://circleci.com/gh/bonitoo-io/influxdb-clients-apigen)
[![License](https://img.shields.io/github/license/bonitoo-io/influxdb-clients-apigen.svg)](https://github.com/bonitoo-io/influxdb-clients-apigen/blob/master/LICENSE)
[![GitHub issues](https://img.shields.io/github/issues-raw/bonitoo-io/influxdb-clients-apigen.svg)](https://github.com/bonitoo-io/influxdb-clients-apigen/issues)
[![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/bonitoo-io/influxdb-clients-apigen.svg)](https://github.com/bonitoo-io/influxdb-clients-apigen/pulls)

This repository contains tools to re-generate API from InfluxDB swagger.yml. 

Supported are following client libraries: 

* Java, 
* C#, 
* Python, 
* Php

#### Goals:
 
* simplify swagger updates
* centralize OpenApi generator for all languages on one place

#### Next steps todo:
* tag swagger.yml with "client libraries" and update generator to filter by this tag  
* after API stabilization and swagger cleanup generate directly from https://github.com/influxdata/influxdb/blob/master/http/swagger.yml 

#### Prequisities:
* Mac/Linux, git, docker, GNU make 

`Makefile` contains all needed

#### How to generate sources from updated swagger

1. Make required changes in `./swagger.yml`
1. Build openapi generator `make openapi-generator`
1. `make generate-java`, `make generate-csharp`, `make generate-python`, `make generate-php` will generates sources for specific client library.
1. `make generate-all` - generate new API stubs from `./swagger.yml` for all client libraries 
1. optionaly `make check-all` will try to compile and run tests for all client libraries
1. `make pr-java`, `make pr-csharp`, `make pr-python`, `make pr-php` will create PR into specific client library
