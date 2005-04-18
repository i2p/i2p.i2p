/*
 * QClient.java
 *
 * Created on 20 March 2005, 23:22
 */

package net.i2p.aum.q;

import java.*;
import java.io.*;
import java.util.*;
import java.lang.*;

import org.apache.xmlrpc.*;

import net.i2p.*;
import net.i2p.data.*;

import net.i2p.aum.*;
import net.i2p.aum.http.*;

import HTML.Template;

/**
 * Implements Q client nodes.
 */

public class QClientNode extends QNode {
    
    static String defaultStoreDir = ".quartermaster_client";
    I2PHttpServer webServer;
    MiniHttpServer webServerTcp;
    Properties httpProps;

    public String nodeType = "Client";

    // -------------------------------------------------------
    // CONSTRUCTORS
    // -------------------------------------------------------

    /**
     * Creates a new instance of QClient, using default
     * datastore location
     * @throws IOException, DataFormatException, I2PException
     */
    public QClientNode() throws IOException, DataFormatException, I2PException
    {
        super(System.getProperties().getProperty("user.home") + sep + defaultStoreDir);
        log.debug("TEST CLIENT DEBUG MSG1");
    }

    /**
     * Creates a new instance of QClient, using specified
     * datastore location
     * @param path of node's datastore directory
     * @throws IOException, DataFormatException, I2PException
     */
    public QClientNode(String dataDir) throws IOException, DataFormatException, I2PException
    {
        super(dataDir);
        
        log.error("TEST CLIENT DEBUG MSG");
    }

    // -------------------------------------------------------
    // METHODS - XML-RPC PRIMITIVE OVERRIDES
    // -------------------------------------------------------

    /**
     * hello cmds to client nodes are illegal!
     */
    /**
     public Hashtable localHello(String destBase64)
     {
        Hashtable h = new Hashtable();
        h.put("status", "error");
        h.put("error", "unimplemented");
        return h;
    }
    **/

    /** perform client-specific setup */
    public void setup()
    {
        updateCatalogFromPeers = 1;
        isClient = true;

        // allow a port change for xmlrpc client app conns
        String xmlPortStr = System.getProperty("q.xmlrpc.tcp.port");
        if (xmlPortStr != null) {
            xmlRpcServerPort = new Integer(xmlPortStr).intValue();
            conf.setIntProperty("xmlRpcServerPort", xmlRpcServerPort);
        }

        // ditto for listening host
        String xmlHostStr = System.getProperty("q.xmlrpc.tcp.host");
        if (xmlHostStr != null) {
            xmlRpcServerHost = xmlHostStr;
            conf.setProperty("xmlRpcServerHost", xmlRpcServerHost);
        }

        // ---------------------------------------------------
        // now fire up the HTTP interface
        // listening only within I2P on client node's dest
        
        // set up a properties object for short local tunnel
        httpProps = new Properties();
        httpProps.setProperty("inbound.length", "0");
        httpProps.setProperty("outbound.length", "0");
        httpProps.setProperty("inbound.lengthVariance", "0");
        httpProps.setProperty("outbound.lengthVariance", "0");
        Properties sysProps = System.getProperties();
        String i2cpHost = sysProps.getProperty("i2cp.tcp.host", "127.0.0.1");
        String i2cpPort = sysProps.getProperty("i2cp.tcp.port", "7654");
        httpProps.setProperty("i2cp.tcp.host", i2cpHost);
        httpProps.setProperty("i2cp.tcp.port", i2cpPort);
    }

    public void run() {
    
        // then do all the parent stuff
        super.run();
    }

