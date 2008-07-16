/*
 * QLaunch.java
 *
 * Created on March 30, 2005, 10:09 PM
 */

package net.i2p.aum.q;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Properties;
import java.util.Vector;

import net.i2p.aum.I2PXmlRpcClientFactory;
import net.i2p.aum.PropertiesFile;
import net.i2p.aum.SimpleFile;
import net.i2p.data.Destination;

import org.apache.xmlrpc.XmlRpcClient;

/**
 * <p>Command Line Interface (CLI) for starting/stopping Q nodes,
 * and also, executing commands on Q nodes such as inserting, retrieving
 * and searching for content.</p>
 *
 * <p>Commands include:
 *   <ul>
 *     <li>Start a server or client Node</li>
 *     <li>Stop a server or client Node</li>
 *     <li>Get status of a server or client Node</li>
 *     <li>Export a server node's dest</li>
 *     <li>Import a foreign dest to a server or client node</li>
 *     <li>Insert a file to a client node, with metadata</li>
 *     <li>Retrieve data/metadata from a client node</li>
 *     <li>Search a client node for content</li>
 */
public class QMgr {

    public Runtime runtime;
    public XmlRpcClient node;
    public String nodePrivKey;
    public String nodeDest;
    public String nodeDirStr;
    public File nodeDir;
    public boolean isServer = false;

    public String [] args;
    public String cmd;
    public int cmdIdx;
    public int argc;
    public int argi;
    
    public static String [] commonI2PSystemPropertyKeys = {
        "i2cp.tcp.host",
        "i2cp.tcp.port",
        "eepproxy.tcp.host",
        "eepproxy.tcp.port",
        "q.xmlrpc.tcp.host",
        "q.xmlrpc.tcp.port",
        "inbound.length",
        "outbound.length",
        "inbound.lengthVariance",
        "outbound.lengthVariance",
    };

    /** Creates a new instance of QLaunch */
    public QMgr() {
    }

    public void notimplemented() {
        usage(1, "Command '"+cmd+"' not yet implemented, sorry");
    }

    /** procures an XML-RPC client for node interaction */
    public void getXmlRpcClient() {
    
        
    }

