package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

import net.i2p.I2PException;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.LogManager;
import net.i2p.I2PAppContext;

/**
 * ATalk - anonymous talk, demonstrating a trivial I2P usage scenario.
 * Run this class with no arguments for a manual.
 *
 * @author jrandom
 */
public class ATalk implements I2PSessionListener, Runnable {
    /** logging hook - status messages are piped to this */
    private final static Log _log = new Log(ATalk.class);
    /** platform independent newline */
    private final static String NL = System.getProperty("line.separator");
    /** the current session */
    private I2PSession _session;
    /** who am i */
    private Destination _myDestination;
    /** who are you? */
    private Destination _peerDestination;
    /** location of my secret key file */
    private String _myKeyFile;
    /** location of their public key */
    private String _theirDestinationFile;
    /** where the application reads input from.  currently set to standard input */
    private BufferedReader _in;
    /** where the application sends output to.  currently set to standard output */
    private BufferedWriter _out;
    /** string that messages must begin with to be treated as files */
    private final static String FILE_COMMAND = ".file: ";
    /** the, erm, manual */
    private final static String MANUAL = "ATalk: Anonymous Talk, a demo program for the Invisible Internet Project SDK"
                                         + NL
                                         + "To generate a new destination:"
                                         + NL
                                         + "\tATalk [fileToSavePrivateKeyIn] [fileToSavePublicKeyIn]"
                                         + NL
                                         + "To talk to another destination:"
                                         + NL
                                         + "\tATalk [myPrivateKeyFile] [peerPublicKey] [shouldLogToScreen]"
                                         + NL
                                         + "shouldLogToScreen is 'true' or 'false', depending on whether you want log info on the screen"
                                         + NL
                                         + "When talking to another destination, messages are sent after you hit return"
                                         + NL
                                         + "To send a file, send a message saying:"
                                         + NL
                                         + "\t"
                                         + FILE_COMMAND
                                         + "[filenameToSend]"
                                         + NL
                                         + "The peer will then recieve the file and be notified of where it has been saved"
                                         + NL
                                         + "To end the talk session, enter a period on a line by itself and hit return"
                                         + NL;
    public final static String PROP_CONFIG_LOCATION = "configFile";
    private static final SimpleDateFormat _fmt = new SimpleDateFormat("hh:mm:ss.SSS");

    /** Construct the talk engine, but don't connect yet */
    public ATalk(String myKeyFile, String theirDestFile) {
        _myKeyFile = myKeyFile;
        _theirDestinationFile = theirDestFile;
    }

    /** Actually start up the connection to the I2P network.
     * Successful connect does not mean the peer is online or reachable.
     *
     * @throws IOException if there is a problem reading in the keys from the files specified
     * @throws DataFormatException if the key files are not in the valid format
     * @throws I2PSessionException if there is a problem contacting the I2P router
     */
    public void connect() throws IOException, I2PSessionException, DataFormatException {
        I2PClient client = I2PClientFactory.createClient();
        File myFile = new File(_myKeyFile);
        Properties props = new Properties();
        String configLocation = System.getProperty(PROP_CONFIG_LOCATION, "atalk.config");
        try {
            props.load(new FileInputStream(configLocation));
        } catch (FileNotFoundException fnfe) {
            _log.warn("Unable to load up the ATalk config file " + configLocation);
        }
        // Provide any router or client API configuration here.
        if (!props.containsKey(I2PClient.PROP_TCP_HOST)) props.setProperty(I2PClient.PROP_TCP_HOST, "localhost");
        if (!props.containsKey(I2PClient.PROP_TCP_PORT)) props.setProperty(I2PClient.PROP_TCP_PORT, "7654");
        if (!props.containsKey(I2PClient.PROP_RELIABILITY))
                                                           props.setProperty(I2PClient.PROP_RELIABILITY,
                                                                             I2PClient.PROP_RELIABILITY_BEST_EFFORT);
        _session = client.createSession(new FileInputStream(myFile), props);
        _session.setSessionListener(this);
        _session.connect();

        File peerDestFile = new File(_theirDestinationFile);
        _peerDestination = new Destination();
        _peerDestination.readBytes(new FileInputStream(peerDestFile));
        return;
    }

