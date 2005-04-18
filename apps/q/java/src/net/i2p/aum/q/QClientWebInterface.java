/*
 * QClientWebInterface.java
 *
 * Created on April 9, 2005, 1:10 PM
 */

package net.i2p.aum.q;

import java.lang.*;
import java.lang.reflect.*;
import java.io.*;
import java.net.*;
import java.util.*;

import HTML.Template;

import net.i2p.aum.http.*;


/**
 * Request handler for Q Client nodes that listens within I2P
 * on the client node's destination. Intended for access via
 * eepProxy, and by adding a hosts.txt entry for this dest
 * under the hostname 'q'.
 */
public class QClientWebInterface extends I2PHttpRequestHandler {

    /** set this to true when debugging html layout */
    public static boolean loadTemplateWithEachHit = true;

    public QNode node = null;

    // refs to main page template, and components of main page
    static Template tmplt;
    static Vector tabRow;
    static Vector pageItems;

    /**
     * for security - disables direct-uri GETs of content if running directly over TCP;
     * we need to coerce users to use their eepproxy browser instead
     */
    public boolean isRunningOverTcp = true;

    /** Creates a new instance of QClientWebInterface */
    public QClientWebInterface(MiniHttpServer server, Object socket, Object node)
        throws Exception
    {
        super(server, socket, node);
        this.node = (QNode)node;
        isRunningOverTcp = socket.getClass() == Socket.class;
    }

    static String [] tabNames = {
        "home", "search", "insert", "tools", "status", "jobs", "help", "about"
    };

    /**
     * Loads a template of a given name. Invokes method on node
     * to resolve this to an absolute pathname, so 'name' -&gt; '/path/to/html/name.html'
     */
    public Template loadTemplate(String name) throws Exception {

        String fullPath = node.getResourcePath("html"+node.sep+name)+".html";
        //System.out.println("fullPath='"+fullPath+"'");
        String [] args = new String [] {
                "filename",  fullPath,
                "case_sensitive", "true",
                "max_includes",   "5"
            };
        return new Template(args);
    }

    // ----------------------------------------------------
    // FRONT-END METHODS
    // ----------------------------------------------------

    /** GET and POST both go through .safelyHandleReq() */
    public void on_GET() {

        safelyHandleReq();
    }

    /** GET and POST both go through .safelyHandleReq() */
    public void on_POST() {

        safelyHandleReq();
    }

    public void on_RPC() {
        
    }

    /**
     * wrap .handleReq() - on exception, call dump_error() to
     * generate a 400 error page with diagnostics
     */
    public void safelyHandleReq() {
        try {
            handleReq();
        } catch (Exception e) {
            dump_error(e);
        }
    }

