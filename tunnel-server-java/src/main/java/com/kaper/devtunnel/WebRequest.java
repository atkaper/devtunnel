package com.kaper.devtunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;

/**
 * Container class for having the web request communication Socket, it's headers, and a start timestamp (to allow us to clean-up expired requests).
 */
public class WebRequest {
    private final Socket socket;
    private final Headers headers;
    private final long requestStartedMs = System.currentTimeMillis();

    /**
     * Constructor, will read the header lines from the input stream, but will not read the body.
     * The body will be left to the TunnelDataController to stream from/to the tunnel-client.
     */
    public WebRequest(Socket socket) throws IOException {
        this.socket = socket;
        socket.setSoTimeout(10000);
        this.headers = new Headers(socket.getInputStream());
        socket.setSoTimeout(30000);
        // If we see an "Expect: 100-continue" line, handle it now (saying "OK"), and remove it.
        // We are not going to ask the application, as that would need an extra request/response round-trip.
        String expect = headers.getHeaderValue("Expect");
        if (expect != null && expect.equalsIgnoreCase("100-continue")) {
            headers.removeHeader("Expect");
            socket.getOutputStream().write("HTTP/1.1 100 Continue\n\n".getBytes());
            socket.getOutputStream().flush();
        }
    }

    public void setSoTimeout(int timeoutMs) {
        try {
            socket.setSoTimeout(timeoutMs);
        } catch (SocketException e) {
            // ignore
        }
    }

    public boolean wasStartedTooLongAgo() {
        return (System.currentTimeMillis() - requestStartedMs > (1000 * 30));
    }

    public Headers getHeaders() {
        return headers;
    }

    public SocketAddress getRemoteSocketAddress() {
        return socket.getRemoteSocketAddress();
    }

    public InputStream getInputStream() throws IOException {
        return socket.getInputStream();
    }

    public OutputStream getOutputStream() throws IOException {
        return socket.getOutputStream();
    }

    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            // ignore close issues
        }
    }
}