    /** Actual bulk processing of the application, reading in user input, 
     * sending messages, and displaying results.  When this function exits, the
     * application is complete.
     *
     */
    public void run() {
        try {
            connect();
            _in = new BufferedReader(new InputStreamReader(System.in));
            _out = new BufferedWriter(new OutputStreamWriter(System.out));

            _out.write("Starting up anonymous talk session" + NL);

            while (true) {
                String line = _in.readLine();
                if ((line == null) || (line.trim().length() <= 0)) continue;
                if (".".equals(line)) {
                    boolean ok = _session.sendMessage(_peerDestination, ("Peer disconnected at " + now()).getBytes());
                    // ignore ok, we're closing
                    break;
                }
                if (line.startsWith(FILE_COMMAND) && (line.trim().length() > FILE_COMMAND.length())) {
                    try {
                        String file = line.substring(FILE_COMMAND.length());
                        boolean sent = sendFile(file);
                        if (!sent) {
                            _out.write("Failed sending the file: " + file + NL);
                        }
                    } catch (IOException ioe) {
                        _out.write("Error sending the file: " + ioe.getMessage() + NL);
                        _log.error("Error sending the file", ioe);
                    }
                } else {
                    boolean ok = _session.sendMessage(_peerDestination, ("[" + now() + "] " + line).getBytes());
                    if (!ok) {
                        _out.write("Failed sending message.  Peer disconnected?" + NL);
                    }
                }
            }
        } catch (IOException ioe) {
            _log.error("Error running", ioe);
        } catch (I2PSessionException ise) {
            _log.error("Error communicating", ise);
        } catch (DataFormatException dfe) {
            _log.error("Peer destination file is not valid", dfe);
        } finally {
            try {
                _log.debug("Exiting anonymous talk session");
                if (_out != null) _out.write("Exiting anonymous talk session");
            } catch (IOException ioe) {
                // ignored
            }
            if (_session != null) {
                try {
                    _session.destroySession();
                } catch (I2PSessionException ise) {
                    // ignored
                }
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ie) {
            }
        }
    }

    private String now() {
        Date now = new Date(Clock.getInstance().now());
        return _fmt.format(now);
    }

    /** Send the given file to the current peer.  This works by sending a message
     * saying ".file: filename\nbodyOfFile", where filename is the name of the file
     * (which the recipient will be shown), and the bodyOfFile is the set of raw 
     * bytes in the file.
     *
     * @throws IOException if the file could not be found or read
     * @return false if the file could not be sent to the peer
     */
    private boolean sendFile(String filename) throws IOException, I2PSessionException {
        _log.debug("Sending file [" + filename + "]");
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        baos.write((FILE_COMMAND + filename + "\n").getBytes());
        FileInputStream fin = new FileInputStream(filename);
        byte buf[] = new byte[4096];
        try {
            while (true) {
                int len = fin.read(buf);
                if (len == -1) break;
                baos.write(buf, 0, len);
            }
        } catch (IOException ioe) {
            _log.debug("Failed reading the file", ioe);
            return false;
        }
        baos.close();
        byte val[] = baos.toByteArray();
        _log.debug("Sending " + filename + " with a full payload of " + val.length);
        try {
            boolean rv = _session.sendMessage(_peerDestination, val);
            _log.debug("Sending " + filename + " complete: rv = " + rv);
            return rv;
        } catch (Throwable t) {
            _log.error("Error sending file", t);
            return false;
        }
    }

