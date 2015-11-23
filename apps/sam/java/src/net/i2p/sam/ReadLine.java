package net.i2p.sam;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * Modified from I2PTunnelHTTPServer
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
     *  @param timeout throws SocketTimeoutException immediately if zero or negative
     *  @throws SocketTimeoutException if timeout is reached before newline
     *  @throws EOFException if EOF is reached before newline
     *  @throws LineTooLongException if too long
     *  @throws IOException on other errors in the underlying stream
     */
    public static void readLine(Socket socket, StringBuilder buf, int timeout) throws IOException {
        if (timeout <= 0)
            throw new SocketTimeoutException();
        long expires = System.currentTimeMillis() + timeout;
        InputStreamReader in = new InputStreamReader(socket.getInputStream(), "UTF-8");
        int c;
        int i = 0;
        socket.setSoTimeout(timeout);
        while ( (c = in.read()) != -1) {
            if (++i > MAX_LINE_LENGTH)
                throw new LineTooLongException("Line too long - max " + MAX_LINE_LENGTH);
            if (c == '\n')
                break;
            int newTimeout = (int) (expires - System.currentTimeMillis());
            if (newTimeout <= 0)
                throw new SocketTimeoutException();
            buf.append((char)c);
            if (newTimeout != timeout) {
                timeout = newTimeout;
                socket.setSoTimeout(timeout);
            }
        }
        if (c == -1) {
            if (System.currentTimeMillis() >= expires)
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


