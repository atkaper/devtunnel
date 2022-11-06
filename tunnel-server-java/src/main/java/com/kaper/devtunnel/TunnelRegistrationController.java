package com.kaper.devtunnel;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * UserId registration controller.
 * In here we will pick a port to listen on at the web/server side, and pass it back to the tunnel client at the application side.
 */
@Slf4j
@Controller
public class TunnelRegistrationController {
    private final TunnelServer tunnelServer;
    private final TunnelPortService tunnelPortService;

    public TunnelRegistrationController(TunnelServer tunnelServer, TunnelPortService tunnelPortService) {
        this.tunnelServer = tunnelServer;
        this.tunnelPortService = tunnelPortService;
    }

    /**
     * Register tunnel client (app side) user. This will start listening on a local server port, and pass back the port number to the tunnel client.
     * The tunnel client user needs to know that port number, to forward his/her web traffic on to. So the tunnel-client should show this
     * server port in a nice visible way.
     * If a user tries to register for a second time using the same userId, then the server listener is terminated, and restarted.
     * If a user needs more than one tunnel, he/she should use multiple userId's.
     * Suggested userId format to use: "[system-userid]@[users-hostname]:[app-target-port]#[random-but-fixed-code]".
     * Example for my machine: "thijs@fizzgig:3001#1666973349". Where the 1666973349 would be stored in ~/.web-app-tunnel.conf for re-use on next start.
     * The random part is just in case multiple machines have the same name and same user-account.
     * A user must pass in the userId via request header: "X-Tunnel-User-Id", and you can optionally also pass in the
     * port number you did use the last time for this userId, to see if it is still available for re-use by sending header:
     * "X-Tunnel-Preferred-Port" with the preferred port number.
     */
    @GetMapping("/register")
    public synchronized ResponseEntity<String> registerUserServerContext(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            MDC.put(Constants.MDC_REQUEST_STAGE, Constants.STAGE_REGISTER);
            UserServerContext userServerContext = tunnelServer.getUserServerContext(request, response);
            if (userServerContext == null) {
                return null;
            }
            // Note: the client-version can be used in case new features are build in the server, to be backwards
            // compatible with old clients. First client version will start at "1".
            userServerContext.setClientVersion(Integer.parseInt(request.getHeader(Constants.X_TUNNEL_CLIENT_VERSION)));
            MDC.put(Constants.MDC_USER_ID, userServerContext.getUserId());
            response.addHeader(Constants.X_TUNNEL_USER_ID, userServerContext.getUserId());

            if (userServerContext.getServerPort() != 0) {
                // Already registered, kill existing one, and re-register.
                tunnelServer.closeUserServerContext(userServerContext);
                tunnelServer.registerUserServerContext(userServerContext);
            } else {
                Integer preferredPort = request.getHeader(Constants.X_TUNNEL_PREFERRED_PORT) != null
                        ? Integer.valueOf(request.getHeader(Constants.X_TUNNEL_PREFERRED_PORT)) : null;
                userServerContext.setServerPort(tunnelPortService.requestNewPort(preferredPort));
            }
            if (userServerContext.getServerPort() <= 0) {
                log.error("No free ports available - all connections in use");
                tunnelServer.closeUserServerContext(userServerContext);
                return ResponseEntity.status(500).body("No free ports available - all connections in use\n");
            }

            userServerContext.setRequestQueue(new LinkedBlockingQueue<>(200));
            userServerContext.setRequestSocketMap(new HashMap<>());
            try {
                userServerContext.setServerSocket(new ServerSocket(userServerContext.getServerPort()));
            } catch (IOException e) {
                log.error("Error listening on port {}", userServerContext.getServerPort());
                tunnelServer.closeUserServerContext(userServerContext);
                return ResponseEntity.status(500).body("Error listening on port " + userServerContext.getServerPort() + "\n");
            }
            MDC.put(Constants.MDC_TUNNEL_SERVER_PORT, String.valueOf(userServerContext.getServerPort()));

            userServerContext.setServerThread(new Thread(() -> tunnelServer.userServerContextListener(userServerContext)));
            userServerContext.getServerThread().setName("Listener-" + userServerContext.getServerPort());
            userServerContext.getServerThread().start();

            log.info("Registered user {}, Listening on {}", userServerContext.getUserId(), userServerContext.getServerPort());
            response.addHeader(Constants.X_TUNNEL_SERVER_PORT, String.valueOf(userServerContext.getServerPort()));
            return ResponseEntity.ok("server-port=" + userServerContext.getServerSocket().getLocalPort() + "\n");
        } finally {
            MDC.clear();
        }
    }

    /**
     * Stop listening for the given userId.
     */
    @GetMapping("/close")
    public ResponseEntity<String> closeUserServerContext(HttpServletRequest request, HttpServletResponse response) throws IOException {
        int port;
        try {
            MDC.put(Constants.MDC_REQUEST_STAGE, Constants.STAGE_CLOSE);
            UserServerContext userServerContext = tunnelServer.getUserServerContext(request, response);
            if (userServerContext == null) {
                return null;
            }
            MDC.put(Constants.MDC_USER_ID, userServerContext.getUserId());
            port = userServerContext.getServerPort();
            log.info("Closed server for user {}, was listening on {}", userServerContext.getUserId(), userServerContext.getServerPort());
            tunnelServer.closeUserServerContext(userServerContext);
        } finally {
            MDC.clear();
        }
        if (port == 0) {
            return ResponseEntity.status(404).body("server-port-closed=not-found\n");
        }
        return ResponseEntity.ok("server-port-closed=" + port + "\n");
    }
}
