#!/bin/bash

# This starts an empty piwigo installation on port 8888. Does not do anything much, as you need a proper database for it.
# But it does show an installation screen, which pointed me to a small bug in my tunnel (which has been fixed of course).

docker run -ti --rm   --name=piwigo   -e PUID=1000   -e PGID=1000   -e TZ=Europe/London   -p 8888:80   lscr.io/linuxserver/piwigo:latest