    /**
     * <p>Forwards hits to either a path handler method, or generic get method.</p>
     * 
     * <p>Detects hits to paths for which we have a handler (ie, methods
     * of this class with name 'hdlr_&lt;somepath&gt;', (such as 'hdlr_help'
     * for handling hits to '/help').</p>
     * 
     * <p>If we have a handler, forward to it, otherwise forward to standard
     * getItem() method</p>
     */
    public void handleReq() throws Exception {

        Class [] noArgs;
        Method hdlrMethod;

        // strip useless leading slash from reqFile
        reqFile = reqFile.substring(1);

        // default to 'home'
        if (reqFile.equals("")) {
            reqFile = "home";
        }
        //print("handleReq: reqFile='"+reqFile+"'");
        
        // Set up the main page template
        try {
            tmplt = loadTemplate("main");
            pageItems = new Vector();
            tmplt.setParam("items", pageItems);
            tmplt.setParam("nodeType", node.nodeType);

        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
        //print("handleReq: loaded template");

        // execute if a command
        if (allVars.containsKey("cmd")) {
            do_cmd();
        }

        // --------------------------------------------------------
        // intercept magic paths for which we have a handler method
        noArgs = new Class[0];
        try {
            // extract top dir of path and make it method-name-safe
            String methodName = "hdlr_"+reqFile.split("/")[0].replace('.','_');
            hdlrMethod = this.getClass().getMethod(methodName, null);

            // now dispatch the method
            hdlrMethod.invoke(this, null);

            // spit out html, if no raw content inserted
            sendPageIfNoContent();
            
            // done
            return;

        } catch (NoSuchMethodException e) {
            // routinely fails if we dont' have handler, so assume it's
            // a GET
        }
        
        // if we get here, client is requesting a specific uri
        allVars.put("uri", reqFile);
        if (!cmd_get()) {
            hdlr_home();
        }
        sendPageIfNoContent();
    }

    /**
     * as name implies, generates standard html page
     * if setRawOutput hasnt' been called
     */
    public void sendPageIfNoContent() {

        if (rawContentBytes == null) {
            
            // we're spitting out html
            setContentType("text/html");
            
            // set up tab row style vector
            setupTabRow();
            
            // finally, render out our filled-out template
            setRawOutput(tmplt.output());
        }
    }

    /**
     * Inserts an item into main pane
     */
    public Object addToMainPane(Object item) {
        
        Hashtable h = new Hashtable();
        h.put("item", item);
        pageItems.addElement(h);
        return item;
    }

    /**
     * Generates a set of tabs and adds these to the page,
     * marking as active the tab whose name is in the current URL
     */
    public void setupTabRow()
    {
        Hashtable h;
        tabRow = new Vector();
        for (int i=0; i< tabNames.length; i++) {
            String name = tabNames[i];
            h = new Hashtable();
            h.put("name", name);
            h.put("label", name.substring(0,1).toUpperCase()+name.substring(1));
            if (name.equals(reqFile)) {
                h.put("active", "1");
            }
        tabRow.addElement(h);
        tmplt.setParam("tabs", tabRow);
        }
    }

    // -----------------------------------------------------
    // METHODS FOR HANDLING MAGIC PATHS
    // ----------------------------------------------------
    
    /** Display home page */
    public void hdlr_home() throws Exception {

        // stick in 'getitem' form
        addToMainPane(loadTemplate("getform"));

    }

    /** Display status page */
    public void hdlr_status() throws Exception {
        
        // ping the node, extract status items
        Vector statusItems = new Vector();
        Hashtable h = node.ping();
        for (Enumeration e = h.keys(); e.hasMoreElements();) {
            String key = (String)e.nextElement();
            String val = h.get(key).toString();
            if (val.length() > 60) {
                // too big for table, stick into a readonly text field
                val = "<input type=text size=60 readonly name=\"big_"+key+"\" value=\""+val+"\">";
            }
            Hashtable rec = new Hashtable();
            rec.put("key", key);
            rec.put("value", val);
            //print("key='"+key+"' val='"+val+"'");
            statusItems.addElement(rec);
        }

        // get status form template insert the items, stick onto main pane
        Template tmpltStatus = loadTemplate("status");
        tmpltStatus.setParam("items", statusItems);
        addToMainPane(tmpltStatus);

    }

    /** display current node jobs list */
    public void hdlr_jobs() throws Exception {

        // get jobs list, add to jobs list template, add that to main pane
        Template tmpltJobs = loadTemplate("jobs");
        tmpltJobs.setParam("items", node.getJobsList());
        addToMainPane(tmpltJobs);
    }

    /** Display search form */
    public void hdlr_search() throws Exception {
        addToMainPane(loadTemplate("searchform"));
    }

    /** Display insert page */
    public void hdlr_insert() throws Exception {

        String formName = allVars.get("mode", 0, "file").equals("site") ? "putsiteform" : "putform";
        Template tmpltPut = loadTemplate(formName);
        addToMainPane(tmpltPut);
    }

    /** Display settings screen */
    public void hdlr_settings() throws Exception {
        addToMainPane(loadTemplate("settings"));
    }

    /** Display tools screen */
    public void hdlr_tools() throws Exception {

        addToMainPane(loadTemplate("tools"));
        addToMainPane(loadTemplate("genkeysform"));
        addToMainPane(loadTemplate("addrefform"));
    }

    /** Display help screen */
    public void hdlr_help() throws Exception {
        addToMainPane(loadTemplate("help"));
    }

    /** Display about screen */
    public void hdlr_about() throws Exception {
        addToMainPane(loadTemplate("about"));
    }

    /** handle /favicon.ico hits */
    public void hdlr_favicon_ico() {

        System.out.println("Sending favicon image");
        setContentType("image/x-icon");
        setRawOutput(Favicon.image);
    }

    /** dummy handler, causes an exception (for testing error dump pages */
    public void hdlr_shit() throws Exception {
        throw new Exception("this method is shit");
    }

    // ----------------------------------------------------
    // METHODS FOR HANDLING COMMANDS
    // ----------------------------------------------------

    /**
     * invoked if GET or POST vars contain 'cmd'.
     * attempts to dispatch command handler method 'cmd_xxxx'
     */
    public void do_cmd() throws Exception {

        // this whole method could be done in python with the statement:
        //   getattr(self, 'cmd_'+urlVars['cmd'], lambda:None)()
        String cmd = allVars.get("cmd", 0);
        try {
            // extract top dir of path and make it method-name-safe
            String methodName = "cmd_"+cmd;
            Method hdlrMethod = this.getClass().getMethod(methodName, null);

            // now dispatch the method
            hdlrMethod.invoke(this, null);
        } catch (NoSuchMethodException e) {}
    }


    /**
     * executes a 'get' cmd
     */
    public boolean cmd_get() throws Exception {
        
        Hashtable result = null;
        String status = null;
        Hashtable metadata = null;
        String mimetype = null;
        
        // bail if node offline
        if (node == null) {
            return false;
        }
        
        // bail if no 'url' arg
        if (!allVars.containsKey("uri")) {
            return false;
        }
        
        // get uri, prepend 'Q:' if needed
        String uri = allVars.get("uri", 0);
        if (!uri.startsWith("Q:")) {
            uri = "Q:" + uri;
        }

        // attempt the fetch
        result = node.getItem(uri);
        status = (String)result.get("status");

        // how'd we go?
        if (status.equals("ok")) {
            // got it - send it back
            metadata = (Hashtable)result.get("metadata");
            mimetype = (String)metadata.get("mimetype");

            // forbid content retrieval via MSIE
            boolean isIE = false;
            for (Enumeration e = headerVars.get("User-Agent").elements(); e.hasMoreElements();) {
                String val = ((String)e.nextElement()).toLowerCase();
                if (val.matches(".*(msie|windows|\\.net).*")) {
                    Template warning = loadTemplate("msiealert");
                    addToMainPane(warning);
                    return false;
                }
            }
            
            // forbid direct delivery of text/* content via direct tcp
            if (isRunningOverTcp) {
                // security feature - set to application/octet-stream if req arrives via tcp.
                // this prevents people surfing the q web interface directly over TCP and
                // falling prey to anonymity attacks (eg gif bugs)
                
                // if user is trying to hit an html page, we can send back a warning
                if (mimetype.startsWith("text")) {
                    Template warning = loadTemplate("anonalert");
                    warning.setParam("dest", node.destStr);
                    addToMainPane(warning);
                    return false;
                }
                setContentType("application/octet-stream");
            } else {
                // got this conn via I2P and eeproxy - safer to obey the mimetype
                setContentType(mimetype);
            }

            setRawOutput((byte [])result.get("data"));
            return true;
        } else {
            // 404
            tmplt.setParam("show_404", "1");
            tmplt.setParam("404_uri", uri);
            return false;
        }
    }

    /** executes genkeys command */
    public void cmd_genkeys() throws Exception {
        
        Hashtable res = node.newKeys();
        String pubKey = (String)res.get("publicKey");
        String privKey = (String)res.get("privateKey");
        Template keysWidget = loadTemplate("genkeysresult");
        keysWidget.setParam("publickey", pubKey);
        keysWidget.setParam("privatekey", privKey);
        addToMainPane(keysWidget);
    }
    
    /** adds a noderef */
    public void cmd_addref() throws Exception {

        String ref = allVars.get("noderef", 0).trim();
        node.hello(ref);
    }

    /** executes 'put' command */
    public void cmd_put() throws Exception {

        // barf if user posted both data and rawdata
        if (allVars.containsKey("data")
            && ((String)allVars.get("data", 0)).length() > 0
            && allVars.containsKey("rawdata")
            && ((String)allVars.get("rawdata", 0)).length() > 0
            )
        {
            Template t = loadTemplate("puterror");
            t.setParam("error", "you specified a file as well as 'rawdata'");
            addToMainPane(t);
            addToMainPane(dumpVars().toString());
            return;
        }

        Hashtable metadata = new Hashtable();
        byte [] data = new byte[0];
        
        // stick in some defaults
        String [] keys = {
            "data", "rawdata",
            "mimetype", "keywords", "privkey", "abstract", "type", "title",
            "path"
        };

        //System.out.println("allVars='"+allVars+"'");

        // extract all items from form, add to metadata ones that
        // have non-zero length. Take 'data' or 'rawdata' and stick their
        // bytes into data.
        for (int i=0; i<keys.length; i++) {
            String key = keys[i];
            if (allVars.containsKey(key)) {
                //System.out.println("posted item '"+key+"'");

                if (key.equals("data")) {
                    byte [] dataval = allVars.get("data", 0).getBytes();
                    if (dataval.length > 0) {
                        data = dataval;
                    }
                } else if (key.equals("rawdata")) {
                    byte [] dataval = allVars.get("rawdata", 0).getBytes();
                    if (dataval.length > 0) {
                        data = dataval;
                    }
                } else if (key.equals("privkey")) {
                    String k = allVars.get("privkey", 0);
                    if (k.length() > 0) {
                        metadata.put("privateKey", k);
                    }
                } else {
                    String val = allVars.get(key, 0);
                    //System.out.println("'"+key+"'='"+val+"'");
                    if (val.length() > 0) {
                        metadata.put(key, allVars.get(key, 0));
                    }
                }
            }
        }

        //System.out.println("metadata="+metadata);

        if (metadata.size() == 0) {
            Template err = loadTemplate("puterror");
            err.setParam("error", "No metadata!");
            addToMainPane(err);
            addToMainPane(dumpVars().toString());
            return;
        }

        if (data.length == 0) {
            Template err = loadTemplate("puterror");
            err.setParam("error", "No data!");
            addToMainPane(err);
            addToMainPane(dumpVars().toString());
            return;
        }

        // phew! ready to put
        System.out.println("WEB:cmd_put: inserting");
        
        Hashtable result = node.putItem(metadata, data);
        
        System.out.println("WEB:cmd_put: got"+result);

        String status = (String)result.get("status");
        if (!status.equals("ok")) {
            String errTxt = (String)result.get("error");
            if (result.containsKey("summary")) {
                errTxt = errTxt + ":" + result.get("summary").toString();
            }
            Template err = loadTemplate("puterror");
            err.setParam("error", (String)result.get("error"));
            addToMainPane(err);
            addToMainPane(dumpVars().toString());
            return;
        }

        // success, yay!
        Template success = loadTemplate("putok");
        success.setParam("uri", (String)result.get("uri"));
        addToMainPane(success);

        //System.out.println("cmd_put: debug on page??");
        //addToMainPane(dumpVars().toString());
    }

    /** executes 'putsite' command */
    public void cmd_putsite() throws Exception {

        Hashtable metadata = new Hashtable();
        String privKey = allVars.get("privkey", 0, "");
        String name = allVars.get("name", 0, "");
        String dir = allVars.get("dir", 0, "");

        // pick up optional metadata items
        String [] keys = {
            "title", "keywords", "abstract",
        };

        // extract all items from form, add to metadata ones that
        // have non-zero length.
        for (int i=0; i<keys.length; i++) {
            String key = keys[i];
            if (allVars.containsKey(key)) {
                //System.out.println("posted item '"+key+"'");
                String val = allVars.get(key, 0);
                //System.out.println("'"+key+"'='"+val+"'");
                if (val.length() > 0) {
                    metadata.put(key, allVars.get(key, 0));
                }
            }
        }

        //System.out.println("metadata="+metadata);

        if (metadata.size() == 0) {
            cmd_putsite_error("No metadata!");
            return;
        }

        // phew! ready to put
        Hashtable result = node.insertQSite(privKey, name, dir, metadata);
        String status = (String)result.get("status");
        if (!status.equals("ok")) {
            cmd_putsite_error((String)result.get("error"));
            return;
        }

        // success, yay!
        Template success = loadTemplate("putok");
        success.setParam("is_site", "1");
        success.setParam("uri", (String)result.get("uri"));
        addToMainPane(success);

        //System.out.println("cmd_put: debug on page??");
        //addToMainPane(dumpVars().toString());
    }

    protected void cmd_putsite_error(String msg) throws Exception {
        
        Template err = loadTemplate("puterror");
        err.setParam("error", msg);
        err.setParam("is_site", "1");
        addToMainPane(err);
        addToMainPane(dumpVars().toString());
    }

    /** performs a search */
    public void cmd_search() throws Exception {

        Hashtable criteria = new Hashtable();
        String [] fields = {
            "type", "title", "path", "mimetype", "keywords",
            "summary", "searchmode"
        };

        for (int i=0; i<fields.length; i++) {
            String fieldName = fields[i];
            String fieldVal = allVars.get(fieldName, 0, null);
            if (fieldVal == null) {
                continue;
            }

            if (fieldName.equals("type") && fieldVal.equals("any")) {
                continue;
            }

            if (!fieldVal.equals("")) {
                if (!(fieldName.equals("searchmode") || allVars.containsKey(fieldName+"_re"))) {
                    // convert into a regexp which matches exact substring
                    fieldVal = ".*\\Q" + fieldVal + "\\E.*";
                }
                criteria.put(fieldName, fieldVal);
            }
        }

        //addToMainPane("Search criteria: "+criteria);
        
        Hashtable result = node.search(criteria);
        Vector items = (Vector)result.get("items");

        // stick up search results form
        Template results = loadTemplate("searchresults");
        System.out.println("items="+items);
        results.setParam("results", items);
        results.setParam("numresults", items.size());
        addToMainPane(results);
        
        //addToMainPane(dumpVars().toString());
    }


    // -----------------------------------------------------
    // NODE INTERACTION METHODS
    // -----------------------------------------------------

    public void do_get(String uri) {

        // prefix uri with 'Q:' if needed
        if (!uri.startsWith("Q:")) {
            uri = "Q:"+uri;
        }

        print("GET "+uri);
    }

    // ----------------------------------------------------
    // DIAGNOSTICS METHODS
    // ----------------------------------------------------
    
    /**
     * Sends back a 400 status, together with a disgnostic page
     * showing the stack dump and the HTTP url and form variables
     */
    public void dump_error(Throwable e) {

        // un-wrap InvocationMethodException
        if (e instanceof InvocationTargetException) {
            Throwable e1 = e.getCause();
            if (e1 != null) {
                e = e1;
            }
        }

        // set up barf reply
        setStatus("HTTP/1.0 400 Error");

        setContentType("text/html");

        // render the exception into raw string
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(os);
        e.printStackTrace(ps);
        String eText = new String(os.toByteArray());

        // generate an html page
        HtmlPage page = new HtmlPage();
        page.head.add("title").raw("Q Error");

        // now dump out the trace, and our HTTP vars
        page.body
            .nest("h3")
                .nest("i")
                    .raw("Dammit!!")
                    .end
                .end
            .hr()
            .raw("There was an internal error processing your request")
            .br().hr()
            .nest("code")
                .nest("pre")
                    .raw(eText)
                    .end
                .end
            .hr()
            .add(dumpVars())
            ;

        setRawOutput(page.toString());
        e.printStackTrace();
    }

    public static void print(String arg) {
        System.out.println("QClientWebInterface: "+arg);
    }

    /**
     * run up this web interface on a local port, for testing
     */
    public static void main(String [] args) {

        // just for testing
        System.setProperty("i2cp.tcp.host", "10.0.0.1");

        try {
            QClientNode node = new QClientNode();
            //node.start();
            node.run();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

