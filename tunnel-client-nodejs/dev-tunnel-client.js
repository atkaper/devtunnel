// ##################################################################################################
// # dev-tunnel-client
// #
// # This nodejs code will connect to the dev-tunnel server, and together they will act as
// # reverse tunnel to get remote traffic to a local running application (as if the local application
// # was running on the remote server).
// #
// # Start arguments: [tunnel-server-url] [local-app-target-port]
// # Example start  : node dev-tunnel-client.js https://dev-tunnel.somedomain.com/ 3001
// # Note: I am currently running this in node v16.14.2.
// #
// # DURING START, WATCH FOR A LINE BETWEEN TWO "=========" LINES, IT MENTIONS THE CHOSEN SERVER
// # PORT. You need that server port, when you want to divert your web traffic to this reverse tunnel
// # on the server side.
// #
// # Note: a file "dev-tunnel.conf" will be made in your home directory, which contains the
// # last used target ports, and which server port you got for them. When starting a tunnel for
// # such a target port again, the server will try to give you the same server port for it, if it is
// # not taken over by someone else. Your tunnel-user-id will be constructed from your login name,
// # hostname, target port, and a random generated code. If your login name or hostname is not
// # auto-detected correctly, you can edit them in the config file. But this is not really needed.
// ##################################################################################################

import fetch from 'node-fetch';
import { exit } from 'process';
import net from 'net';
import os from 'os';
import fs from 'fs';
const sleeper = ms => new Promise( res => setTimeout(res, ms));

// ##################################################################################################
// # Collect configuration details - combination of command line arguments / context / stored config.
// ##################################################################################################

const myArgs = process.argv.slice(2);
if (myArgs.length !== 2) {
    console.log("Please pass in two start arguments; [tunnel-server-url] [local-app-target-port]");
    console.log(`Example: ${process.argv[0]} ${process.argv[1]} "https://dev-tunnel.somedomain.com/" 3001`);
    console.log(`Example: node dev-tunnel-client.js https://dev-tunnel.somedomain.com/ 3001`);
    exit(1);
}

const TUNNEL_URL = myArgs[0].replace(new RegExp("/$"), "");
const TUNNEL_TARGET_PORT = myArgs[1];

const configFile = (os.homedir() || '~/') + '/dev-tunnel.conf';

function writeConfig(theConfig) {
    try {
        fs.writeFileSync(configFile, JSON.stringify(theConfig, null, 4));
    } catch (err) {
        console.error(err);
        exit(1);
    }
}

if (!fs.existsSync(configFile)) {
    writeConfig({
        user: process.env.USER || os.userInfo().username || 'unknown',
        host: os.hostname(),
        userIdPostfix: Math.floor(Math.random() * (999999 - 100000) + 100000),
        lastUsedPorts: {},
    });
}

let config;
try {
    config = JSON.parse(fs.readFileSync(configFile, 'utf8'));
} catch (err) {
    console.error(err);
    exit(1);
}

const user = config.user;
const host = config.host;
const userIdPostfix = config.userIdPostfix;

const TUNNEL_USER_ID = `${user}@${host}:${TUNNEL_TARGET_PORT}#${userIdPostfix}`;
const TUNNEL_PREFERRED_PORT = config.lastUsedPorts[TUNNEL_TARGET_PORT];

// ##################################################################################################
// # Config done, register the client with the tunnel server. Watch closely to see the chosen port!
// ##################################################################################################

const headers = {
    'X-Tunnel-User-Id': TUNNEL_USER_ID,
    'X-Tunnel-Client-Version': "1",
};

if (TUNNEL_PREFERRED_PORT) {
    // If we have memorized a server port for an earlier run, pass it on to see if we can get the same one again.
    headers['X-Tunnel-Preferred-Port'] = TUNNEL_PREFERRED_PORT;
}

