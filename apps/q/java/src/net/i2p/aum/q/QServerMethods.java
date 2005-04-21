/*
 * QServerMethods.java
 *
 * Created on 20 March 2005, 23:23
 */

package net.i2p.aum.q;

import java.lang.*;
import java.util.*;
import java.io.*;


/**
 * Defines the methods which will be exposed in the server's
 * XML-RPC interface. On the xml-rpc client side, these methods are invoked
 * through the 'peerXXXX' methods.
 * This class is just a shim, which invokes methods of the same name on
 * the QServerNode. It's separated off as a shim because the XML-RPC implementation
 * we're using (org.apache.xmlrpc) can only add entire objects and all their
 * methods as handlers, and doesn't support adding a-la-carte methods.
 */
public class QServerMethods {

    private QNode node;

    /**
     * Creates a new instance of QServerMethods,
     * with a ref to the server
     */
    public QServerMethods(QNode node) {
        this.node = node;
    }
    
    /**
     * pings this peer node
     */
    public Hashtable ping() {
        node.nodeLoadAfterHit();
        System.out.println("XMLRPC: ping");
        return node.ping();
    }

    /**
     * pings this peer node
     * @param args a Hashtable (dict, struct, assoc array) of args, all of which are
     * completely ignored
     */
    public Hashtable ping(Hashtable args) {
        return ping();
    }

    /**
     * introduces ourself to this remote peer. From then on, caller will be expected
     * to maintain reasonable uptime
     * @param destStr our own base64 destination
     */
    public Hashtable hello(String destStr) {
        node.nodeLoadAfterHit();
        System.out.println("XMLRPC: hello");
        return node.hello(destStr);
    }

    /**
     * introduces ourself to this remote peer. From then on, caller will be expected
     * to maintain reasonable uptime
     * @param args a Hashtable/dict/struct/assoc-array containing:
     * <ul>
     *  <li>dest - base64 destination (noderef) for the remote peer to add</li>
     * </ul>
     */
    public Hashtable hello(Hashtable args) {
        String destStr;
        System.out.println("XMLRPC: hello");
        try {
            destStr = (String)args.get("dest");
        } catch (Exception e) {
            destStr = null;
        }
        if (destStr == null) {
            Hashtable res = new Hashtable();
            res.put("status", "error");
            res.put("error", "baddest");
            res.put("summary", "Bad or missing destination");
            node.nodeLoadAfterHit();
            return res;
        }
        return hello(destStr);
    }

    /**
     * Searches node for all data items whose metadata keys match the keys
     * of the given mapping.
     * @param criteria a Hashtable (or python dict, etc) of search criteria. Each
     * 'key' is a metadata item to match, and corresponding value is a regular expression
     * to match.
     */
    public Hashtable search(Hashtable criteria) {
        node.nodeLoadAfterHit();
        System.out.println("XMLRPC: search");
        System.out.println("XMLRPC: search: "+criteria);
        return node.search(criteria);
    }

    /**
     * returns a list of new content and/or peers which have
     * been stored on the server since a given time
     * @param since (int) unixtime in seconds
     * @param includePeers (int) set to 1 to include 'peers' list in update, 0 to omit
     * @param includeCatalog (int) set to 1 to include 'items' (catalog) list in
     * update, 0 to omit
     */
    public Hashtable getUpdate(int since, int includePeers, int includeCatalog) {
        node.nodeLoadAfterHit();
        System.out.println("XMLRPC: getUpdate: "+since+" "+includePeers+" "+includeCatalog);
        return node.getUpdate(since, includePeers, includeCatalog);
    }

