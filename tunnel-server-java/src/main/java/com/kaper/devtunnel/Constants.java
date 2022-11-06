package com.kaper.devtunnel;

public class Constants {
    public static final String X_TUNNEL_CLIENT_VERSION = "X-Tunnel-Client-Version";
    public static final String X_TUNNEL_USER_ID = "X-Tunnel-User-Id";
    public static final String X_TUNNEL_PREFERRED_PORT = "X-Tunnel-Preferred-Port";
    public static final String X_TUNNEL_WEB_REQUEST_ID = "X-Tunnel-Request-Id";
    public static final String X_TUNNEL_WEB_REQUEST = "X-Tunnel-Request";
    public static final String X_TUNNEL_SERVER_PORT = "X-Tunnel-Server-Port";
    public static final String X_TUNNEL_STATUS = "X-Tunnel-Status";

    public static final String CONNECTION_HEADER = "Connection";
    public static final String CONNECTION_CLOSE_VALUE = "close";

    public static final String MDC_REQUEST_STAGE = "stage";
    public static final String MDC_USER_ID = "userId";
    public static final String MDC_REQUESTER = "requester";
    public static final String MDC_WEB_REQUEST_ID = "requestUuid";
    public static final String MDC_TUNNEL_SERVER_PORT = "tunnelServerPort";
    public static final String MDC_WEB_TO_APP_BODY_BYTES = "webToAppBodyBytes";
    public static final String MDC_APP_TO_WEB_BODY_BYTES = "appToWebBodyBytes";
    public static final String MDC_ACTIVE_POLL_COUNT = "activePollCount";
    public static final String MDC_ACTIVE_REQUEST_COUNT = "activeRequestCount";
    public static final String MDC_ACTIVE_CONNECTION_COUNT = "activeConnectionCount";
    public static final String MDC_TUNNEL_ERROR_COUNT = "tunnelErrorCount";

    public static final String STAGE_WEB_TO_APP_LISTEN = "web-to-app-listen";
    public static final String STAGE_APP_TO_WEB_RESPONSE = "app-to-web-response";
    public static final String STAGE_REGISTER = "register";
    public static final String STAGE_CLOSE = "close";
    public static final String DIRECTION_WEB_TO_APP_REQUEST = "webToAppRequest";
    public static final String DIRECTION_APP_TO_WEB_RESPONSE = "appToWebResponse";
}
