# devtunnel

## High Level Description

You can use this project to make a local running web application appear to be running
on some remote cluster or server. Where this remote server does NOT have access to your
local machine, but your local machine DOES have access to the remote server.

This is done by using a reverse http tunnel server. You can run this server for example inside
a kubernetes cluster, or on some remote server in docker (or as native java application).
Then you connect your local machine to this tunnel server, using the tunnel-client from the
```tunnel-client-nodejs``` folder in this repository.

The tunnel server is meant for use by multiple developers at the same time, and will pick a
random server port number to listen on for web requests to be forwarded to.
This means that this tunnel is ONLY usable for cases where you can freely configure the
forwarding of the web traffic to the chosen server port number.

To make this a bit less random, the tunnel-client will remember which server port it got,
and on a next start it will try asking for that same port number to be used.
If in the meantime the tunnel server has not given that port to another user, you will
get that same port again. This will lead to quite a stable set of port mappings, as long
as the tunnel-server has more ports than there are users ;-) Of course there is still
the chance of some overlap, as the tunnel-server at the moment does not remember which ports
were given to who when the server is restarted. As long as the server keeps running, the
ports will stay reserved.

If the server runs out of ports, then the oldest port which is not in active use will be
used for the new tunnel connection.

Note: there is no form of security in this tunnel server yet. The plan is to use this in
a test environment only. The only "risk" is that others on the intranet can start a tunnel,
which would reserve/take port numbers for the normal users. So a form of denial-of-service.
No other harm is likely to happen, as the traffic will only be initiated from the remote
server to the local tunnel user, so no unwanted access to the remote cluster is possible.
If it seems that the tunnel is getting DOS-ed, we can add security measures. For example
a shared password to use to start a tunnel, or even go as far as setting up accounts per user.

**Here's an image to clarify the setup:**

```text
                                                         Kubernetes Cluster
  Developer Laptop                                     ┌────────────────────────────────────────┐
┌───────────────────────────────────────────┐          │                                        │
│                                           │          │   ┌─────────┐           ┌───────────┐  │
│                         ┌────────────┐    │          │   │         │           │           │  │
│                         │            │    │          │   │         ├───────────►  Web App  │  │
│                         │  Browser───┼────┼──────────┼───►  Web    │           │           │  │
│                         │            │    │          │   │  Proxy  │           └───────────┘  │
│                         │            │    │          │   │         │                          │
│                         └────────────┘    │          │   │         │           ┌───────────┐  │
│                                           │          │   │         ├───────────►           │  │
│                                           │          │   │         │           │  Web App  │  │
│  ┌────────────┐         ┌────────────┐    │          │   │         │           │           │  │
│  │            │         │            │    │          │   │         ├──────┐    └───────────┘  │
│  │  Local     │         │  Tunnel    │    │          │   └─────────┘      │                   │
│  │  Web App   ◄─────────┤  Client────┼────┼───┐      │                    │    ┌───────────┐  │
│  │            │         │            │    │   │      │                    └────►  Tunnel   │  │
│  └────────────┘         └────────────┘    │   │      │  Reverse Tunnel         │  Server   │  │
│                                           │   └──────┼─────────────────────────►           │  │
└───────────────────────────────────────────┘          │                         └───────────┘  │
                                                       │                                        │
                                                       └────────────────────────────────────────┘
```

What is missing in above image is the firewalls and VPNs between the laptop and the cluster,
which only makes traffic from laptop to cluster possible. And not the other way around.

The image explained:

- The developer runs a local web application, which makes a part of the test website.
- The browser on the developer machine talks to the web proxy on the kubernetes test cluster.
- The web proxy on that cluster has rules to know to which web-app to send the traffic to.
- One of the web-apps can be simulated by the tunnel-server.
- The tunnel-client on the developer laptop is talking to the tunnel-server.
- When a request from the browser, via the web proxy enters the tunnel server, it will
  be passed back to the tunnel client, and will be sent to the local web-app on the developer
  machine.
- The response from the local web-app will go back via the tunnel client to the tunnel-server,
  and will be presented as response back to the browser (via the web proxy).

