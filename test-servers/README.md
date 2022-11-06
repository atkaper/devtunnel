# Test Servers / Local APP's

This folder contains some test server backends, to simulate some local app to run via the tunnel.

## Test Steps

- You should start the tunnel-server-java "devtunnel" server, to listen on port 8080.
- Then start one of these server backends.
- Next start the tunnel-client-nodejs tunnel client using nodejs (I currently use nodejs v16.14.2).
  Pass in the following start arguments to the tunnel client:
  - http://127.0.0.1:8080/
  - 8888
- Note: the tunnel client start will show which server port is assigned to you.
  In my tests, I kept using server port 9003. After it selected a port, it will keep the same one
  for each test run, as it stores the configuration in ```~/dev-tunnel.conf```.
  If you want to, you can edit that config to choose a target server port you prefer. After editing
  the config, restart the tunnel server, and the tunnel client to pick up the change.
- After all is running, access http://127.0.0.1:9003/  where the 9003 must be replaced by the server
  port number you got from the tunnel client startup. So will probably be 9000 on a clean start.
  Note: the preferred server port is connected to your tunnel target port (in above example 8888).
  You can run multiple tunnels to different target ports at the same time if needed. And each will
  get a different server port.

## The test servers

Note: all of these are set to listen on port 8888, to be able to switch them around while my tunnel
was still running.

### echo-service.py

The ```echo-service.py``` is a simple python3 echo server. It reads whatever you throw at it, and
responds with a report of what you did send.

Any request url or method (GET/POST) you send, should a response.

### chunked-server.sh

The ```chunked-server.sh``` starts an ```php:apache``` docker container, with 
```php-chunk-response.php``` in it as /index.php root/home page.

The page will set content type to ```text/plain```, then flush output, and afterwards sends the
contents of the README.md file. This flush before sending data will trigger the apache to use a
chunked stream response, instead of a fixed Content-Length response. This is used to see how the
tunnel copes with chunking.

Note/Warning: the tunnel currently does support chunked responses from the target app to the
web-browser, but NOT chunked requests from the web-browser to the target app. I might add that at
a later time, if there is a need for it. Only case I can think of at the moment, would be
a file upload page. So too-bad for now for those ;-)

Only a request to the root/home page will send back a document. All others will pass back a 404
(which in itself is also a valid test).

### piwigo-demo-webapp.sh

The ```piwigo-demo-webapp.sh``` is just some web-app taken from internet. It does however require
a database, so is not of much use. It only displays an installation setup page. However, this
page did lead me to find a tunnel client bug, which of course has been fixed now ;-)

### nginx-proxy

The ```nginx-proxy``` folder contains the ```run.sh``` script which starts a docker container with
an nginx proxy, using configuration ```default.conf```. It does proxy traffic from port 8888 to
website https://www.kaper.com/ just to have a site to browse through to test via the tunnel.

Note/Warning: the ```default.conf``` file contains some html response search/replace statements,
to rewrite the original hostname to the tunnel-server server port address you will use. You do
need to update the nginx ```default.conf``` to have YOUR tunnel server port. So first start the
tunnel, then edit the port number in that conf, and then start the nginx proxy.

Or... you could put server port 9003 in your ~/dev-tunnel.conf file as preferred server port for
target port 8888 ;-) e.g. like this:

```
$ cat ~/dev-tunnel.conf 
{
    "user": "thijs",
    "host": "fizzgig",
    "userIdPostfix": 672243,
    "lastUsedPorts": {
        "3001": "9004",
        "8888": "9003"
    }
}
```

## Network Inspection

The folder also contains ```tcpdump-port-8080.sh``` and ```tcpdump-port-8888.sh```, which start
a tcpdump on the ports 8080 and 8888. I did use those to look at the packages going between
app, tunnel-client, and tunnel-server.

Probably won't be of much use for anyone else ;-) Unless you want to dig deeper into what happens.
