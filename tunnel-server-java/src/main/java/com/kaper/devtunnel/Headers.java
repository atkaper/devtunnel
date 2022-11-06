package com.kaper.devtunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Class representing request or response headers.
 * And contains some utility methods to work with headers.
 */
@Slf4j
public class Headers {
    private List<String> headerLines = new ArrayList<>();
    private int headerBytesRead = 0;

    /**
     * Construct response header set.
     * Used for creating error responses.
     */
    public Headers(String httpResponseStatus) {
        headerLines.add("HTTP/1.1 " + httpResponseStatus);
    }

    /**
     * Construct header set from an input stream. This can both be request or response data.
     * Read input stream up to and including "\r\n\r\n", e.g. the end of header block with the empty line.
     * We do not close the stream, to allow a following stream copy loop to copy the message BODY.
     */
    public Headers(InputStream stream) {
        int bytesRead;
        final byte[] fullRequest = new byte[1_000_000]; // max 1 MB to fit full request
        try {
            // read the stream byte by byte (ugly, but I guess the OS will cache the real incoming network packet anyway).
            while ((bytesRead = stream.read(fullRequest, headerBytesRead, 1)) != -1) {
                headerBytesRead += bytesRead;
                // Check last 4 bytes read for "\r\n\r\n"
                if (headerBytesRead > 4 && (fullRequest[headerBytesRead - 4] == '\r'
                        && fullRequest[headerBytesRead - 3] == '\n' && fullRequest[headerBytesRead - 2] == '\r'
                        && fullRequest[headerBytesRead - 1] == '\n')) {
                    // do we have two newlines? if so, we have the full header!
                    log.debug("Header size: {}", headerBytesRead);
                    headerLines = new ArrayList<>(Arrays.asList(new String(fullRequest, 0, headerBytesRead).split("\r\n")));
                    if (log.isDebugEnabled()) {
                        headerLines.forEach(it -> log.debug("Header: " + it));
                    }
                    return;
                }
            }
            log.warn("Unexpected end of stream while reading headers (eof)");
            // Ok, EOF, but then let's use what we have so far. It could be that the last header line is broken...
            log.debug("Header size: {}", headerBytesRead);
            if (headerBytesRead > 0) {
                headerLines = new ArrayList<>(Arrays.asList(new String(fullRequest, 0, headerBytesRead).split("\r\n")));
                if (log.isDebugEnabled()) {
                    headerLines.forEach(it -> log.debug("Header: " + it));
                }
            }
        } catch (IOException e) {
            // This one will probably be more like a remote disconnect.
            log.warn("Unexpected end of stream while reading headers: {}", e.getMessage());
        }
    }

    /**
     * Send header lines to client or server.
     */
    public void sendHeaderLines(OutputStream stream, String direction) {
        StringBuilder data = new StringBuilder();
        for (String line : headerLines) {
            log.debug("{} {}", direction, line);
            data.append(line).append("\r\n");
        }
        data.append("\r\n");
        try {
            stream.write(data.toString().getBytes());
            stream.flush();
        } catch (IOException e) {
            log.info("{} header send error? - {}", direction, e.getMessage());
        }
    }

    public int sendHeaderLineByteCount() {
        int count = 0;
        for (String line : headerLines) {
            count += (line.getBytes().length + 2);
        }
        return count + 2;
    }

    public String getHeaderValue(String headerName) {
        for (String line : headerLines) {
            if (line.toLowerCase().startsWith(headerName.toLowerCase() + ": ")) {
                return line.substring(headerName.length() + 2).trim();
            }
        }
        return null;
    }

    public void setContentLength(int length) {
        setHeader("Content-Length", String.valueOf(length));
    }

    public Integer getContentLength() {
        Integer length = null;
        String contentLength = getHeaderValue("content-length");
        if (contentLength != null) {
            length = Integer.parseInt(contentLength);
        }
        return length;
    }

    public void addHeader(String headerName, String headerValue) {
        headerLines.add(headerName + ": " + headerValue);
    }

    public void setHeader(String headerName, String headerValue) {
        removeHeader(headerName);
        headerLines.add(headerName + ": " + headerValue);
    }

    /**
     * Find given header name, and remove it from the list.
     */
    public void removeHeader(String filterHeader) {
        boolean foundOne = false;
        List<String> result = new ArrayList<>();
        for (String line : headerLines) {
            if (!line.toLowerCase().startsWith(filterHeader.toLowerCase() + ":")) {
                result.add(line);
            } else {
                log.debug("Removing header: {}", line);
                foundOne = true;
            }
        }
        if (!foundOne) {
            return;
        }
        headerLines.clear();
        headerLines.addAll(result);
    }

    public List<String> getHeaderNames() {
        List<String> result = new ArrayList<>();
        for (String line : headerLines) {
            if (line.contains(": ")) {
                result.add(line.replaceFirst(": .*$", "").trim());
            }
        }
        return result;
    }

    public String getFirstLine() {
        return headerLines.stream().findFirst().orElse(null);
    }

    public boolean hasHeaderLines() {
        return !headerLines.isEmpty();
    }

    public int getHeaderBytesRead() {
        return headerBytesRead;
    }
}
