package com.kaper.devtunnel;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * This class maintains the list of active connections on the web/server side.
 * It also handles listening on the server, and queueing the web requests for processing by the tunnel-client via the TunnelDataController.
 * For each incoming web request, the HTTP headers are already read when the connection is made.
 * The transfer of the body data is left to the TunnelDataController to not have to read all in memory, but use streaming where possible.
 * The TunnelServer has a cleanup thread, which looks for expired requests, to terminate them.
 */
@Slf4j
@Component
public class TunnelServer {
    /** Map of connected users. */
    private final Map<String, UserServerContext> userServerContextMap = new HashMap<>();

    public TunnelServer() {
        startCleanupThread();
    }

    public void registerUserServerContext(UserServerContext userServerContext) {
        userServerContextMap.put(userServerContext.getUserId(), userServerContext);
    }

    public void closeUserServerContext(UserServerContext userServerContext) {
        if (userServerContext.getServerSocket() != null) {
            userServerContext.terminate();
            try {
                // The cleanup job will close any open ongoing web requests
                // Also give some time to the operating system to stop listening on the server socket.
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        userServerContextMap.remove(userServerContext.getUserId());
    }

    /**
     * Check all open connections to see if the user on the app-side is gone, or if the connection was open for too long.
     * In that case, close the connection.
     */
    private void startCleanupThread() {
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    MDC.put(Constants.MDC_REQUEST_STAGE, "cleanup");
                    // Check once per second
                    Thread.sleep(1000);

                    // Check if we have any users with open requests who appear to be gone... close them.
                    userServerContextMap.forEach((user, userServerContext) -> {
                        MDC.put(Constants.MDC_USER_ID, userServerContext.getUserId());
                        MDC.put(Constants.MDC_TUNNEL_SERVER_PORT, String.valueOf(userServerContext.getServerPort()));
                        new HashMap<>(userServerContext.getRequestSocketMap()).forEach((webRequestId, webRequest) -> {
                            if (webRequest.wasStartedTooLongAgo() || !userServerContext.wasUserRecentlySeen()) {
                                // If the request is getting old, or we have not seen the user for over some time, just send back an error response to the caller.
                                userServerContext.getRequestQueue().remove(webRequestId);
                                userServerContext.getRequestSocketMap().remove(webRequestId);
                                MDC.put(Constants.MDC_WEB_REQUEST_ID, webRequestId);
                                MDC.put(Constants.MDC_REQUESTER, webRequest.getRemoteSocketAddress().toString());
                                log.info("Cleanup {} waiting request {}", userServerContext.getUserId(), webRequest.getRemoteSocketAddress().toString());
                                if (webRequest.wasStartedTooLongAgo()) {
                                    sendErrorResponseToWeb(userServerContext, webRequest, webRequestId, "503 TIMEOUT",
                                            "User " + userServerContext.getUserId() + " took too long to respond");
                                } else {
                                    sendErrorResponseToWeb(userServerContext, webRequest, webRequestId, "503 OFFLINE", "User " + userServerContext.getUserId() + " is offline");
                                }
                                MDC.remove(Constants.MDC_WEB_REQUEST_ID);
                                MDC.remove(Constants.MDC_REQUESTER);
                            }
                        });
                    });
                } catch (InterruptedException ie) {
                    log.warn("cleanup interrupted - shut down");
                } catch (Exception e) {
                    // whatever happens, we never want to stop the cleanup, so just log and continue.
                    // I'm guessing we might run into concurrent modification exceptions, or some other concurrent issue here sometimes.
                    log.error("Error in cleanup thread?", e);
                } finally {
                    MDC.clear();
                }
            }
        });
        cleanupThread.setName("Cleanup-Thread");
        cleanupThread.start();
    }

    /**
     * Find UserServerContext for the userId from the request header.
     * This is called by the tunnel client (app side) get/post methods.
     */
    public UserServerContext getUserServerContext(HttpServletRequest appRequest, HttpServletResponse appResponse) throws IOException {
        String userId = appRequest.getHeader(Constants.X_TUNNEL_USER_ID);
        if (userId == null || userId.trim().length() == 0) {
            appResponse.setStatus(400);
            appResponse.getOutputStream().println("Missing " + Constants.X_TUNNEL_USER_ID);
            return null;
        }
        if (!userServerContextMap.containsKey(userId)) {
            UserServerContext newUserServerContext = new UserServerContext();
            newUserServerContext.setUserId(userId);
            userServerContextMap.put(userId, newUserServerContext);
        }
        UserServerContext userServerContext = userServerContextMap.get(userId);
        userServerContext.setUserLastSeenNow();
        return userServerContext;
    }

    /**
     * The listener thread for the server port for a single userId tunnel.
     */
    public void userServerContextListener(UserServerContext userServerContext) {
        MDC.put(Constants.MDC_USER_ID, userServerContext.getUserId());
        MDC.put(Constants.MDC_TUNNEL_SERVER_PORT, String.valueOf(userServerContext.getServerPort()));

        // Keep listening for incoming requests, and put them in a socketMap and FIFO queue pointing to the map.
        while (userServerContext.getServerPort() != 0) {
            MDC.remove(Constants.MDC_REQUESTER);
            WebRequest webRequest;
            String webRequestId = UUID.randomUUID().toString();
            try {
                Socket socket = userServerContext.getServerSocket().accept();
                webRequestId = webRequestId + "-" + userServerContext.getRequestCount().incrementAndGet();
                MDC.put(Constants.MDC_WEB_REQUEST_ID, webRequestId);
                MDC.put(Constants.MDC_REQUESTER, socket.getRemoteSocketAddress().toString());
                webRequest = new WebRequest(socket);
                log.debug("Got connection: {}, webRequestId: {}, user: {}, request: {}", socket.getRemoteSocketAddress(), webRequestId, userServerContext.getUserId(),
                        webRequest.getHeaders().getFirstLine());
                if (!userServerContext.wasUserRecentlySeen()) {
                    // If we have not seen the user for over some time, just send back an error response to the caller.
                    sendErrorResponseToWeb(userServerContext, webRequest, webRequestId, "503 OFFLINE", "User " + userServerContext.getUserId() + " is offline...");
                    continue;
                }
            } catch (Exception e) {
                // From "accept", should never happen?
                log.warn("Error in accept() - terminating listener (for possible tunnel restart): {}", e.getMessage());
                // Close server, terminate all open connections, and remove from registration.
                userServerContext.getRequestSocketMap().forEach(
                        (webRequestIdToClose, socketToClose) -> sendErrorResponseToWeb(userServerContext, socketToClose, webRequestIdToClose, "503 ERROR",
                                "Unexpected accept error - terminating connections"));
                try {
                    userServerContext.getServerSocket().close();
                } catch (IOException ex) {
                    // ignore
                }
                userServerContextMap.remove(userServerContext.getUserId(), userServerContext);
                break;
            }

            userServerContext.getRequestSocketMap().put(webRequestId, webRequest);
            try {
                boolean offered = userServerContext.getRequestQueue().offer(webRequestId, 5000, TimeUnit.SECONDS);
                if (!offered) {
                    sendErrorResponseToWeb(userServerContext, webRequest, webRequestId, "503 OVERFLOW",
                            "User " + userServerContext.getUserId() + " request queue full...");
                    userServerContext.getRequestSocketMap().remove(webRequestId);
                    continue;
                }
            } catch (InterruptedException e) {
                // from the queue offer, won't happen (maybe only on shutdown)
                sendErrorResponseToWeb(userServerContext, webRequest, webRequestId, "503 ERROR", "User " + userServerContext.getUserId() + " request queue issue...");
                userServerContext.getRequestSocketMap().remove(webRequestId);
            }
            log.debug("map size: {}, queue size: {}, user: {}", userServerContext.getRequestSocketMap().size(), userServerContext.getRequestQueue().size(),
                    userServerContext.getUserId());
        }
    }

    /**
     * Send an error response to the web end of the connection. And close the WebRequest.
     */
    public void sendErrorResponseToWeb(UserServerContext userServerContext, WebRequest webRequest, String webRequestId, String statusCode, String errorMessage) {
        MDC.put(Constants.MDC_TUNNEL_ERROR_COUNT, String.valueOf(userServerContext.getTunnelErrorCount().incrementAndGet()));
        Headers headers = webRequest.getHeaders();
        try {
            if (headers.getContentLength() != null) {
                try {
                    webRequest.setSoTimeout(2000);
                    StreamHelper.streamCopy(webRequest.getInputStream(), headers.getContentLength(), new StreamHelper.DevNullOutputStream(), "webToDevNull");
                } catch (Exception e) {
                    log.error("sendErrorResponseToWeb, issue reading body: {}", e.getMessage());
                }
            }
            log.warn("Send Web Error: {} {}, request: {}", statusCode, errorMessage, headers.getFirstLine());
            byte[] body = (errorMessage + "\n").getBytes();

            Headers responseHeaders = new Headers(statusCode);
            responseHeaders.setHeader(Constants.CONNECTION_HEADER, "Close");
            responseHeaders.setContentLength(body.length);
            responseHeaders.setHeader("Content-Type", "text/plain");
            responseHeaders.addHeader(Constants.X_TUNNEL_USER_ID, userServerContext.getUserId());
            responseHeaders.addHeader(Constants.X_TUNNEL_WEB_REQUEST_ID, webRequestId);
            responseHeaders.addHeader(Constants.X_TUNNEL_SERVER_PORT, String.valueOf(userServerContext.getServerPort()));

            responseHeaders.sendHeaderLines(webRequest.getOutputStream(), "toWeb");
            webRequest.getOutputStream().write(body);
            webRequest.getOutputStream().flush();
        } catch (IOException e) {
            log.error("Error in sendErrorResponseToWeb: {} - to send: {} {}", e.getMessage(), statusCode, errorMessage);
        } finally {
            webRequest.close();
        }
    }

    /**
     * Return shallow copy of userServerContextMap, for reporting purposes.
     */
    public HashMap<String, UserServerContext> getCopyOfUserServerContextMap() {
        return new HashMap<>(userServerContextMap);
    }
}
