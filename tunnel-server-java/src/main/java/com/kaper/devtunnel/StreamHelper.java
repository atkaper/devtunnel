package com.kaper.devtunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import lombok.extern.slf4j.Slf4j;

/**
 * Stream utility class.
 */
@Slf4j
public class StreamHelper {
    private StreamHelper() {
       // no instances, just a helper class.
    }

    /**
     * Copy an exact number of bytes from in to out stream.
     */
    public static void streamCopy(InputStream in, int length, OutputStream out, String direction) throws IOException {
        log.debug("streamCopy {} todo {}", direction, length);
        int bytesToSend = length;
        byte[] buffer = new byte[10 * 1024 * 1024]; // 10 MB buffer
        int blockLength;
        int copyLength = Math.min(buffer.length, bytesToSend);
        while (bytesToSend > 0 && (blockLength = in.read(buffer, 0, copyLength)) > 0) {
            log.debug("streamCopy {} bytes {}", direction, blockLength);
            out.write(buffer, 0, blockLength);
            bytesToSend = bytesToSend - blockLength;
            copyLength = Math.min(buffer.length, bytesToSend);
        }
        log.debug("streamCopy {} done {}", direction, length);
    }

    /**
     * Equivalent of /dev/null to absorb an input stream and throw it away.
     */
    public static class DevNullOutputStream extends OutputStream {
        @Override
        public void write(int b) {
            // just absorb input..
        }
    }
}