    /**
     * returns a list of new content and/or peers which have
     * been stored on the server since a given time
     * Wparam args a Hashtable/struct/dict/assoc-array of arguments, including:
     * <ul>
     *   <li>since - (int) unixtime in seconds</li>
     *   <li>includePeers - (int) set to nonzero to include 'peers' list in update, 0 to omit,
     *      default 0</li>
     *   <li>includeCatalog - (int) set to nonzero to include 'items' (catalog) list in
     *      update, 0 to omit (default 0)</li>
     * </ul>
     */
    public Hashtable getUpdate(Hashtable args) {
        int since;
        int includePeers = 0;
        int includeCatalog = 0;

        // uplift 'since' key from args, or barf if invalid
        try {
            since = ((Integer)(args.get("since"))).intValue();
        } catch (Exception e) {
            Hashtable res = new Hashtable();
            res.put("status", "error");
            res.put("error", "badargument");
            res.put("summary", "Invalid value for 'since'");
            node.nodeLoadAfterHit();
            return res;
        }

        // uplift 'includePeers' key from args, silently fall back
        // on default if invalid
        if (args.containsKey("includePeers")) {
            try {
                includePeers = ((Integer)(args.get("includePeers"))).intValue();
            } catch (Exception e) {}
        }

        // uplift 'includeCatalog' key from args, silently fall back
        // on default if invalid
        if (args.containsKey("includeCatalog")) {
            try {
                includeCatalog = ((Integer)(args.get("includeCatalog"))).intValue();
            } catch (Exception e) {}
        }
        return getUpdate(since, includePeers, includeCatalog);
    }

    public Vector getJobsList() throws Exception {
        return node.getJobsList();
    }

    /**
     * attempt to retrieve a data item from remote peer
     * @param key - the key under which the content item is assumedly stored in Q
     */
    public Hashtable getItem(String uri) throws IOException, QException {
        node.nodeLoadAfterHit();
        System.out.println("XMLRPC: getItem: "+uri);
        return node.getItem(uri);
    }

    /**
     * attempt to retrieve a data item from remote peer
     * @param args - a Hashtable/struct/dict/assoc-array, containing:
     *  <ul>
     *    <li>key - (string) the key under which the content item is assumedly stored in Q</li>
     *  </ul>
     */
    public Hashtable getItem(Hashtable args) throws IOException, QException {
        String key;
        try {
            key = (String)args.get("key");
        } catch (Exception e) {
            Hashtable res = new Hashtable();
            res.put("status", "error");
            res.put("error", "badargs");
            node.nodeLoadAfterHit();
            return res;
        }

        return getItem(key);
    }

    /**
     * puts an item of content to remote peer
     * @param args - a Hashtable/struct/dict/assoc-array, containing at least:
     *  <ul>
     *   <li>data - binary - the raw data to insert</li>
     *  </ul>
     * Any other key/value pairs in this struct will be taken as metadata, and
     * inserted into the datastore as such.
     * @return the assigned key for the item, under which the item
     * can be subsequently retrieved. This key will be inserted into 
     * the metadata
     */
    public Hashtable putItem(Hashtable args)
        throws IOException, QException
    {
        byte [] data;
        try {
            data = (byte [])args.get("data");
            args.remove("data");
        } catch (Exception e) {
            Hashtable res = new Hashtable();
            res.put("status", "error");
            res.put("error", "baddata");
            node.nodeLoadAfterHit();
            return res;
        }
        return putItem(args, data);
    }

    /**
     * alternative wrapper method which allows data to be a String.
     * DO NOT USE if the string contains any control chars or bit-7-set chars
     */
    public Hashtable putItem(Hashtable metadata, String data)
        throws IOException, QException
    {
        return putItem(metadata, data.getBytes());
    }

    /**
     * alternative wrapper method which allows data to be a String.
     * DO NOT USE if the string contains any control chars or bit-7-set chars
     */
    public Hashtable putItem(String data)
        throws IOException, QException
    {
        return putItem(data.getBytes());
    }


    /**
     * puts an item of content to remote peer
     * Wparam metadata a mapping object containing metadata
     * @param data raw data to insert
     * @return the assigned key for the item, under which the item
     * can be subsequently retrieved. This key will be inserted into 
     * the metadata
     */
    public Hashtable putItem(Hashtable metadata, byte [] data)
        throws IOException, QException
    {
        node.nodeLoadAfterHit();
        System.out.println("XMLRPC: putItem: "+metadata);
        return node.putItem(metadata, data);
    }