    public int doHelp() {
        if (argi == argc) {
            // output short help
            System.out.println(
                "I2P QMgr - Brief command summary:\n"
                +"Synopsis:"
                +"  java net.i2p.aum.q.QMgr [-dir <path>] [server] [<cmd> [<args>]]\n"
                +"Commands:\n"
                +"  help             - print this help summary\n"
                +"  help verbose     - print detailed verbose usage info\n"
                +"  start            - start a node in background\n"
                +"  foreground       - run a node in foreground\n"
                +"  stop             - terminate node\n"
                +"  status           - display node status\n"
                +"  getref [<file>]  - output the node's noderef (its base64 dest)\n"
                +"  addref [<file>]  - add one or more node refs to node\n"
                +"  get key [<file>] - get key to stdout (or to file)\n"
                +"  put [<file>] [-m <metadata>] - insert content\n"
                +"  search item1=val1 item2=val2...  - search for content\n"
                );
        }
        else if (args[argi].equals("verbose")) {
            System.out.println(
                "----------------------------\n"
                +"Welcome to the I2P Q network\n"
                +"----------------------------\n"
                +"\n"
                +"This program, QMgr, is a command-line interface to the Q network,\n"
                +"(an in-I2P distributed file store)\n"
                +"and allows you to perform basic operations, including:\n"
                +"\n"
                +" - create, startup and shutdown Q server and client nodes\n"
                +" - determine status of running Q nodes\n"
                +" - import and export noderefs to/from these nodes\n"
                +" - search for, insert and retrieve content\n"
                +"\n"
                +"Command syntax:\n"
                +"  java net.i2p.aum.q.QMgr [-dir <path>] [-port <port>] [server] [<cmd> [<args>]]\n"
                +"\n"
                +"Explanation of commands and arguments:"
                +"\n"
                +"* 'server'\n"
                +"  Specifies that we're operating on a server node (otherwise it's\n"
                +"  assumed we're operating on a client node)\n"
                +"\n"
                +"* '-dir=<path>'\n"
                +"  Server nodes by default reside at ~/.quartermaster_server,\n"
                +"  and client nodes at ~/.quartermaster_client.\n"
                +"  Nodes are uniquely identified by the directory at which they\n"
                +"  reside. Specifying this argument allows you to operate on a\n"
                +"  server or client node which resides at a different location\n"
                +"\n"
                +"* '-port=<port>'\n"
                +"  Applies to client nodes only. Valid only for startup command.\n"
                +"  Permanently changes the port on which a given client listens\n"
                +"  for cmmands.\n"
                +"\n"
                +"* Commands - the basic commands are:\n"
                +"\n"
                +"  help\n"
                +"    - display a help summary\n"
                +"\n"
                +"  help verbose\n"
                +"    - display this long-winded help\n"
                +"\n"
                +"  start\n"
                +"     - start the node. If a nonexistent directory path is given,\n"
                +"       a whole new unique server or client node will be created\n"
                +"       at that path\n"
                +"\n"
                +"  foreground\n"
                +"     - as for start, but run the server in foreground rather\n"
                +"       than as a background daemon\n"
                +"\n"
                +"  stop\n"
                +"     - shutdown the node\n"
                +"\n"
                +"  status\n"
                +"     - print a dump of node status and statistics to stdout\n"
                +"\n"
                +"  newkeys\n"
                +"     - generate and print out a new keypair for signed-space\n"
                +"       data item inserts\n"
                +"\n"
                +"  getref [<file>]\n"
                +"     - print the node's noderef (its base64 destination) to\n"
                +"       stdout. If <file> arg is given, writes the destination\n"
                +"       to this file instead.\n"
                +"\n"
                +"  addref [<file>]\n"
                +"     - add one or more noderefs to the node. If [<file>] argument\n"
                +"       is given, the refs are read from this file, which is expected\n"
                +"       to contain one base64 destination per line\n"
                +"\n"
                +"The following commands are only valid for client nodes:\n"
                +"\n"
                +"  get <key> [<file>]\n"
                +"     - Try to retrieve a content item, (identified by <key>), from the\n"
                +"       node. If the item is retrieved, its raw data will be printed\n"
                +"       to stdout, or to <file> if given. NOTE - REDIRECTING TO STDOUT\n"
                +"       IS PRESENTLY UNRELIABLE, SO SPECIFY AN EXPLICIT FILENAME FOR NOW\n"
                +"\n"
                +"  put [<file>] [-m item=val ...]\n"
                +"     - Inserts an item of content to the node, and prints its key to\n"
                +"       stdout. Reads content data from <file> if given, or from standard\n"
                +"       input if not. Metadata arguments may be given as '-m' followed by\n"
                +"       a space-separated sequence of 'item=value' specifiers.\n"
                +"       Typical metadata items include:\n"
                +"         - type (one of text/html/image/audio/video/archive)\n"
                +"         - title - a short (<80 char) descriptive title\n"
                +"         - filename - a recommended filename under which to store this\n"
                +"           item on retrieve.\n"
                +"         - abstract - a longer (<256 char) summary of content\n"
                +"         - keywords - a comma-separated list of keywords\n"
                +"\n"
                +"  search -m item=val [ item=val ...]\n"
                +"     - searches node for content matching a set of metadata criteria\n"
                +"       each 'item=val' specifies an 'item' of metadata, to be matched\n"
                +"       against regular expression 'val'. For example:\n"
                +"         java net.i2p.aum.q.QMgr search -m title=\"^Madonna\" type=\"music\"\n"
                );
        }
        else {
            System.out.println(
                "Unrecognised help qualifier '"+args[argi]+"'\n"
                +"type 'java net.i2p.aum.q.QMgr help' for more info"
                );
        }
        return 0;
    }