    /** I2PSessionListener.messageAvailable requires this method to be called whenever
     * I2P wants to tell the session that a message is available.  ATalk always grabs
     * the message immediately and either processes it as a "send file" command (passing
     * it off to handleRecieveFile(..) or simply displays the
     * message to the user.
     *
     */
    public void messageAvailable(I2PSession session, int msgId, long size) {
        _log.debug("Message available: id = " + msgId + " size = " + size);
        try {
            byte msg[] = session.receiveMessage(msgId);
            // inefficient way to just read the first line of text, but its easy 
            BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(msg)));
            String line = reader.readLine();
            if (line.startsWith(FILE_COMMAND)) {
                handleRecieveFile(line, msg);
            } else {
                // not a file command, so just plop 'er out on the screen
                _out.write(now() + " --> " + new String(msg));
                _out.write(NL);
                _out.flush();
            }
        } catch (I2PSessionException ise) {
            _log.error("Error fetching available message", ise);
        } catch (IOException ioe) {
            _log.error("Error writing out the message", ioe);
        }
    }

    /** React to a file being sent our way from the peer via {@link #sendFile sendFile} 
     * by saving the file to a temporary location and displaying where, what, and how large 
     * it is.
     *
     * @param firstline the first line of the message that, according to the sendFile 
     *			implementation, contains the command and the filename that it was stored 
     *			at on the peer's computer
     * @param msg the entire message recieved, including the firstline
     */
    private void handleRecieveFile(String firstline, byte msg[]) throws IOException {
        _log.debug("handleRecieveFile called");
        File f = File.createTempFile("recieve", ".dat", new File("."));
        FileOutputStream fos = new FileOutputStream(f);
        int lineLen = firstline.getBytes().length + "\n".getBytes().length;
        int lenToCopy = msg.length - lineLen;
        byte buf[] = new byte[lenToCopy];
        System.arraycopy(msg, lineLen, buf, 0, lenToCopy);
        fos.write(buf);
        fos.close();
        String name = firstline.substring(FILE_COMMAND.length());
        _out.write("Recieved a file called [" + name + "] of size [" + lenToCopy + "] bytes, saved as ["
                   + f.getAbsolutePath() + "]" + NL);
        _out.flush();
    }

    /** driver */
    public static void main(String args[]) {
        I2PAppContext context = new I2PAppContext();
        if (args.length == 2) {
            String myKeyFile = args[0];
            String myDestinationFile = args[1];
            boolean success = generateKeys(myKeyFile, myDestinationFile);
            if (success)
                _log.debug("Keys generated (private key file: " + myKeyFile + " destination file: " + myDestinationFile
                           + ")");
            else
                _log.debug("Keys generation failed");
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ie) {
            }
        } else if (args.length == 3) {
            _log.debug("Starting chat");
            String myKeyfile = args[0];
            String peerDestFile = args[1];
            String shouldLog = args[2];
            if (Boolean.TRUE.toString().equalsIgnoreCase(shouldLog))
                context.logManager().setDisplayOnScreen(true);
            else
                context.logManager().setDisplayOnScreen(false);
            String logFile = args[2];
            Thread talkThread = new I2PThread(new ATalk(myKeyfile, peerDestFile));
            talkThread.start();
        } else {
            System.out.println(MANUAL);
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ie) {
            }
            System.exit(-1);
        }
    }

    /** Generate a new Destination, saving that destination and the associated 
     * private keys in the privKeyFile, and also saving the destination without
     * any private keys to destinationFile.
     *
     * @param privKeyFile private key file, including the destination and the various
     *			    private keys, as defined by {@link I2PClient#createDestination I2PClient.createDestination}
     * @param destinationFile file in which the Destination is serialized in
     */
    private static boolean generateKeys(String privKeyFile, String destinationFile) {
        try {
            Destination d = I2PClientFactory.createClient().createDestination(new FileOutputStream(privKeyFile));
            FileOutputStream fos = new FileOutputStream(destinationFile);
            d.writeBytes(fos);
            fos.flush();
            fos.close();
            return true;
        } catch (IOException ioe) {
            _log.error("Error generating keys", ioe);
        } catch (I2PException ipe) {
            _log.error("Error generating keys", ipe);
        }
        return false;
    }

    /** required by {@link I2PSessionListener I2PSessionListener} to notify of disconnect */
    public void disconnected(I2PSession session) {
        _log.debug("Disconnected");
    }

    /** required by {@link I2PSessionListener I2PSessionListener} to notify of error */
    public void errorOccurred(I2PSession session, String message, Throwable error) {
        _log.debug("Error occurred: " + message, error);
    }

    /** required by {@link I2PSessionListener I2PSessionListener} to notify of abuse */
    public void reportAbuse(I2PSession session, int severity) {
        _log.debug("Abuse reported of severity " + severity);
    }
}