#!/bin/bash

# This starts monitoring traffic on localhost to/from port 8080

sudo tcpdump -i lo -Al -s0 -XX port 8080
