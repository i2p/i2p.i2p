package net.i2p.sam;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * Modified from I2PTunnelHTTPServer
 * Handles UTF-8. Does not buffer past the end of line.
 *
 * @since 0.9.24
 */
class ReadLine {

    private static final int MAX_LINE_LENGTH = 8*1024;

    /**
     *  Read a line teriminated by newline, with a total read timeout.
     *
     *  Warning - strips \n but not \r
     *  Warning - 8KB line length limit as of 0.7.13, @throws IOException if exceeded
     *
     *  @param buf output
     *  @param timeout forever if if zero or negative
     *  @throws SocketTimeoutException if timeout is reached before newline
     *  @throws EOFException if EOF is reached before newline
     *  @throws LineTooLongException if too long
     *  @throws IOException on other errors in the underlying stream
     */
    public static void readLine(Socket socket, StringBuilder buf, int timeout) throws IOException {
        final int origTimeout = timeout;
        int c;
        int i = 0;
        final long expires;
        if (origTimeout > 0) {
            socket.setSoTimeout(timeout);
            expires = System.currentTimeMillis() + timeout;
        } else {
            expires = 0;
        }
        final Reader reader = new UTF8Reader(socket.getInputStream());
        while ( (c = reader.read()) != -1) {
            if (++i > MAX_LINE_LENGTH)
                throw new LineTooLongException("Line too long - max " + MAX_LINE_LENGTH);
            if (c == '\n')
                break;
            if (origTimeout > 0) {
                int newTimeout = (int) (expires - System.currentTimeMillis());
                if (newTimeout <= 0)
                    throw new SocketTimeoutException();
                buf.append((char)c);
                if (newTimeout != timeout) {
                    timeout = newTimeout;
                    socket.setSoTimeout(timeout);
                }
            } else {
                buf.append((char)c);
            }
        }
        if (c == -1) {
            if (origTimeout > 0 && System.currentTimeMillis() >= expires)
                throw new SocketTimeoutException();
            else
                throw new EOFException();
        }
    }

    private static class LineTooLongException extends IOException {
        public LineTooLongException(String s) {
            super(s);
        }
    }
}


