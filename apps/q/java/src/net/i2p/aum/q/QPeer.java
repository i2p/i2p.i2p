/*
 * QPeer.java
 *
 * Created on March 28, 2005, 2:13 PM
 */

package net.i2p.aum.q;

import java.io.*;

import net.i2p.*;
import net.i2p.data.*;
import net.i2p.util.*;
import net.i2p.aum.*;

/**
 * Wrapper for a peer record file.
 * Implements a bunch of accessor methods for getting/setting numerical attribs
 */
public class QPeer implements Serializable {

    QNode node;
    protected Destination dest;
    protected String peerId;
    protected String destStr;

    public PropertiesFile file;

    /** Creates a whole new peer */
    public QPeer(QNode node, Destination dest) throws IOException {

        file = new PropertiesFile(node.peersDir + node.sep + node.destToId(dest));

        this.dest = dest;
        destStr = dest.toBase64();
        peerId = node.destToId(dest);
        
        file.setProperty("id", peerId);
        file.setProperty("dest", destStr);
        file.setProperty("timeLastUpdate", "0");
        file.setProperty("timeLastContact", "0");
        file.setProperty("timeNextContact", "0");
    }

    /** Loads an existing peer, barfs if nonexistent */
    public QPeer(QNode node, String destId) throws IOException, DataFormatException {
        
        file = new PropertiesFile(node.peersDir + node.sep + destId);
        
        // barf if file doesn't exist
        if (!file._fileExists) {
            throw new IOException("Missing peer record file");
        }

        destStr = file.getProperty("dest");
        dest = new Destination();
        dest.fromBase64(destStr);
        peerId = destId;
    }

    public Destination getDestination() {
        return dest;
    }

    public String getDestStr() {
        return destStr;
    }
    
    public String getId() {
        return peerId;
    }

    public int getTimeLastUpdate() {
        return new Integer(file.getProperty("timeLastUpdate")).intValue();
    }

    public void setTimeLastUpdate(long when) {
        file.setProperty("timeLastUpdate", String.valueOf(when));
    }

    public int getTimeLastContact() {
        return new Integer(file.getProperty("timeLastContact")).intValue();
    }

    public void setTimeLastContact(int when) {
        file.setProperty("timeLastContact", String.valueOf(when));
    }

    public int getTimeNextContact() {
        return new Integer(file.getProperty("timeNextContact")).intValue();
    }

    public void setTimeNextContact(int when) {
        file.setProperty("timeNextContact", String.valueOf(when));
    }
    
    public boolean hasBeenGreeted() {
        return file.containsKey("sentHello");
    }

    public void markAsGreeted() {
        file.setProperty("sentHello", "1");
    }
}

