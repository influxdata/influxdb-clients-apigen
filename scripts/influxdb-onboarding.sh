#!/usr/bin/env bash
set -e

echo "Wait to start InfluxDB 2.x"
wget -S --spider --tries=20 --retry-connrefused --waitretry=5 http://localhost:8086/metrics

echo
echo "Post onBoarding request, to setup initial user (my-user@my-password), org (my-org) and bucketSetup (my-bucket)"
echo
curl -i -X POST http://localhost:8086/api/v2/setup -H 'accept: application/json' \
    -d '{
            "username": "my-user",
            "password": "my-password",
            "org": "my-org",
            "bucket": "my-bucket",
            "token": "my-token"
        }'
