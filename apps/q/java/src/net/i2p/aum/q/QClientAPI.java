/*
 * QClientAPI.java
 *
 * Created on March 31, 2005, 5:19 PM
 */

package net.i2p.aum.q;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Hashtable;
import java.util.Vector;

import org.apache.xmlrpc.XmlRpcClient;
import org.apache.xmlrpc.XmlRpcException;

/**
 * <p>The official Java API for client applications wishing to access the Q
 * network</p>
 * <p>This API is just a thin wrapper that hides the XMLRPC details, and exposes
   a simple set of methods.</p>
 * <p>Note to app developers - I'm only implementing this API in Java
 * and Python at present. If you've got some time and knowledge of other
 * languages and their available XML-RPC client libs, we'd really appreciate
 * it if you can port this API into other languages - such as Perl, C++,
 * Ruby, OCaml, C#, etc. You can take this API implementation as the reference
 * code for porting to your own language.</p>
 */

public class QClientAPI {

    XmlRpcClient node;

    /**
     * Creates a new instance of QClientAPI talking on given xmlrpc port
     */
    public QClientAPI(int port) throws MalformedURLException {
        node = new XmlRpcClient("http://127.0.0.1:"+port);
    }

    /**
     * Creates a new instance of QClientAPI talking on default xmlrpc port
     */
    public QClientAPI() throws MalformedURLException {
        node = new XmlRpcClient("http://127.0.0.1:"+QClientNode.defaultXmlRpcServerPort);
    }

    /**
     * Pings a Q client node, gets back a bunch of useful stats
     */
    public Hashtable ping() throws XmlRpcException, IOException {
        return (Hashtable)node.execute("i2p.q.ping", new Vector());
    }

    /**
     * Retrieves an update of content catalog
     * @param since a unixtime in seconds. The content list returned will
     * be a differential update since this time.
     */
    public Hashtable getUpdate(int since)
        throws XmlRpcException, IOException
    {
        Vector args = new Vector();
        args.addElement(new Integer(since));
        args.addElement(new Integer(1));
        args.addElement(new Integer(1));
        return (Hashtable)node.execute("i2p.q.getUpdate", args);
    }

    /**
     * Retrieves an item of content from the network, given its key
     * @param key the key to retrieve
     */
    public Hashtable getItem(String key) throws XmlRpcException, IOException {
        Vector args = new Vector();
        args.addElement(key);
        return (Hashtable)node.execute("i2p.q.getItem", args);
    }

    /** 
     * Inserts a single item of data, without metadata. A default metadata set
     * will be generated.
     * @param data a byte[] of data to insert
     * @return a Hashtable containing results, including:
     * <ul>
     *  <li>result - either "ok" or "error"</li>
     *  <li>error - (only if result != "ok") - terse error label</li>
     *  <li>key - the key under which this item has been inserted</li>
     * </ul>
     */
    public Hashtable putItem(byte [] data) throws XmlRpcException, IOException {
        Vector args = new Vector();
        args.addElement(data);
        return (Hashtable)node.execute("i2p.q.putItem", args);
    }

    /** 
     * Inserts a single item of data, with metadata
     * @param metadata a Hashtable of metadata to insert
     * @param data a byte[] of data to insert
     * @return a Hashtable containing results, including:
     * <ul>
     *  <li>result - either "ok" or "error"</li>
     *  <li>error - (only if result != "ok") - terse error label</li>
     *  <li>key - the key under which this item has been inserted</li>
     * </ul>
     */
    public Hashtable putItem(Hashtable metadata, byte [] data)
        throws XmlRpcException, IOException
    {
        Vector args = new Vector();
        args.addElement(metadata);
        args.addElement(data);
        return (Hashtable)node.execute("i2p.q.putItem", args);
    }

    /**
     * Generates a new keypair for inserting signed-space items
     * @return a struct with the keys:
     * <ul>
     *  <li>status - "ok"</li>
     *  <li>publicKey - base64-encoded signed space public key</li>
     *  <li>privateKey - base64-encoded signed space private key</li>
     * </ul>
     * When inserting an item using the privateKey, the resulting uri
     * will be <code>Q:publicKey/path</code>
     */
    public Hashtable newKeys() throws XmlRpcException, IOException
    {
        Vector args = new Vector();
        return (Hashtable)node.execute("i2p.q.newKeys", args);
    }

    
    /**
     * Adds a new noderef to node
     * @param dest - the base64 i2p destination for the remote peer
     * @return a Hashtable containing results, including:
     * <ul>
     *  <li>result - either "ok" or "error"</li>
     *  <li>error - (only if result != "ok") - terse error label</li>
     * </ul>
     */
    public Hashtable hello(String dest) throws XmlRpcException, IOException {
        Vector args = new Vector();
        args.addElement(dest);
        return (Hashtable)node.execute("i2p.q.hello", args);
    }

    /**
     * Shuts down a running node
     * If the shutdown succeeds, then this call will fail with an exception. But
     * if the call succeeds, then the shutdown has failed (sorry if this is a tad
     * counter-intuitive).
     * @param privKey - the base64 i2p private key for this node.
     * @return a Hashtable containing results, including:
     * <ul>
     *  <li>result - "error"</li>
     *  <li>error - terse error label</li>
     * </ul>
     */
    public Hashtable shutdown(String privKey) throws XmlRpcException, IOException {
        Vector args = new Vector();
        args.addElement(privKey);
        return (Hashtable)node.execute("i2p.q.shutdown", args);
    }

    /**
     * Search the node for catalog entries matching a set of criteria
     * @param criteria a Hashtable of metadata criteria to match, and whose
     * values are regular expressions
     * @return a Hashtable containing results, including:
     * <ul>
     *  <li>result - "ok" or "error"</li>
     *  <li>error - if result != "ok", a terse error label</li>
     *  <li>items - a Vector of items found which match the given search
     *     criteria. If no available matching items were found, this vector
     *     will come back empty.
     * </ul>
     */
    public Hashtable search(Hashtable criteria) throws XmlRpcException, IOException {
        Vector args = new Vector();
        args.addElement(criteria);
        return (Hashtable)node.execute("i2p.q.search", args);
    }
}

