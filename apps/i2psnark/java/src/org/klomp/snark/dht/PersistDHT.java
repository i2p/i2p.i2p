package org.klomp.snark.dht;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;

/**
 *  Retrieve / Store the local DHT in a file
 *
 */
abstract class PersistDHT {

    private static final long MAX_AGE = 60*60*1000;

    public static synchronized void loadDHT(KRPC krpc, File file) {
        Log log = I2PAppContext.getGlobalContext().logManager().getLog(PersistDHT.class);
        int count = 0;
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "ISO-8859-1"));
            String line = null;
            while ( (line = br.readLine()) != null) {
                if (line.startsWith("#"))
                    continue;
                try {
                    krpc.heardAbout(new NodeInfo(line));
                    count++;
                    // TODO limit number? this will flush the router's SDS caches
                } catch (IllegalArgumentException iae) {
                    if (log.shouldLog(Log.WARN))
                        log.warn("Error reading DHT entry", iae);
                } catch (DataFormatException dfe) {
                    if (log.shouldLog(Log.WARN))
                        log.warn("Error reading DHT entry", dfe);
                }
            }
        } catch (IOException ioe) {
            if (log.shouldLog(Log.WARN) && file.exists())
                log.warn("Error reading the DHT File", ioe);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
        if (log.shouldLog(Log.INFO))
            log.info("Loaded " + count + " nodes from " + file);
    }

    public static synchronized void saveDHT(DHTNodes nodes, File file) {
        if (nodes.isEmpty())
            return;
        Log log = I2PAppContext.getGlobalContext().logManager().getLog(PersistDHT.class);
        int count = 0;
        long maxAge = I2PAppContext.getGlobalContext().clock().now() - MAX_AGE;
        PrintWriter out = null;
        try {
            out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(file), "ISO-8859-1")));
            out.println("# DHT nodes, format is NID:Hash:Destination:port");
            for (NodeInfo ni : nodes.values()) {
                 if (ni.lastSeen() < maxAge)
                     continue;
                 // DHTNodes shouldn't contain us, if that changes check here
                 out.println(ni.toPersistentString());
                 count++;
            }
        } catch (IOException ioe) {
            if (log.shouldLog(Log.WARN))
                log.warn("Error writing the DHT File", ioe);
        } finally {
            if (out != null) out.close();
        }
        if (log.shouldLog(Log.INFO))
            log.info("Stored " + count + " nodes to " + file);
    }
}