    /**
     * <p>Sets up and launches an http server for servicing requests
     * to this node.</p>
     * <p>For server nodes, the xml-rpc server listens within I2P on the
     * node's destination.</p>
     * <p>For client nodes, the xml-rpc server listens on a local TCP
     * port (according to attributes xmlRpcServerHost and xmlRpcServerPort)</p>
     */
    public void startExternalInterfaces(QServerMethods methods) throws Exception
    {
        System.out.println("Creating http interface...");
        try {
            // create tcp http server for xmlrpc and browser access
            webServerTcp = new MiniHttpServer(QClientWebInterface.class, xmlRpcServerPort, this);
            webServerTcp.addXmlRpcHandler(baseXmlRpcServiceName, methods);
            System.out.println("started in-i2p http/xmlrpc server listening on port:" + xmlRpcServerPort);
            webServerTcp.start();

            // create in-i2p http server for xmlrpc and browser access
            webServer
                = new I2PHttpServer(privKey,
                                    QClientWebInterface.class,
                                    this,
                                    httpProps
                                    );
            webServer.addXmlRpcHandler(baseXmlRpcServiceName, methods);
            webServer.start();
            System.out.println("Started in-i2p http/xmlrpc server listening on dest:");
            String dest = privKey.getDestination().toBase64();
            System.out.println(dest);
            

        System.out.println("web interfaces created");

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Failed to create client web interfaces");
            System.exit(1);
        }

/**        
        WebServer serv = new WebServer(xmlRpcServerPort);
        // if host is non-null, add as a listen host
        if (xmlRpcServerHost.length() > 0) {
            serv.setParanoid(true);
            serv.acceptClient(xmlRpcServerHost);
        }
        serv.addHandler(baseXmlRpcServiceName, methods);
        serv.start();
        log.info("Client XML-RPC server listening on port "+xmlRpcServerPort+" as service"+baseXmlRpcServiceName);
**/

    }

    // -----------------------------------------------------
    // client-specific customisations of xmlRpc methods
    // -----------------------------------------------------

    /**
     * Insert an item of content, with metadata. Then (since this is the client's
     * override) schedules a job to insert this item to a selection of remote peers.
     * @param metadata Hashtable of item's metadata
     * @param data raw data to insert
     */
    public Hashtable putItem(Hashtable metadata, byte [] data) throws QException
    {
        Hashtable resp = new Hashtable();
        QDataItem item;
        String uri;
    
        // do the local insert first
        try {
            item = new QDataItem(metadata, data);
            item.processAndValidate(true);
            localPutItem(item);
            uri = (String)item.get("uri");

        } catch (QException e) {
            resp.put("status", "error");
            resp.put("error", "qexception");
            resp.put("summary", e.getLocalizedMessage());
            return resp;
        }

        // now schedule remote insertion
        schedulePeerUploadJob(item);

        // and return success, rest will happen automatically in background
        resp.put("status", "ok");
        resp.put("uri", uri);
        return resp;
    }