How does the web-proxy know where to route all traffic to? It has a configuration file, which
has the routes to use. But this set of routes is the same for ALL users. To allow a developer
to "override" one or more routes to NOT go to the standard web-app's on the cluster, the
developer can set a special COOKIE. This cookie will let the web-proxy know that for that
particular user which has the cookie, an alternative route must be used, and which route.
Note: the code of this proxy is not in scope of this project.

While above image and text talks about web-applications. Of course this same tunnel server
can be used for our services landscape. We have an API-gateway which has a similar role as
the web-proxy in the image, which talks to our rest and graphql services. A service developer
could run a local service, and set up the tunnel, and then tell our API-gateway to talk to the
tunnel server to reach the local service instead of a service on the cluster. The API-gateway
on our test environment has the option to override the target service route by sending it a
special request header (is similar to the web cookie routing override).

## tunnel-server-java

See folder ```tunnel-server-java``` for the code. Written in java. It does have a docker file
example, which you can use to package this server for deployment in kubernetes.

- The tunnel-server works on network layer 7, parsing and forwarding HTTP/1.1 calls.
- It is NOT 100% HTTP/1.1 spec compliant yet. So let me know if you find cases which do
  not work. One case I know of, which is marked as TODO item, is sending chunked data to
  the tunnel. I imagine that for example a file upload will need this. This does NOT work
  yet. Chunked responses should work fine.
- The theory of operation sounds simple, but implementing this was quite a complex thing ;-)
  - The tunnel client registers itself, which opens a server port for that client on the
    tunnel-server.
  - The client sends a GET request, which is paused at the tunnel-server end until either a
    request comes in for that client on the server port. Or if 30 seconds without request
    have passed.
  - If the 30 seconds were over, then the client will just start a new poller request.
  - If there was a request for the tunnel on the server-port, then the request is read,
    and send as RESPONSE on the poller-request back to the tunnel client.
  - The tunnel client will open a connection to the local APP, and send on the request.
  - When the local app sends a response, the tunnel-client will send a POST to the
    tunnel-server, which has the APP response as POST request body.
  - The tunnel server will pass that on as response via the server port to the original
    requester.
  - As last step, the POST will hang in the tunnel-server to wait for a next request on the
    server port. Or it will time out if no requests come are done within 30 seconds.
  - From here on the tunnel and server loop back to the top of this list (after the
    registration step).
- The current tunnel-client is written in NodeJS, as that is the runtime which is on all
  frontend developer machines. If needed, I could look at making a python or java client
  also, for use by backenders / service developers.

Note: there are multiple projects / tools available on internet, which serve this exact same
purpose. You might want to have a look at those, to see which one does suit your situation best.
On our environment, we also have created an SSH server container, to start reverse SSH tunnels
for a similar purpose, so that is also a solution direction you can use, as SSH also can create
reverse tunnels. I just thought it was fun (a nice challenge) to develop a layer 7 version for
this on top of a http(s) transport.

To run the server, you can either build the docker image, and start it:

- ```./docker-build.sh```
- ```./docker-run.sh```

This docker setup does not require you to have java 17 on your machine.
Note: there are two Dockerfiles, one which assumes you did a java build in advance, and
one which does the full build for you.

Or you can just build it on your command line:

- ```./mvnw clean install```
- ```java -XshowSettings:vm -Dspring.profiles.active=tst -jar target/devtunnel-0.0.1-SNAPSHOT.jar```

You do need java (jdk 17) on your machine to build and run it without the docker setup.

It is left as an exercise to the reader / user to migrate this build to fit your CI-cd systems.
After I have installed this, I might add an example kubernetes deployment yml file to this
project. When running on our clusters, we use json log format. Just start with JVM option:
```-Dspring.profiles.active=kubernetes,tst``` in that case (is the default in the dockerfiles).

## tunnel-server-java - report

The tunnel-server has a report page. It listens on the standard root/home page of the
tunnel-server code. E.g. if you run this locally, try ```http://127.0.0.1:8080/```.
If this is deployed in kubernetes, the report will be reachable on the exposed tunnel-server
url.

The report shows a list of tunnel user-ids and some counters for those. And also if the user
is active or not. On tunnel-server restart, the list will be cleared.

## tunnel-client-nodejs

See folder ```tunnel-client-nodejs``` for the code. Written for nodejs.

