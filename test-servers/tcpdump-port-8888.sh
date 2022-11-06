#!/bin/bash

# This starts monitoring traffic on localhost to/from port 8888

sudo tcpdump -i lo -Al -s0 -XX port 8888