    /**
     * Search datastore and catalog for a given item of content
     * @param criteria Hashtable of criteria to match in metadata
     */
    public Hashtable search(Hashtable criteria)
    {
        Hashtable result = new Hashtable();
        Vector matchingItems = new Vector();
        Iterator items;
        Hashtable item;
        Hashtable foundUris = new Hashtable();
        String uri;
        
        // get an iterator for all catalog items
        try {
            // test all local content
            items = contentIdx.getItemsSince(0);
            while (items.hasNext()) {
                String uriHash = (String)items.next();
                item = getLocalMetadataForHash(uriHash);
                uri = (String)item.get("uri");
                //System.out.println("search: testing "+metadata+" against "+criteria);
                if (metadataMatchesCriteria(item, criteria)) {
                    matchingItems.addElement(item);
                    foundUris.put(uri, item);
                }
            }

            // now test remote catalog
            items = catalogIdx.getItemsSince(0);
            while (items.hasNext()) {
                String uriHash = (String)items.next();
                item = getLocalCatalogMetadataForHash(uriHash);
                uri = (String)item.get("uri");
                //System.out.println("search: testing "+metadata+" against "+criteria);
                if (metadataMatchesCriteria(item, criteria)) {
                    if (!foundUris.containsKey("uri")) {
                        matchingItems.addElement(item);
                    }
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            result.put("status", "error");
            result.put("error", e.getMessage());
            return result;
        }

        result.put("status", "ok");
        result.put("items", matchingItems);
        return result;
        
    }


    /**
     * retrieves a peers/catalog update - executes on base class, then
     * adds in our catalog entries
     */
    public Hashtable getUpdate(int since, int includePeers, int includeCatalog)
    {
        Hashtable h = localGetUpdate(since, includePeers, includeCatalog);

        if (includeCatalog != 0) {

            // must extend v with remote catalog entries
            Vector vCat = (Vector)(h.get("items"));
            Iterator items;

            // get an iterator for all new catalog items since given unixtime
            try {
                items = catalogIdx.getItemsSince(since);

                // pick through the iterator, and fetch metadata for each item
                while (items.hasNext()) {
                    String key = (String)(items.next());
                    Hashtable pf = getLocalCatalogMetadata(key);
                    log.error("getUpdate(client): key="+key+", pf="+pf);
                    System.out.println("getUpdate(client): key="+key+", pf="+pf);
                    if (pf != null) {
                        // clone this metadata, add in the key
                        Hashtable pf1 = (Hashtable)pf.clone();
                        pf1.put("key", key);
                        vCat.addElement(pf1);
                    }
                }


            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return h;
    }

    /**
     * <p>Retrieve an item of content.</p>
     * <p>This client override tries the local datastore first, then
     * attempts to get the data from remote servers believed to have the data</p>
     */
    public Hashtable getItem(String uri) throws IOException, QException
    {
        Hashtable res;

        log.info("getItem: uri='"+uri+"'");

        if (localHasItem(uri)) {

            class Fred {
            }
            
            Fred xxx = new Fred();
            
            // got it locally, send it back
            return localGetItem(uri);
        }
        
        // ain't got it locally - try remote sources in turn till we
        // either get it or fail
        Vector sources = getItemLocation(uri);

        // send back an error if not in local catalog
        if (sources == null || sources.size() == 0) {
            Hashtable dnf = new Hashtable();
            dnf.put("status", "error");
            dnf.put("error", "notfound");
            dnf.put("comment", "uri not known locally or remotely");
            return dnf;
        }

        // ok, got at least one remote source, go through them till
        // we get data that checks out
        int i;
        int npeers = sources.size();
        int numCmdFail = 0;
        int numDnf = 0;
        int numBadData = 0;
        for (i=0; i<npeers; i++) {
            String peerId = (String)sources.get(i);
            try {
                res = peerGetItem(peerId, uri);
            } catch (Exception e) {
                e.printStackTrace();
                numCmdFail += 1;
                continue;
            }
            // got some kind of response back from peer
            String status = (String)res.get("status");
            if (status.equals("ok")) {

                // don't trust at face value!
                // hash the data, ensure it matches dataHash
                Hashtable metadata = (Hashtable)res.get("metadata");
                String dataHash = (String)metadata.get("dataHash");
                byte [] data = (byte[])res.get("data");
                String dataHash1 = sha256Base64(data);
                if (dataHash.equals(dataHash1)) {
                    // at least the data matches, trust in this vers
                    // TODO - verify metadata hash against main uri

                    // cache it into local datastore
                    QDataItem item = new QDataItem(metadata, data);
                    localPutItem(item);
                    
                    // and pass back
                    return res;
                }
                else {
                    System.out.println("getItem: bad hash on "+data.length+"-byte uri "+uri);
                    System.out.println("getItem: expected: "+dataHash);
                    System.out.println("getItem: received: "+dataHash1);
                    System.out.println("getItem: metadata="+metadata);
                    numBadData += 1;
                }
            }
            else {
                numDnf += 1;
            }
        }

        // if we get here, then all peers either failed,
        // returned dnf, or sent back dodgy data
        res = new Hashtable();
        res.put("status", "error");
        res.put("error", "notfound");
        res.put("summary", 
                "tried "+npeers+" peers, "
                    +numCmdFail+" cmdfail, "
                    +numDnf+" notfound, "
                    +numBadData+" baddata"
                );
        return res;
    }

    /**
     * Schedules the insertion of a qsite
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
        // for results
        Hashtable result = new Hashtable();
        String uri = null;      // uri under which this site will be reachable
        String pubKey64;

        File dir = new File(rootPath);

        // barf if no such directory
        if (!dir.isDirectory()) {
            result.put("status", "error");
            result.put("error", "nosuchdir");
            result.put("detail", "Path '"+rootPath+"' is not a directory");
            return result;
        }

        // barf if not readable
        if (!dir.canRead()) {
            result.put("status", "error");
            result.put("error", "cantread");
            result.put("detail", "Path '"+rootPath+"' is not readable");
            return result;
        }
        
        // barf if missing or invalid site name
        siteName = siteName.trim();
        if (!siteName.matches("[a-zA-Z0-9_-]+")) {
            result.put("status", "error");
            result.put("error", "badsitename");
            result.put("detail", "QSite name should be only alphanumerics, '-' and '_'");
            return result;
        }

        String defaultPath = rootPath + sep + "index.html";
        File defaultFile = new File(defaultPath);
        
        // barf if index.html not present and readable
        if (!(defaultFile.isFile() && defaultFile.canRead())) {
            result.put("status", "error");
            result.put("error", "noindex");
            result.put("detail", "Required file index.html missing or unreadable");
            return result;
        }

        // derive public key and uri for site, barf if bad key
        try {
            pubKey64 = QUtil.privateToPubHash(privKey64);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", "badprivkey");
            return result;
        }
        uri = "Q:" + pubKey64 + "/" + siteName + "/";

        // now the fun recursive bit
        insertQSiteDir(privKey64, siteName, rootPath, "");

        // queue up an insert of default file
        metadata.put("type", "qsite");
        metadata.put("path", siteName+"/");
        metadata.put("mimetype", "text/html");

        //System.out.println("insertQSite: privKey='"+privKey64+"'");
        //System.out.println("insertQSite: siteName='"+siteName+"'");
        //System.out.println("insertQSite: rootDir='"+rootPath+"'");
        //System.out.println("insertQSite: metadata="+metadata);
        //System.out.println("insertQSite: default="+defaultPath);

        insertQSiteFile(privKey64, siteName, defaultPath, "", metadata);

        result.put("status", "ok");
        result.put("uri", uri);
        return result;
    }

    /**
     * recursively queues jobs for the insertion of a directory's contents, for
     * a qsite.
     * @param privKey64 - private 'signed space' key, base64 format
     * @param siteName - short text name for the site
     * @param absPath - physical pathname of the subdirectory to insert
     * @param relPath - qsite-relative pathname of this item
     */
    protected void insertQSiteDir(String privKey64, String siteName, String absPath, String relPath)
        throws Exception
    {
        File dir = new File(absPath);

        // fail gracefully if not a readable directory
        if (!(dir.isDirectory() && dir.canRead())) {
            System.out.println("insertQSiteDir: not a readable directory "+absPath);
            return;
        }

        //System.out.println("insertQSiteDir: entry - abs='"+absPath+"' rel='"+relPath+"'");

        // loop through the contents
        String [] contents = dir.list();
        for (int i=0; i<contents.length; i++) {
            String item = contents[i];
            String itemAbsPath = absPath + sep + item;
            String itemRelPath = relPath + item;
            //System.out.println("insertQSiteDir: item='"+item+"' abs='"+itemAbsPath+"' rel='"+itemRelPath+"'");
            File itemFile = new File(itemAbsPath);
            
            // what kind of entry is this?
            if (itemFile.isDirectory()) {
                // directory - recursively insert
                insertQSiteDir(privKey64, siteName, itemAbsPath, itemRelPath + "/");

            } else {
                // file - insert
                insertQSiteFile(privKey64, siteName, itemAbsPath, itemRelPath, null);
            }
        }
    }

    /**
     * queues up the insertion of an individual qsite file
     * @param privKey64 - base64 signed space private key
     * @param siteName - name of qsite
     * @param absPath - absolute location of file on host filesystem
     * @param relPath - pathname of file relative to Q uri
     */
    protected void insertQSiteFile(String privKey64, String siteName,
                                   String absPath, String relPath, Hashtable metadata)
        throws Exception
    {
        //System.out.println("insertQSiteFile: priv="+privKey64+" name="+siteName+" abs="+absPath+" rel="+relPath+" metadata="+metadata);

        if (metadata == null) {
            metadata = new Hashtable();
        }

        metadata.put("privateKey", privKey64);
        if (!metadata.containsKey("path")) {
            metadata.put("path", siteName + "/" + relPath);
        }
        if (!metadata.containsKey("mimetype")) {
            metadata.put("mimetype", Mimetypes.guessType(relPath));
        }

        scheduleLocalInsertJob(absPath, metadata);
    }

    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args)
        throws IOException, DataFormatException, I2PException, InterruptedException
    {

        // just for testing
        System.setProperty("i2cp.tcp.host", "10.0.0.1");
        
        QClientNode node;
        if (args.length > 0) {
            node = new QClientNode(args[0]);
        }
        else {
            node = new QClientNode();
        }
        node.log.info("QClientNode: running node...");
        node.run();
    }

    public void foo1() {
        System.out.println("QClientNode.foo: isClient="+isClient);
    }

    
}
