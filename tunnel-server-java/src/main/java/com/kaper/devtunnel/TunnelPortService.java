package com.kaper.devtunnel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * This service will try to find a suitable port number to use for the new registration.
 * The user can pass on a preferred port. If it is available, it will be allowed to use it.
 * If not, then a random port will be used.
 * When all ports are in use, the system will check if we can free up the port which was used
 * the longest time ago, if it is not active anymore.
 * The possible port range is configured in the application.yml; tunnel.startPort and tunnel.endPort.
 */
@Service
public class TunnelPortService {
    private final TunnelServer tunnelServer;

    private final int startPort;
    private final int endPort;

    public TunnelPortService(TunnelServer tunnelServer, @Value("${tunnel.startPort}") int startPort, @Value("${tunnel.endPort}") int endPort) {
        this.tunnelServer = tunnelServer;
        this.startPort = startPort;
        this.endPort = endPort;
    }

    /**
     * Get a port to be used for the new tunnel to listen on.
     * A user can pass in the port he/she used the last time, to see if they can get the same one again.
     */
    public int requestNewPort(Integer preferredPort) {
        // Get current list of open ports.
        Map<String, UserServerContext> activeMappings = tunnelServer.getCopyOfUserServerContextMap();

        // Collect which ports are free. Start by adding all possible ports, and then remove the ones from the active list.
        Set<Integer> ports = new HashSet<>();
        for (int i = startPort; i <= endPort; i++) {
            ports.add(i);
        }

        // Remove active ones, and while we are at it, also memorize the oldest entry in case there are no ports left to use.
        // Also see if we encounter a context with the preferred port number. If that one is not active, and no ports are free,
        // we will terminate that one. If any ports are free, you will not get the preferred port, as it now is someone else's
        // preferred port ;-)
        UserServerContext oldestContext = null;
        UserServerContext preferredContext = null;
        for (Map.Entry<String, UserServerContext> entry : activeMappings.entrySet()) {
            UserServerContext context = entry.getValue();
            // Mark entry as not free.
            ports.remove(context.getServerPort());
            if (oldestContext == null || oldestContext.getUserLastSeenTimestampMs() > context.getUserLastSeenTimestampMs()) {
                // Memorize the oldest entry.
                oldestContext = context;
            }
            if (context.getServerPort() == (preferredPort != null ? preferredPort : -1)) {
                // Memorize the one we preferred to use.
                preferredContext = context;
            }
        }

        // All in use?
        if (ports.size() == 0) {
            // oops, we need to kick out one of the existing users, if not active.
            if (preferredContext != null && !preferredContext.wasUserRecentlySeen()) {
                tunnelServer.closeUserServerContext(preferredContext);
                return preferredContext.getServerPort();
            }
            if (oldestContext != null && !oldestContext.wasUserRecentlySeen()) {
                tunnelServer.closeUserServerContext(oldestContext);
                return oldestContext.getServerPort();
            }
            // No ports available.
            return -1;
        }

        // If the user passed on a preferred port, use it if it is still available.
        if (preferredPort != null && ports.contains(preferredPort)) {
            return preferredPort;
        }

        // Take a random left-over port.
        return new ArrayList<>(ports).get(new Random().nextInt(ports.size()));
    }
}