    public int doStart() {
        //notimplemented();

        String [] startForegroundArgs;
        int i;

        // Detect/add any '-D' settings
        // search our list of known i2p-relevant sysprops, detect
        // if they've been set in System properties, and if so, copy
        // them to a customProps table
        Hashtable customProps = new Hashtable();
        Properties sysprops = System.getProperties();
        for (i=0; i<commonI2PSystemPropertyKeys.length; i++) {
            String key = commonI2PSystemPropertyKeys[i];
            if (sysprops.containsKey(key)) {
                customProps.put(key, sysprops.get(key));
            }
        }

        // Create args list
        args[cmdIdx] = "foreground";  // kludge = substitute 'foreground' command

        // need original args plus 2 ('java' and 'classname') plus
        // number of custom properties
        startForegroundArgs = new String[argc+2+customProps.size()];

        // create a set of startup args for child vm
        startForegroundArgs[0] = "java";
        i = 1;
        Enumeration keys = customProps.keys();
        while (keys.hasMoreElements()) {
            String key = (String)keys.nextElement();
            String val = (String)customProps.get(key);
            startForegroundArgs[i++] = "-D"+key+"="+val;
        }
        startForegroundArgs[i++] = "net.i2p.aum.q.QMgr";
        for (int j = 0; j < args.length; j++) {
            startForegroundArgs[i+j] = args[j];
        }

        // and spawn the start job
        try {
            //runtime.exec(startForegroundArgs, propLines);
            runtime.exec(startForegroundArgs, null);
            for (i=0; i<startForegroundArgs.length; i++) {
                System.out.println("start: arg["+i+"]="+startForegroundArgs[i]);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return 1;
        }
        return 0;
    }

    /** this gets invoked after a 'start' command, and runs in a detached process */
    public int doStartForeground() {

        //new File("/tmp/blahblah").mkdirs();
        /**
        String s = new String();
        int i = 0;
        for (i=0; i<args.length; i++) {
            s = s + args[i] + "\n";
        }
        try {
            new SimpleFile("/tmp/qq", "rws").write(s);
        } catch (IOException e) {}
        //notimplemented();
        **/

        QNode node;

        try {
            if (isServer) {
                node = new QServerNode(nodeDirStr);
            }
            else {
                node = new QClientNode(nodeDirStr);
            }

            node.run();
            
            // enter endless loop
            //while (true) {
            //    Thread.sleep(1000000000);
            //}

        } catch (Exception e) {
            e.printStackTrace();
        }

        return 0;
    }

    public int doStop() {

        System.out.println("Stopping node at '"+nodeDirStr+"'...");
        Vector nodeArgs = new Vector();
        nodeArgs.addElement(nodePrivKey);
        try {
            Hashtable res = (Hashtable)node.execute("i2p.q.shutdown", nodeArgs);
            System.out.println("Shutdown failed: got "+res);
            return 1;
        } catch (IOException e) {
            System.out.println("Shutdown apparently ok");
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Problem with shutdown");
            return 1;
        }

        return 0;
    }

    public int doStatus() {
        System.out.println("Pinging node at '"+nodeDirStr+"'...");
        try {
            Hashtable res = (Hashtable)node.execute("i2p.q.ping", new Vector());
            System.out.println("Node Ping:");
            Enumeration eres = res.keys();
            while (eres.hasMoreElements()) {
                String key = (String)eres.nextElement();
                Object val = res.get(key);
                System.out.println("  "+key+"="+val);
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Failed to ping node");
            return 1;
        }

        return 0;
    }

    /** executes a 'getref' command */
    public int doGetRef() {
        if (!isServer) {
            System.err.println("Cannot get noderefs for client nodes");
            return 1;
        }
        if (argi < argc) {
            // write it to a file
            String path = args[argi];
            try {
                new SimpleFile(path, "rws").write(nodeDest);
            } catch (IOException e) {
                e.printStackTrace();
                usage("getref: Cannot create/write output file "+path);
            }
        }
        else {
            System.out.println(nodeDest);
        }
        return 0;
    }

    /**
     * attempts to add a single dest
     * @return true if dest added successfully, false if not
     */
    public boolean doAddOneRef(String ref) {
        
        Destination d;
        Hashtable res;

        // don't trust user or xmlrpc link, try to create a dest
        // from purported string now
        try {
            d = new Destination();
            d.fromBase64(ref);
        } catch (Exception e) {
            System.out.println("Invalid destination: '"+ref+"'");
            return false;
        }
        
        // looks ok, try to pass it to node
        try {
            Vector v = new Vector();
            v.addElement(ref);
            res = (Hashtable)node.execute("i2p.q.hello", v);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Failed to add noderef '"+ref+"'");
            return false;
        }
        
        System.out.println("doAddRef: res="+res);

        // see what result we got
        String status = (String)res.get("status");

        System.out.println("doAddRef: status="+status);

        if (status == null || !status.equals("ok")) {
            String error = (String)res.get("error");
            System.out.println("Error '"+error+"' trying to add node dest '"+ref+"'");
            return false;
        }
        
        // success
        return true;
    }

    /** executes an 'addref' command */
    public int doAddRef() {
        
        if (argi < argc) {
            // open file, split into lines, submit each line as dest
            String path = args[argi];
            String raw;
            try {
                raw = new SimpleFile(path, "r").read().trim();
            } catch (IOException e) {
                e.printStackTrace();
                System.out.println("addref: failed to open file '"+path+"'");
                return 1;
            }
            String [] lines = raw.split("\\s+");
            //int i;
            for (int i=0; i<lines.length; i++) {

                String line = lines[i];

                // ignore empty and comment lines
                if (line.substring(0,1).equals("#")) {
                    continue;
                }

                if (!doAddOneRef(line)) {
                    // failed
                    return 1;
                }
            }
        }
        else {
            // read lines from stdin
            InputStreamReader rIn = new InputStreamReader(System.in);
            BufferedReader brIn = new BufferedReader(rIn);

            String line;
            
            while (true) {
                try {
                    line = brIn.readLine();
                } catch (IOException e) {
                    e.printStackTrace();
                    return 1;
                }
                if (line == null) {
                    break;
                }
                
                if (!doAddOneRef(line)) {
                    // failed
                    return 1;
                }
            }
            
        }
        
        return 0;
    }

    public int doGet() {

        if (argi == argc) {
            usage("get: missing key");
        }
        
        String key = args[argi++];

        System.err.println("Trying to retrieve key '"+key+"'...");

        Hashtable res;
        try {
            Vector getArgs = new Vector();
            getArgs.addElement(key);
            res = (Hashtable)node.execute("i2p.q.getItem", getArgs);
            //System.err.println("get: res="+res);

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Failed to ping node");
            return 1;
        }
        
        String status = (String)res.get("status");
        if (!status.equals("ok")) {
            String err = (String)res.get("error");
            System.err.println("Key retrieve error: "+err);
            String comment = (String)res.get("comment");
            if (comment != null) {
                System.err.println("  "+comment);
            }
            return 1;
        }

        // got something
        byte [] data = (byte [])res.get("data");

        // save to file, or spit out to stdout?
        if (argi < argc) {
            // spit to file
            String path = args[argi];
            try {
                new SimpleFile(path, "rws").write(data);
            } catch (Exception e) {
                e.printStackTrace(System.err);
                System.err.println("Failed to save data to file '"+path);
                return 1;
            }
        }
        else {
            // dump to stdout
            //System.out.print(data);
            for (int i=0; i<data.length; i++) {
                char c = (char)data[i];
                System.out.print(c);
            }
            System.out.flush();
        }
        return 0;
    }

    public int doPut() {

        Hashtable metadata = new Hashtable();
        String path = null;

        // different ways of sourcing data/metadata
        if (argi < argc) {
            // provided filename and/or metadata
            if (!args[argi].equals("-m")) {
                // read from file
                path = args[argi++];
            }
            
            // now expect -m, or error
            if (argi >= argc || !args[argi].equals("-m")) {
                usage("Bad put command syntax");
            }

            // now skip over the '-m'
            argi++;

            metadata = readMetadataSpec();
        }

        byte [] data = null;

        if (path != null) {
            // easy way - suck the file or barf
            try {
                data = new SimpleFile(path, "r").readBytes();
            } catch (IOException e) {
                e.printStackTrace();
                usage("get: Failed to read input file '"+path+"'");
            }
        }
        else {
            // the crap option - suck it from stdin
            // read lines from stdin
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            int c;
            try {
                while (true) {
                    c = System.in.read();
                    if (c < 0) {
                        break;
                    }
                    bo.write(c);
                }
            } catch (Exception e) {
                e.printStackTrace();
                usage("put: error reading from input stream");
            }
            
            data = bo.toByteArray();
        }

        // ok, got data (and possibly metadata too)
        Vector putArgs = new Vector();
        Hashtable res;
        putArgs.addElement(metadata);
        putArgs.addElement(data);
        
        System.out.println("data length="+data.length);

        try {
            res = (Hashtable)node.execute("i2p.q.putItem", putArgs);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.err.println("Failed to put");
            return 1;
        }
        
        // got a res
        String status = (String)res.get("status");
        if (!status.equals("ok")) {
            String error = (String)res.get("error");
            usage("put: failure - "+error);
        }

        // success
        String key = (String)res.get("key");
        System.out.print(key);
        System.out.flush();
        
        return 0;
    }

    public int doNewKeys() {
        
        System.err.println("Generating new signed-space keypair...");

        String [] keys = QUtil.newKeys();
        System.out.println("Public: "+keys[0]);
        System.out.println("Private: "+keys[1]);
        
        return 0;
    }

    public int doSearch() {

        if (argi == argc) {
            usage("Missing search metadata");
        }

        // expect -m, or error
        if (argi >= argc || !args[argi].equals("-m")) {
            usage("Bad search command syntax");
        }

        // now skip over the '-m'
        argi++;

        if (argi == argc) {
            usage("Missing search metadata");
        }

        Hashtable metadata = readMetadataSpec();

        // ok, got data (and possibly metadata too)
        Vector searchArgs = new Vector();
        Hashtable res;
        searchArgs.addElement(metadata);
        try {
            res = (Hashtable)node.execute("i2p.q.search", searchArgs);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.err.println("Failed to search");
            return 1;
        }
        
        // got a res
        String status = (String)res.get("status");
        if (!status.equals("ok")) {
            String error = (String)res.get("error");
            usage("search: failure - "+error);
        }

        // success
        Vector items = (Vector)res.get("items");
        
        //System.out.println(items);

        for (int i=0; i<items.size(); i++) {
            Hashtable rec = (Hashtable)items.get(i);
            String key = (String)rec.get("key");
            if (key == null) {
                continue;
            }
            System.out.println(key);
            Enumeration keys = rec.keys();
            while (keys.hasMoreElements()) {
                Object mkey = keys.nextElement();
                if (!mkey.toString().equals("key")) {
                    Object val = rec.get(mkey);
                    System.out.println("  "+mkey+"="+val);
                }
            }
            
        }
        
        return 0;
    }

    public Hashtable readMetadataSpec() {
        Hashtable meta = new Hashtable();

        //dumpArgs();

        // rest of args are metadata
        while (argi < argc) {
            String metaArg = args[argi++];
            String [] parts = metaArg.split("=", 2);
            // barf if just 1 part
            if (parts.length < 2) {
                usage("Illegal metadata arg '"+metaArg+"'");
            }

            // add to search map
            meta.put(parts[0], parts[1]);
        }
        
        return meta;
    }

    public void dumpArgs() {
        System.out.println("Dump of QMgr shell args:");
        for (int i=0; i<args.length; i++) {
            System.out.println("args["+i+"]='"+args[i]+"'");
        }
    }

    public int execute(String [] args) {

        argi = 0;
        argc = args.length;
        this.args = args;
        
        runtime = Runtime.getRuntime();

        // barf if no cmds given
        if (argc == 0) {
            usage("Missing command");
        }

        // test if specifying a directory
        if (args[argi].equals("-dir")) {
            // barf if no dir arg
            argi++;
            if (argi == argc) {
                usage("-dir: missing directory");
            }
            nodeDirStr = args[argi++];
        }

        // test if specifying a port
        if (args[argi].equals("-port")) {
            // barf if no port arg
            argi++;
            if (argi == argc) {
                usage("-port: missing port num");
            }
            System.setProperty("q.xmlrpc.tcp.port", args[argi++]);
        }

        // test if server
        if (argi < argc && args[argi].equals("server")) {
            isServer = true;
            argi++;
        }
        
        // barf if no more arg
        if (argi == argc){
            usage("Missing command");
        }

        // cool, got at least a keyword
        cmdIdx = argi++;
        cmd = args[cmdIdx];
        
        // and dispatch off to appropriate handler
        if (cmd.equals("help")) {
            return doHelp();
        }

        // following commands deal with a node, so gotta get a handle
        if (nodeDirStr == null) {
            // fall back on defaults
            if (isServer) {
                nodeDirStr = System.getProperties().getProperty("user.home")
                    + QServerNode.sep
                    + QServerNode.defaultStoreDir;
            }
            else {
                nodeDirStr = System.getProperties().getProperty("user.home")
                    + QClientNode.sep
                    + QClientNode.defaultStoreDir;
            }
        }
        nodeDir = new File(nodeDirStr);
        
        //System.out.println("nodeDirStr='"+nodeDirStr+"'");
        
        if (cmd.equals("start")) {
            return doStart();
        }
        
        else if (cmd.equals("foreground")) {
            // secret option, used when starting node
            return doStartForeground();
        }

        // the following commands require that the node actually exists
        if (!nodeDir.isDirectory()) {
            usage(
                "Nonexistent node directory '"+nodeDirStr+"'\n"
                +"The '"+cmd+"' command requires that the node already\n"
                +"exists. You may use the 'start' command to create a\n"
                +"whole new node at that directory if you wish"
                );
        }

        // yay, found a node (we hope), create an xmlrpc client for talking
        // to that node
        String propPath = nodeDirStr + QNode.sep + "node.conf";
        PropertiesFile pf;
        try {
            pf = new PropertiesFile(propPath);
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("Failed to load node properties");
            return -1;
        }

        nodePrivKey = pf.getProperty("privKey");
        nodeDest = pf.getProperty("dest");
       
        if (isServer) {
            // gotta create I2P xmlrpc client
            try {
                node = new I2PXmlRpcClientFactory().newClient(nodeDest);
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Failed to create I2P XML-RPC connection to node");
                return -1;
            }
        }
        else {
            int xmlRpcServerPort = pf.getIntProperty(
                "xmlRpcServerPort", QNode.defaultXmlRpcServerPort);
            // create normal xmlrpc client
            try {
                node = new XmlRpcClient("http://localhost:"+xmlRpcServerPort);
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Failed to create I2P XML-RPC connection to node");
                return -1;
            }
        }
        
        if (cmd.equals("stop")) {
            return doStop();
        }
        else if (cmd.equals("status")) {
            return doStatus();
        }
        else if (cmd.equals("getref")) {
            return doGetRef();
        }
        else if (cmd.equals("addref")) {
            return doAddRef();
        }
        else if (cmd.equals("get")) {
            return doGet();
        }
        else if (cmd.equals("put")) {
            return doPut();
        }
        else if (cmd.equals("newkeys")) {
            return doNewKeys();
        }
        else if (cmd.equals("search")) {
            return doSearch();
        }
        else {
            usage("unrecognised command '"+cmd+"'");
        }
        
        return 0; // needed to shut the stupid compiler up
    }

    // barf-o-matic methods

    public int usage() {
        return usage(1);
    }

    public int usage(String msg) {
        return usage(1, msg);
    }
    
    public int usage(int retval) {
        return usage(retval, null);
    }
    
    public int usage(int retval, String msg) {
        System.out.println(msg);
        System.out.println(
            "Usage: java net.i2p.aum.q.QMgr [-dir=<path>] [server] [cmd [args]]\n"
            +"Type 'java net.i2p.aum.q.QMgr help' for help summary\n"
            +"or 'java net.i2p.aum.q.QMgr help verbose' for long-winded help"
            );
        System.exit(retval);
        return 0; // stop silly compiler from whingeing
    }

    /**
     * Startup a Q server or client node, or send a command to a running node
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        QMgr mgr = new QMgr();
        int retval = mgr.execute(args);
        System.exit(retval);
    }
    
}

