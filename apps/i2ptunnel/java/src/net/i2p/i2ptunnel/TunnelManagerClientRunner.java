/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.StringTokenizer;

import net.i2p.util.Log;

/**
 * Runner thread that reads commands from the socket and fires off commands to
 * the TunnelManager
 *
 */
class TunnelManagerClientRunner implements Runnable {
    private final static Log _log = new Log(TunnelManagerClientRunner.class);
    private TunnelManager _mgr;
    private Socket _clientSocket;

    public TunnelManagerClientRunner(TunnelManager mgr, Socket socket) {
        _clientSocket = socket;
        _mgr = mgr;
    }

    public void run() {
        _log.debug("Client running");
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(_clientSocket.getInputStream()));
            OutputStream out = _clientSocket.getOutputStream();

            String cmd = reader.readLine();
            if (cmd != null) processCommand(cmd, out);
        } catch (IOException ioe) {
            _log.error("Error processing client commands", ioe);
        } finally {
            if (_clientSocket != null) try {
                _clientSocket.close();
            } catch (IOException ioe) {
            }
        }
        _log.debug("Client closed");
    }

    /**
     * Parse the command string and fire off the appropriate tunnelManager method,
     * sending the results to the output stream
     */
    private void processCommand(String command, OutputStream out) throws IOException {
        _log.debug("Processing [" + command + "]");
        StringTokenizer tok = new StringTokenizer(command);
        if (!tok.hasMoreTokens()) {
            _mgr.unknownCommand(command, out);
        } else {
            String cmd = tok.nextToken();
            if ("quit".equalsIgnoreCase(cmd)) {
                _mgr.processQuit(out);
            } else if ("lookup".equalsIgnoreCase(cmd)) {
                if (tok.hasMoreTokens())
                    _mgr.processLookup(tok.nextToken(), out);
                else
                    _mgr.error("Usage: lookup <hostname>", out);
            } else if ("testdestination".equalsIgnoreCase(cmd)) {
                if (tok.hasMoreTokens())
                    _mgr.processTestDestination(tok.nextToken(), out);
                else
                    _mgr.error("Usage: testdestination <publicDestination>", out);
            } else if ("convertprivate".equalsIgnoreCase(cmd)) {
                if (tok.hasMoreTokens())
                    _mgr.processConvertPrivate(tok.nextToken(), out);
                else
                    _mgr.error("Usage: convertprivate <privateData>", out);
            } else if ("close".equalsIgnoreCase(cmd)) {
                if (tok.hasMoreTokens()) {
                    String closeArg;
                    if ((closeArg = tok.nextToken()).equals("forced")) {
                        if (tok.hasMoreTokens()) {
                            _mgr.processClose(tok.nextToken(), true, out);
                        } else {
                            _mgr.error("Usage: close [forced] <jobnumber>|all", out);
                        }
                    } else {
                        _mgr.processClose(closeArg, false, out);
                    }
                } else {
                    _mgr.error("Usage: close [forced] <jobnumber>|all", out);
                }
            } else if ("genkey".equalsIgnoreCase(cmd)) {
                _mgr.processGenKey(out);
            } else if ("list".equalsIgnoreCase(cmd)) {
                _mgr.processList(out);
            } else if ("listen_on".equalsIgnoreCase(cmd)) {
                if (tok.hasMoreTokens()) {
                    _mgr.processListenOn(tok.nextToken(), out);
                } else {
                    _mgr.error("Usage: listen_on <ip>", out);
                }
            } else if ("openclient".equalsIgnoreCase(cmd)) {
                int listenPort = 0;
                String peer = null;
                if (!tok.hasMoreTokens()) {
                    _mgr.error("Usage: openclient <listenPort> <peer>", out);
                    return;
                }
                try {
                    String portStr = tok.nextToken();
                    listenPort = Integer.parseInt(portStr);
                } catch (NumberFormatException nfe) {
                    _mgr.error("Bad listen port", out);
                    return;
                }
                if (!tok.hasMoreTokens()) {
                    _mgr.error("Usage: openclient <listenport> <peer>", out);
                    return;
                }
                peer = tok.nextToken();
                _mgr.processOpenClient(listenPort, peer, out);
            } else if ("openhttpclient".equalsIgnoreCase(cmd)) {
                int listenPort = 0;
                String proxy = "squid.i2p";
                if (!tok.hasMoreTokens()) {
                    _mgr.error("Usage: openhttpclient <listenPort> [<proxy>]", out);
                    return;
                }
                try {
                    String portStr = tok.nextToken();
                    listenPort = Integer.parseInt(portStr);
                } catch (NumberFormatException nfe) {
                    _mgr.error("Bad listen port", out);
                    return;
                }
                if (tok.hasMoreTokens()) {
                    proxy = tok.nextToken();
                }
                if (tok.hasMoreTokens()) {
                    _mgr.error("Usage: openclient <listenport> [<proxy>]", out);
                    return;
                }
                _mgr.processOpenHTTPClient(listenPort, proxy, out);
            } else if ("opensockstunnel".equalsIgnoreCase(cmd)) {
                int listenPort = 0;
                if (!tok.hasMoreTokens()) {
                    _mgr.error("Usage: opensockstunnel <listenPort>", out);
                    return;
                }
                try {
                    String portStr = tok.nextToken();
                    listenPort = Integer.parseInt(portStr);
                } catch (NumberFormatException nfe) {
                    _mgr.error("Bad listen port", out);
                    return;
                }
                if (tok.hasMoreTokens()) {
                    _mgr.error("Usage: opensockstunnel <listenport>", out);
                    return;
                }
                _mgr.processOpenSOCKSTunnel(listenPort, out);
            } else if ("openserver".equalsIgnoreCase(cmd)) {
                int listenPort = 0;
                String serverHost = null;
                String serverKeys = null;
                if (!tok.hasMoreTokens()) {
                    _mgr.error("Usage: openserver <serverHost> <serverPort> <serverKeys>", out);
                    return;
                }
                serverHost = tok.nextToken();

                if (!tok.hasMoreTokens()) {
                    _mgr.error("Usage: openserver <serverHost> <serverPort> <serverKeys>", out);
                    return;
                }
                try {
                    String portStr = tok.nextToken();
                    listenPort = Integer.parseInt(portStr);
                } catch (NumberFormatException nfe) {
                    _mgr.error("Bad listen port", out);
                    return;
                }
                if (!tok.hasMoreTokens()) {
                    _mgr.error("Usage: openserver <serverHost> <serverPort> <serverKeys>", out);
                    return;
                }
                serverKeys = tok.nextToken();
                _mgr.processOpenServer(serverHost, listenPort, serverKeys, out);
            } else {
                _mgr.unknownCommand(command, out);
            }
        }
    }
}