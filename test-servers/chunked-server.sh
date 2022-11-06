#!/bin/bash

# This starts a server on port 8888, and you can query it on http://127.0.0.1:8888/ via the tunnel.
# It returns a chunked response page. This is used to test if the tunnel copes with this chunked response.

docker run -ti --rm --name nginx -p 8888:80 -v `pwd`/php-chunk-response.php:/var/www/html/index.php -v `pwd`/README.md:/var/www/html/README.md php:apache
