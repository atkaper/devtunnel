package com.kaper.devtunnel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * Status Report Module.
 */
@Slf4j
@Controller
public class TunnelStatusController {
    private final TunnelServer tunnelServer;

    public TunnelStatusController(TunnelServer tunnelServer) {
        this.tunnelServer = tunnelServer;
    }

    @RequestMapping("/")
    public void home(HttpServletResponse response) throws IOException {
        response.sendRedirect("/status");
    }

    /**
     * Status Report. Show active users, and some counts.
     */
    @GetMapping("/status")
    public ModelAndView tunnelStatus() {
        List<ReportLine> report = new ArrayList<>();
        HashMap<String, UserServerContext> mapCopy = tunnelServer.getCopyOfUserServerContextMap();
        mapCopy.forEach((userId, userServerContext) -> report.add(
                new ReportLine(
                        userId, userServerContext.getServerPort(), userServerContext.getRequestQueue().size(), userServerContext.getRequestSocketMap().size(),
                        userServerContext.getActivePollCount().get(), userServerContext.getRequestCount().get(), userServerContext.getTunnelErrorCount().get(),
                        new Date(userServerContext.getUserRegisteredTimestampMs()),
                        new Date(userServerContext.getUserLastSeenTimestampMs()),
                        userServerContext.wasUserRecentlySeen()
                )));
        report.sort((line1, line2) -> {
            if (line1.active && !line2.active) {
                return -1;
            }
            if (!line1.active && line2.active) {
                return 1;
            }
            if (line1.active) {
                return line1.userId.compareTo(line2.userId);
            }
            return line2.lastSeenDate.compareTo(line1.lastSeenDate);
        });
        return new ModelAndView("report").addObject("userCount", mapCopy.size()).addObject("report", report);
    }

    public record ReportLine(
            String userId,
            int serverPort,
            int openRequests,
            int openConnections,
            int activePollCount,
            long totalRequests,
            int totalErrors,
            Date registrationDate,
            Date lastSeenDate,
            boolean active
    ) {
    }
}
