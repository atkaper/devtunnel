package com.kaper.devtunnel;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tunnel Data Controller.
 * This controller will be used by the tunnel client on the application side of the tunnel.
 * You can receive and send data using the GET and POST long-poll methods in here.
 */
@Slf4j
@RestController
public class TunnelDataController {
    private final TunnelServer tunnelServer;

    public TunnelDataController(TunnelServer tunnelServer) {
        this.tunnelServer = tunnelServer;
    }

    /**
     * This method will be called from the app side, by the tunnel client application.
     * It will call this to start waiting for a web request (long poll). If a web request comes in, it will be sent on to the app,
     * together with a request-id header. The tunnel client will pass the request on to the real app, and
     * the app response will be handled by the longPollerPostAppToWebResponse (POST /data) method.
     * The long-poll wait will time out in 30 seconds, with status code 204. The tunnel client just has to re-execute this
     * same request again, to keep waiting for the web request. This timeout is set to 30 seconds, as some networks might
     * not keep the connection open for longer than a minute.
     */
    @GetMapping("/data")
    public void longPollerGetWebToAppRequest(HttpServletRequest appRequest, HttpServletResponse appResponse) throws InterruptedException, IOException {
        UserServerContext userServerContext = tunnelServer.getUserServerContext(appRequest, appResponse);
        if (userServerContext == null) {
            return;
        }
        if (userServerContext.getServerSocket() == null || userServerContext.getServerPort() == 0) {
            // no port/server? then a server restart has cleared the list...
            tunnelServer.closeUserServerContext(userServerContext);
            appResponse.addHeader(Constants.X_TUNNEL_STATUS, "Tunnel user not Found - Please restart tunnel client");
            appResponse.setStatus(404);
            return;
        }
        setMdcContext(userServerContext, Constants.STAGE_WEB_TO_APP_LISTEN, true);
        appResponse.addHeader(Constants.X_TUNNEL_USER_ID, userServerContext.getUserId());
        appResponse.addHeader(Constants.X_TUNNEL_SERVER_PORT, String.valueOf(userServerContext.getServerPort()));

        try {
            String webRequestId = waitForWebToAppRequest(userServerContext);
            if (webRequestId == null) {
                // Not sending back "X-Tunnel-Status" header, as this is a normal flow case.
                appResponse.setStatus(204);
                return;
            }
            MDC.put(Constants.MDC_WEB_REQUEST_ID, webRequestId);
            WebRequest webRequest = userServerContext.getRequestSocketMap().get(webRequestId);
            setMdcWebRequestContext(webRequest);
            log.debug("longPollerGetWebToAppRequest - request webRequestId: {}, picking up request: {}, request: {}",
                    webRequestId, webRequest.getRemoteSocketAddress(), webRequest.getHeaders().getFirstLine());
            appResponse.addHeader(Constants.X_TUNNEL_WEB_REQUEST_ID, webRequestId);

            Headers webRequestHeaders = webRequest.getHeaders();
            if (!webRequestHeaders.hasHeaderLines()) {
                // this happens when a browser keeps an open connection, but does not send data, and closes it unused.
                log.info("longPollerGetWebToAppRequest - no headers, just closing connection");
                webRequest.close();
                userServerContext.getRequestSocketMap().remove(webRequestId);
                sendErrorResponseToApp(appResponse, userServerContext, webRequestId, "No web request headers");
                return;
            }
            // Sending this request line as extra header is a bit double, but on the tunnel-client, we do not want to
            // parse the body stream to read this same data. The body stream will be sent directly to the APP at the users machine.
            // To allow the tunnel client to log for which request something happens, we add this as extra header.
            appResponse.addHeader(Constants.X_TUNNEL_WEB_REQUEST, webRequestHeaders.getFirstLine());

            // TODO add support for chunked transfer... When we do, check userServerContext.getClientVersion() for version >= 2, we start at 1 without chunking.
            String transferEncoding = webRequestHeaders.getHeaderValue("Transfer-Encoding");
            if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
                // Not yet, sorry...
                tunnelServer.sendErrorResponseToWeb(userServerContext, webRequest, webRequestId, "400 ILLEGAL_REQUEST", "Transfer-Encoding chunked not yet supported");
                userServerContext.getRequestSocketMap().remove(webRequestId);
                sendErrorResponseToApp(appResponse, userServerContext, webRequestId, "Transfer-Encoding chunked not yet supported");
                return;
            }

            Integer webRequestBodyLength = webRequestHeaders.getContentLength();
            if (webRequestBodyLength == null) {
                // For a GET request this is normal ;-) For a chunked post, I will need to change this a bit.
                log.debug("No content length, assuming 0. Request: {}", webRequestHeaders.getFirstLine());
                webRequestBodyLength = 0;
            }
            MDC.put(Constants.MDC_WEB_TO_APP_BODY_BYTES, webRequestBodyLength.toString());

            webRequestHeaders.setContentLength(webRequestBodyLength);
            webRequestHeaders.setHeader(Constants.CONNECTION_HEADER, Constants.CONNECTION_CLOSE_VALUE);

            appResponse.setContentType("application/octet-stream");
            appResponse.setContentLength(webRequestHeaders.sendHeaderLineByteCount() + webRequestBodyLength);
            webRequestHeaders.sendHeaderLines(appResponse.getOutputStream(), Constants.DIRECTION_WEB_TO_APP_REQUEST);
            StreamHelper.streamCopy(webRequest.getInputStream(), webRequestBodyLength, appResponse.getOutputStream(), Constants.DIRECTION_WEB_TO_APP_REQUEST);
            log.info("Handled webToAppRequest: {}, body bytes: {}", webRequestHeaders.getFirstLine(), webRequestBodyLength);
            userServerContext.setUserLastSeenNow();
        } finally {
            MDC.clear();
            userServerContext.getActivePollCount().decrementAndGet();
        }
    }

    /**
     * Setup generic MDC log context with all we know about the userServerContext.
     */
    private static void setMdcContext(UserServerContext userServerContext, String stage, boolean countPoller) {
        MDC.put(Constants.MDC_REQUEST_STAGE, stage);
        MDC.put(Constants.MDC_USER_ID, userServerContext.getUserId());
        MDC.put(Constants.MDC_TUNNEL_SERVER_PORT, String.valueOf(userServerContext.getServerPort()));
        MDC.put(Constants.MDC_ACTIVE_POLL_COUNT, String.valueOf(userServerContext.getActivePollCount().addAndGet(countPoller ? 1 : 0)));
        MDC.put(Constants.MDC_ACTIVE_REQUEST_COUNT, String.valueOf(userServerContext.getRequestQueue().size()));
        MDC.put(Constants.MDC_ACTIVE_CONNECTION_COUNT, String.valueOf(userServerContext.getRequestSocketMap().size()));
        MDC.put(Constants.MDC_TUNNEL_ERROR_COUNT, String.valueOf(userServerContext.getTunnelErrorCount().get()));
    }

    /**
     * Setup extra MDC log context with some WebRequest information.
     */
    private void setMdcWebRequestContext(WebRequest webRequest) {
        MDC.put(Constants.MDC_REQUESTER, webRequest.getRemoteSocketAddress().toString());

        // At work, we use some tracing/informational headers, all starting wih "X-" and ending in "-Id".
        // Let's add those to the MDC log context, if the value is max 80 chars.
        for (String headerName : webRequest.getHeaders().getHeaderNames()) {
            String headerValue = webRequest.getHeaders().getHeaderValue(headerName);
            if (headerName.toLowerCase().startsWith("x-") && headerName.toLowerCase().endsWith("-id")
                    && headerValue != null && headerValue.length() <= 80) {
                MDC.put(headerNameToCamelCase(headerName), headerValue);
            }
        }
    }

    /**
     * Change header like X-CorrelationId to camelcase correlationId, and X-Remote-Addr to remoteAddr.
     */
    private String headerNameToCamelCase(String headerName) {
        return Pattern.compile("-([a-z])")
                .matcher(headerName.toLowerCase().replaceFirst("^x-", ""))
                .replaceAll(match -> match.group().toUpperCase().replaceFirst("^-", ""));
    }

    /**
     * The poll-wait routine. This will wait for 30 seconds to get a fresh web request. On timeout, return null.
     */
    private static String waitForWebToAppRequest(UserServerContext userServerContext) throws InterruptedException {
        log.debug("waitForWebToAppRequest - wait for queue entry");
        userServerContext.setUserLastSeenNow();
        int maxAttempts = 6;
        String webRequestId = null;
        while (webRequestId == null && (maxAttempts--) > 0) {
            webRequestId = userServerContext.getRequestQueue().poll(5, TimeUnit.SECONDS);
            userServerContext.setUserLastSeenNow();
        }
        return webRequestId;
    }

    /**
     * Tell the tunnel client that something went wrong, and that it will need to re-start the poll GET /data request.
     */
    private static void sendErrorResponseToApp(HttpServletResponse appResponse, UserServerContext userServerContext, String webRequestId, String status) {
        userServerContext.getRequestSocketMap().remove(webRequestId);
        appResponse.addHeader(Constants.X_TUNNEL_STATUS, status);
        appResponse.setStatus(204);
    }

    /**
     * The POST data which is sent to this method from the tunnel client at app side, is actually the RESPONSE to be sent to web,
     * which belongs to the previous long-poller response (which was the web request). See also longPollerGetWebToAppRequest description.
     * Note: when this POST is done, it passes control on to longPollerGetWebToAppRequest to wait for a next web request.
     */
    @PostMapping(value = "/data", consumes = "application/octet-stream")
    public void longPollerPostAppToWebResponse(HttpServletRequest appRequest, HttpServletResponse appResponse) throws InterruptedException, IOException {
        UserServerContext userServerContext = tunnelServer.getUserServerContext(appRequest, appResponse);
        if (userServerContext == null) {
            return;
        }
        setMdcContext(userServerContext, Constants.STAGE_APP_TO_WEB_RESPONSE, false);

        try {
            String webRequestId = appRequest.getHeader(Constants.X_TUNNEL_WEB_REQUEST_ID);
            log.debug("longPollerPostAppToWebResponse webRequestId: {}", webRequestId);
            if (webRequestId == null) {
                sendIllegalRequestToApp(appResponse, "Missing " + Constants.X_TUNNEL_WEB_REQUEST_ID);
                return;
            }
            MDC.put(Constants.MDC_WEB_REQUEST_ID, webRequestId);

            WebRequest webRequest = userServerContext.getRequestSocketMap().get(webRequestId);
            if (webRequest == null) {
                sendIllegalRequestToApp(appResponse, "Unknown " + Constants.X_TUNNEL_WEB_REQUEST_ID);
                return;
            }
            setMdcWebRequestContext(webRequest);

            // this first contentLength is the tunnel request size, so it does include the headers to be sent.
            int appRequestBodyLength = appRequest.getContentLength();

            Headers appToWebResponseHeaders = new Headers(appRequest.getInputStream());
            if (!appToWebResponseHeaders.hasHeaderLines() | !appToWebResponseHeaders.getFirstLine().toLowerCase().startsWith("http/")) {
                sendIllegalRequestToApp(appResponse, "Missing response headers?");
                tunnelServer.sendErrorResponseToWeb(userServerContext, webRequest, webRequestId, "503 INVALID_RESPONSE", "Wrong application response, missing headers");
                userServerContext.getRequestSocketMap().remove(webRequestId);
                return;
            }
            appToWebResponseHeaders.addHeader(Constants.X_TUNNEL_USER_ID, userServerContext.getUserId());
            appToWebResponseHeaders.addHeader(Constants.X_TUNNEL_WEB_REQUEST_ID, webRequestId);
            appToWebResponseHeaders.addHeader(Constants.X_TUNNEL_SERVER_PORT, String.valueOf(userServerContext.getServerPort()));
            appToWebResponseHeaders.setHeader(Constants.CONNECTION_HEADER, Constants.CONNECTION_CLOSE_VALUE);

            // remove the header byte count, to just send on the body bytes.
            int webResponseBodyLength = appRequestBodyLength - appToWebResponseHeaders.getHeaderBytesRead();
            if (appToWebResponseHeaders.getContentLength() == null || webResponseBodyLength != appToWebResponseHeaders.getContentLength()) {
                log.debug("Content length mismatch? {} / {} -> set to {}", webResponseBodyLength, appToWebResponseHeaders.getContentLength(), webResponseBodyLength);
                appToWebResponseHeaders.setContentLength(webResponseBodyLength);
            }
            appToWebResponseHeaders.sendHeaderLines(webRequest.getOutputStream(), Constants.DIRECTION_APP_TO_WEB_RESPONSE);
            MDC.put(Constants.MDC_APP_TO_WEB_BODY_BYTES, String.valueOf(webResponseBodyLength));

            try {
                StreamHelper.streamCopy(appRequest.getInputStream(), webResponseBodyLength, webRequest.getOutputStream(), Constants.DIRECTION_APP_TO_WEB_RESPONSE);
            } catch (IOException e) {
                log.error("appToWebResponse stream end? {} / {}", webRequestId, e.getMessage());
            }
            webRequest.close();
            userServerContext.getRequestSocketMap().remove(webRequestId);
            log.info("Handled appToWebResponse: {}, body bytes: {}", appToWebResponseHeaders.getFirstLine(), webResponseBodyLength);
        } finally {
            MDC.clear();
            userServerContext.setUserLastSeenNow();
        }

        // End with the same code as in the GET... wait for a next "request" in a long-poll.
        longPollerGetWebToAppRequest(appRequest, appResponse);
    }

    private static void sendIllegalRequestToApp(HttpServletResponse appResponse, String message) throws IOException {
        appResponse.setStatus(400);
        appResponse.getOutputStream().print(message);
        appResponse.getOutputStream().flush();
    }

}
