#!/bin/bash

# just a local test run
# trying to limit memory and cpu, like in kubernetes, and set a specific port range to use

docker run -ti --rm --name tunnel-server-java --network host -e START_PORT=9000 -e END_PORT=9099 --memory=800m --cpus=2 tunnel-server-java
