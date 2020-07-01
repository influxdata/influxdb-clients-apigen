#!/bin/bash

set -ex
SCRIPT_PATH="$( cd "$(dirname "$0")" ; pwd -P )"
cd ${SCRIPT_PATH}/build/$1
BRANCH_NAME=swagger-update/`date +%m-%d-%Y-%H-%M`
echo new branch name: $BRANCH_NAME

git branch $BRANCH_NAME
git checkout $BRANCH_NAME
git commit -am "feat(swagger): update swagger.yml" || true
git push origin $BRANCH_NAME

gh pr create --title "feat(swagger): update swagger.yml" --body ""