Disclaimer: I am NOT a nodejs application developer. So some of my choices in the code might
seem a bit off ;-) Feel free to submit correction pull-requests to me.

All code is currently in a single file: ```dev-tunnel-client.js```.

Reason to use nodejs is that the frontenders at work all use nodejs for the frontend
development. So it is a runtime engine they all have on their machine. Not everyone
wants or can install Docker or java.

The current tunnel client is a working proof-of-concept. It is quite possible that if we
actually start using this, that it will be copied into some other work repository
(a tools/library mono repo), and will get refactored heavily.

The current version does not make proper use of the asynchronous ideas of nodejs. All is
done in a simple synchronous (endless loop) way:

- you start the code using:
  ```nodejs dev-tunnel-client.js http://tunnel-server:server-port/ 3001``` where 3001
  is the local target port. You can replace that with the port you need for your local app.
  And if you run two apps locally on two different ports, you can just start two tunnels
  at the same time. Note: you can also connect to an httpS tunnel-server, but the local
  app will for now always be talked to without httpS, to using plain http. We could add https
  targets if needed, but I see no need for it at the moment.
- it reads the ```~/dev-tunnel.conf``` config file (or creates one)
- a user-id is constructed from logged on username, your machine name, the required target
  application port, and a random number to prevent double user-ids. Example user-id:
  ```thijs@fizzgig:8888#672243```.
- it registers your tunnel, using your user-id.
- it will show the SERVER PORT which you got assigned by the tunnel server. You need to
  use THAT port to forward your test web traffic to, so look carefully at that number ;-)
  The number is stored in your ```~/dev-tunnel.conf``` to try on a next run to get the
  same server port again. That might come in handy if you bookmark test setups/urls.
  You are not guaranteed to get the same number, but if it is still available, you will
  get it.
- From here onwards, the tunnel-client starts an endless loop;
  - poll the tunnel-server to see if any web request were coming in.
  - on timeout, just go back to that poll step again (will happen every 30 seconds).
  - then read the web-request from the tunnel-server.
  - connect to the local application on the target port.
  - send the request to the local target port.
  - read a response from the local target application.
  - post the response to the tunnel-server (and tunnel server will send it on to the
    original web caller).
  - and the post will end in a poll-wait for a next request.
  - and this goes back to the start of the endless loop...
- If more than 10 errors occur at the start of the endless loop, the tunnel client does
  exit. And in that case you will have to restart the tunnel-client to get is registered
  again. Note: this could happen on network loss, or if the tunnel-server is restarted
  for some reason.

Example contents of the ```~/dev-tunnel.conf``` config file:

```json
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

You can edit any values in this file, if needed. But I guess the defaults will all work fine for
everyone.

- The user is taken initially from your logged on user account on your laptop.
- The host is attempted to be read from your machien hostname. Not sure if that will work
  on all different OS-es (tested on Linux for now). So if you see a wrong value there, you
  can correct it if you want.
- The userIdPostfix is a random number. Just in case there are multiple users which have the same
  username, and same host name.
- The lastUsedPorts contains a map from your local application target port, to the last server
  port we got for that target. On a new start, the tunnel client will let the server know what
  that last used server port was, and if it is not in use by someone else, the server will get
  you that same port again.

## Closing

Status: this is just a proof-of-concept. I only tested it on my local machine for now.
I will give this a try at work, and see if it suits our needs.

Note: the ```test-servers``` folder has some tools I did use during development + testing of
the devtunnel. It has its own README.md file. For using the tunnel, you do not need that
folder.

TODO / Change Requests (not high priority):

- Implement chunked POST's to the tunnel-server from the web requests.
- If needed, add some form of security? Not really needed I think. As you can not abuse the tunnel.
- Perhaps make list of connections persistent at server side also, instead of just in tunnel-clients.
  This would help in giving all developers their own dedicated server ports.
- Implement aut-reconnect of tunnel-client, if tunnel-server has been restarted.
- Perhaps add some unit tests? (probably not going to happen ;-))
- Report has a hidden feature to terminate/kill a tunnel. Make that visible (hoping users will
  not randomly start killing other peoples connections).

Thijs Kaper, November 6, 2022.