let response;
try {
    response = await fetch(TUNNEL_URL + '/register', {
        method: 'GET',
        headers: headers,
    });
} catch(e) {
    console.log('======================================================================================================');
    console.log(`Tunnel start error, UserId: ${TUNNEL_USER_ID}`);
    console.log(`${e}`);
    console.log('======================================================================================================');
    exit(1);
}

const data = await response.text();

let serverPort;
console.log('======================================================================================================');
if (response.status === 200) {
    serverPort = response.headers.get('X-Tunnel-Server-Port');
    // Show the chosen server port to the user. You need this to divert your server-side traffic to!
    console.log(`TUNNEL[${serverPort}:${TUNNEL_TARGET_PORT}] - Tunnel Server Port: ${serverPort}, UserId: ${TUNNEL_USER_ID}`);
} else {
    console.log(`Tunnel start error: ${data?.trim()}, UserId: ${TUNNEL_USER_ID}`);
}
console.log('======================================================================================================');

if (response.status !== 200) {
    console.log("Exit");
    exit(1);
}

// Memorize which port we got for the used TUNNEL_TARGET_PORT.
config.lastUsedPorts[TUNNEL_TARGET_PORT] = serverPort;
writeConfig(config);

// ##################################################################################################
// # After registration, go into an endless loop of waiting for requests, and handling them.
// ##################################################################################################

let pollForRequestResponse = {status: 204};

