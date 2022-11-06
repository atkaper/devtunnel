package com.kaper.devtunnel;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Data;

/**
 * For each port we listen on, we will make an instance of this object.
 */
@Data
public class UserServerContext {
    private String userId = null;
    private int clientVersion;
    private int serverPort = 0;
    private ServerSocket serverSocket = null;
    private Thread serverThread = null;

    private AtomicLong requestCount = new AtomicLong();
    private AtomicInteger activePollCount = new AtomicInteger();
    private AtomicInteger tunnelErrorCount = new AtomicInteger();

    private Map<String, WebRequest> requestSocketMap = new HashMap<>();
    private BlockingQueue<String> requestQueue = new LinkedBlockingQueue<>(200);

    private long userRegisteredTimestampMs = System.currentTimeMillis();
    private long userLastSeenTimestampMs = System.currentTimeMillis();
    private long lastSeenTimeoutMs = 1000 * 30;

    public void setUserLastSeenNow() {
        userLastSeenTimestampMs = System.currentTimeMillis();
    }

    public boolean wasUserRecentlySeen() {
        return (System.currentTimeMillis() - userLastSeenTimestampMs <= lastSeenTimeoutMs);
    }

    public void terminate() {
        requestQueue.clear();
        getRequestSocketMap().forEach((userId, socket) -> socket.close());
        requestSocketMap.clear();
        try {
            serverSocket.close();
        } catch (IOException ex) {
            // ignore
        }
    }
}
