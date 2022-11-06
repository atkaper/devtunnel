#!/bin/bash


# if you have a local java, you can use this to build the container:
# ./mvnw clean install && docker build -t tunnel-server-java .

# if you do not have java, you can use this to build the container:
docker build -t tunnel-server-java -f Dockerfile-full-build .