    /**
     * puts an item of data, without metadata, into the network
     * @param data - binary - the raw data to insert
     * @return the assigned key for the item
     */
    public Hashtable putItem(byte [] data)
        throws IOException, QException
    {
        node.nodeLoadAfterHit();
        System.out.println("XMLRPC: putItem (no metadata)");
        return node.putItem(data);
    }

    /**
     * Schedules the insertion of a qsite. Valid for client nodes only
     * @param privKey64 base64 representation of a signed space private key
     * @param siteName short text name of the qsite, whose URI will end up
     * as 'Q:pubKey64/siteName/'.
     * @param rootPath physical absolute pathname of the qsite's root directory
     * on the host filesystem.
     * Note that this directory must have a file called 'index.html' at its top
     * level, which will be used as the qsite's default document.
     * @param metadata A set of metadata to associate with the qsite
     * @return Hashtable containing results, as the keys:
     * <ul>
     *  <li>status - String - either "ok" or "error"</li>
     *  <li>error - String - short summary of error, only present if
     *    status is "error"</li>
     *  <li>uri - the full Q URI for the top level of the site
     * </ul>
     */
    public Hashtable insertQSite(String privKey64,
                                 String siteName,
                                 String rootPath,
                                 Hashtable metadata
                                 )
        throws Exception
    {
        node.nodeLoadAfterHit();
        System.out.println("XMLRPC: insertQSite("+privKey64+", "+siteName+", "+rootPath+", "+metadata+")");
        return node.insertQSite(privKey64, siteName, rootPath, metadata);
    }

    /**
     * Generates a new keypair for signed-space insertions
     * @return a struct with the keys:
     * <ul>
     *  <li>status - "ok"</li>
     *  <li>publicKey - base64-encoded signed space public key</li>
     *  <li>privateKey - base64-encoded signed space private key</li>
     * </ul>
     * When inserting an item using the privateKey, the resulting uri
     * will be <code>Q:publicKey/path</code>
     */
    public Hashtable newKeys() {

        return node.newKeys();
    }

    /**
     * shuts down the node
     * for the purpose of security, the caller must quote the node's full
     * base64 private key
     * @param nodePrivKey the node's full base64 I2P private key
     * @return if shutdown succeeds, an XML-RPC error will result, because
     * the node will fail to send a reply. If an invalid key is given,
     * the reply Hashtable will contain {"status":"error", "error":"invalidkey"}
     */
    public Hashtable shutdown(String nodePrivKey) {

        Hashtable res = new Hashtable();

        // sekkret h4x - kill the VM if key is the node's I2P base64 privkey
        //System.out.println("shutdown: our privkey="+node.privKeyStr);
        //System.out.println("shutdown: nodePrivKey="+nodePrivKey);
        if (nodePrivKey.equals(node.privKeyStr)) {

            res.put("status", "ok");
            //node.scheduleShutdown();
            // get a runtime
            //System.out.println("Node at "+node.dataDir+" shutting down");
            Runtime r = Runtime.getRuntime();
            // and terminate the vm
            //r.exit(0);
            r.halt(0);
        }
        else {
            res.put("status", "error");
            res.put("error", "invalidkey");
        }

        return res;
    }

    /**
     * shuts down the node
     * for the purpose of security, the caller must quote the node's full
     * base64 private key
     * @param args - a Hashtable/struct/dict/assoc-array, containing:
     *  <ul>
     *    <li>privKey - string - the node's full base64 I2P private key</li>
     *  </ul>
     * @return if shutdown succeeds, an XML-RPC error will result, because
     * the node will fail to send a reply. If an invalid key is given,
     * the reply Hashtable will contain {"status":"error", "error":"invalidkey"}
     */
    public Hashtable shutdown(Hashtable args) {
        String privKey;
        try {
            privKey = (String)args.get("privKey");
        } catch (Exception e) {
            Hashtable res = new Hashtable();
            res.put("status", "error");
            res.put("error", "badkey");
            node.nodeLoadAfterHit();
            return res;
        }
        return shutdown(privKey);
    }
}