// Main processing loop, wait for request, and handle it.
let errorRetryCount = 0;
while(errorRetryCount <= 10) {
    // ##############################################################################################
    // # If last status was 204, POLL for a new request. After 30 seconds times out, and retries.
    // ##############################################################################################

    // 204 is a "normal" functional status, it means there were no new incoming requests yet.
    // Just keep trying by starting a new "long-poll". The 30 sec timeout is set at the server end.
    // We only keep waiting for 30 seconds, as some infra networking in between client and server
    // might time out after 1 minute, and would kill the connection.
    while (pollForRequestResponse.status === 204 && errorRetryCount <= 10) {
        try {
            pollForRequestResponse = await fetch(TUNNEL_URL + '/data', {
                method: 'GET',
                headers: {
                    'X-Tunnel-User-Id': TUNNEL_USER_ID,
                    'X-Tunnel-Port': serverPort,
                }
            });
        } catch (e) {
            console.log(`TUNNEL[${serverPort}:${TUNNEL_TARGET_PORT}] - poll error: ${e} (retry after 3 seconds)`);
            errorRetryCount++;
            await sleeper(3000);
            // set dummy status, to just try again at start of while
            pollForRequestResponse = { status: 204 };
        }
    }

    // ##############################################################################################
    // # Not 204, so either we got a request, or an error. Check which one...
    // ##############################################################################################

    let requestId;
    let requestLine;
    if (pollForRequestResponse.status === 200) {
        // status 200 means we got a request. Get request ID for later use to send response to.
        requestId = pollForRequestResponse.headers.raw()['x-tunnel-request-id'][0];
        requestLine = pollForRequestResponse.headers.raw()['x-tunnel-request'][0];
        console.log(`TUNNEL[${serverPort}:${TUNNEL_TARGET_PORT}] - Request-Id: ${requestId}, Request: ${requestLine}`);
    } else {
        // not 200, so must be some error. Log and retry a couple of times before we give up.
        console.log(`TUNNEL[${serverPort}:${TUNNEL_TARGET_PORT}] - status: ${pollForRequestResponse.status}`);
        console.log(pollForRequestResponse.headers?.raw());
        console.log(`TUNNEL[${serverPort}:${TUNNEL_TARGET_PORT}] - unexpected response - will ignore this for now, and just listen for next request...`);
        // act as if we got a 204, so we will just start listening for a new connection
        pollForRequestResponse = { status: 204 };
        errorRetryCount++;
        await sleeper(500);
        continue;
    }

    // We got a valid requestId, so reset errorRetryCount.
    errorRetryCount = 0;

    // ##############################################################################################
    // # Request was valid. Connect to local web-app, and send the incoming request to it.
    // ##############################################################################################

    let appSocket;
    try {
        // Create connection to the local application (note: async, any connect errors will occur when trying to USE the connection).
        appSocket = net.createConnection({
            host: '127.0.0.1',
            port: parseInt(TUNNEL_TARGET_PORT),
        });

        // Use connection, send received request from pollForRequestResponse to the application.
        const body = await pollForRequestResponse.arrayBuffer();
        appSocket.write(Buffer.from(body));
    } catch (ex) {
        // Oops. Something went wrong.
        console.log(`TUNNEL[${serverPort}:${TUNNEL_TARGET_PORT}] - Request-Id: ${requestId}, Error writing to APP ${TUNNEL_TARGET_PORT} socket? ${ex}`);
        // read full request body, and throw away
        // next line does not work? seems we don't need it... perhaps the above pollForRequestResponse.body has taken it partially already?
        //   await pollForRequestResponse.text();
        // Now send an error to the original caller.
        const errorMessage = `Could not connect to application of ${TUNNEL_USER_ID}, error: ${ex}\n`;
        pollForRequestResponse = await fetch(TUNNEL_URL + '/data', {
            method: 'POST',
            body: 'HTTP/1.1 503 APP_DOWN\r\nContent-Type: text/plain\r\nConnection: close\r\nContent-Length: ' + errorMessage.length + '\r\n\r\n' + errorMessage,
            headers: {
                'X-Tunnel-User-Id': TUNNEL_USER_ID,
                'X-Tunnel-Request-Id': requestId,
                'Content-Type': 'application/octet-stream',
            }
        });
        // go to start of while loop. The "fetch/post" here will end in polling for a next request, to be handled by start of while.
        continue;
    }

    // ##############################################################################################
    // # Request sent to local web-app, now wait and read reply from web-app.
    // ##############################################################################################

    // Listen for data chunks from app. I haven't found a way to stream it directly, so collecting full
    // response in memory. Positive side effect is that we can show the first line of the response in the log.
    let chunks = [];
    appSocket.on('data', (data) => {
        chunks.push(data);
    });

    // Turn callback into a promise, so we can wait for it ;-)
    function waitAppSocketEnd(appSocket) {
        return new Promise((resolve) => appSocket.on('end', () => { resolve(); }));
    }

    await waitAppSocketEnd(appSocket);
    const appData = Buffer.concat(chunks);
    // Get app-response status line
    const firstResponseStatusLine = appData.toString('utf8', 0, appData.indexOf(13));

    // ##############################################################################################
    // # Collected reply will now be sent to the tunnel-server as response. Post ends in poll-wait.
    // ##############################################################################################

    console.log(`TUNNEL[${serverPort}:${TUNNEL_TARGET_PORT}] - Request-Id: ${requestId}, Response: ${firstResponseStatusLine}, ${appData.length} bytes`);

    // Send app response to tunnel for given request-id. The post will end in poll-wait for a next request.
    try {
        pollForRequestResponse = await fetch(TUNNEL_URL + '/data', {
            method: 'POST',
            body: appData,
            headers: {
                'X-Tunnel-User-Id': TUNNEL_USER_ID,
                'X-Tunnel-Port': serverPort,
                'X-Tunnel-Request-Id': requestId,
                'Content-Type': 'application/octet-stream',
                'Content-Length': appData.length.toString(),
            }
        });
    } catch (e) {
        console.log(`TUNNEL[${serverPort}:${TUNNEL_TARGET_PORT}] - post/poll error: ${e}`);
        // set dummy status, to just try again at start of while
        pollForRequestResponse = { status: 204 };
    }
    // Here we jump back to start of while, to handle a next request, or start a new poll if no data yet.
}

// ##################################################################################################
// # We only end up here, if we got a number of errors in a row on the poller. If so, exit. Sad.
// ##################################################################################################

console.log('======================================================================================================');
console.log(`TUNNEL[${serverPort}:${TUNNEL_TARGET_PORT}] - Too many retry errors, exit.`);
console.log('======================================================================================================');
exit(1);
