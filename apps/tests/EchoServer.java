/*
 * A Minimal echo server.
 *
 * Copyright (c) 2004 Michael Schierl
 *
 * Licensed unter GNU General Public License.
 */

import java.io.*;
import java.net.*;

public class EchoServer extends Thread {

    public static void main(String[] args) throws IOException {
        ServerSocket ss = new ServerSocket(Integer.parseInt(args[0]));
        while (true) {
            Socket s = ss.accept();
            new EchoServer(s);
        }
    }

    private Socket s;
    
    public EchoServer(Socket s) {
        this.s=s;
        start();
    }

    public void run() {
        try {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] b = new byte[4096];
            int len;
            while ((len = in.read(b)) != -1) {
                out.write(b, 0, len);
            }
        } catch (SocketException ex) {
            // nothing
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
}
