/*
 * QConsole.java
 *
 * Created on 19 April 2005, 12:28
 */

package net.i2p.aum.q;

import java.io.*;
import java.net.*;
import java.util.*;

import javax.servlet.*;
import javax.servlet.http.*;

import org.apache.xmlrpc.*;

import HTML.*; 

/**
 *
 * @author  david
 * @version
 */
public class QConsole extends HttpServlet {
    
    XmlRpcClient nodeProxy;

    boolean nodeExists = false;
    boolean nodeIsRunning = false;
    String nodeDirStr;
    File nodeDir;
    String nodePrivKey;
    String sep = File.separator;
    String cpsep = File.pathSeparator;
    Properties options;
    
    /** Initializes the servlet.
     */
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        try {
            nodeProxy = new XmlRpcClient("http://localhost:7651");
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // load config, if any
        options = new Properties();
        try {
            FileInputStream f = new FileInputStream("qconsole.conf");
            options.load(f);
            
        } catch (Exception e) {
            // no valid config
            e.printStackTrace();
            System.out.println("BAD OR MISSING CONF");
        }
    }
    
    /** Destroys the servlet.
     */
    public void destroy() {
        
    }
    
    /** Processes requests for both HTTP <code>GET</code> and <code>POST</code> methods.
     * @param request servlet request
     * @param response servlet response
     */
    protected void processRequest(HttpServletRequest request, HttpServletResponse response)
    throws ServletException, IOException {

        findNode();
        determineIfNodeIsRunning();

        Hashtable vars = parseVars(request.getQueryString());
        
        response.setContentType("text/html");
        PrintWriter out = response.getWriter();
        out.println("<html>");
        out.println("<head>");
        out.println("<title>QConsole</title>");
        out.println("</head>");
        out.println("<body>");

        out.println("<h1>Q Node Manager</h1>");

        //out.println("debug: vars='"+vars+"'<br><br>");

        if (vars.containsKey("startnode") && !nodeIsRunning) {
            startNode();
            if (!nodeIsRunning) {
                out.println("Failed to start node :(<br><br>");
            }

        } else if (vars.containsKey("stopnode") && nodeIsRunning) {
            stopNode();
            nodeIsRunning = false;
        }
        
        if (nodeIsRunning) {
            out.println("Q Node is running<br><br>");
            out.print("<a href=\"http://localhost:7651\">Node Console</a>");
            out.print(" | ");
            out.println("<a href=\"/q?stopnode\">Stop Node</a>");
        } else {
            out.println("Q Node is not running<br><br>");
            out.println("<a href=\"/q?startnode\">Start Node</a>");
        }

        out.println("</body>");
        out.println("</html>");
        /* */
        out.close();
    }
    
    /** Handles the HTTP <code>GET</code> method.
     * @param request servlet request
     * @param response servlet response
     */
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
    throws ServletException, IOException {
        processRequest(request, response);
    }
    
    /** Handles the HTTP <code>POST</code> method.
     * @param request servlet request
     * @param response servlet response
     */
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
    throws ServletException, IOException {
        processRequest(request, response);
    }
    
    /** Returns a short description of the servlet.
     */
    public String getServletInfo() {
        return "Short description";
    }

    /** try to find node */
    public void findNode() {

        try {
            nodeDirStr = System.getProperties().getProperty("user.home")
                        + sep + ".quartermaster_client";
                        
            // yay, found a node (we hope), create an xmlrpc client for talking
            // to that node
            String propPath = nodeDirStr + sep + "node.conf";
            File propFile = new File(propPath);
            FileInputStream propIn = new FileInputStream(propPath);
            Properties prop = new Properties();
            prop.load(propIn);

            nodePrivKey = prop.getProperty("privKey");

            // presence of private key indicates node exists
            nodeExists = nodePrivKey != null;
            
        } catch (Exception e) {
            // node doesn't exist
        }

    }
    
    public void startNode() {
        
        int i;
        String [] jars = {
            "i2p", "mstreaming", "aum",
        };
        
        String cp = "";

        String jarsDir = "lib";
        
        for (i=0; i<jars.length; i++) {
            if (i> 0) {
                cp += cpsep;
            }
            cp += jarsDir + sep + jars[i] + ".jar";
        }

        System.out.println("cp='"+cp+"'");
        
        // build up args
        int nopts = options.size();
        String args = "";
        args += "java";
        for (Enumeration e = options.propertyNames(); e.hasMoreElements();) {
            String opt = (String)e.nextElement();
            String arg = "-D" + opt + "=" + options.getProperty(opt);
            System.out.println(arg);
            args += " " + arg;
        }
            
        args += " -cp " + cp;
        args += " net.i2p.aum.q.QMgr";
        args += " foreground";
        
        Runtime runtime = Runtime.getRuntime();

        // and spawn the start job
        try {
            //runtime.exec(startForegroundArgs, propLines);
            System.out.println("args='"+args+"'");
            runtime.exec(args, null);
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        // wait a bit
        sleep(3);

        // try for 10s to contact node
        for (i=0; i<10; i++) {
            sleep(1);
            determineIfNodeIsRunning();
            if (nodeIsRunning) {
                break;
            }
        }
    }
    
    public void stopNode() {

        Vector args = new Vector();
        args.addElement(nodePrivKey);
        try {
            System.out.println("stopping node...");
            nodeProxy.execute("i2p.q.shutdown", args);
        } catch (Exception e) {
            
        }
        System.out.println("node terminated");
    }

    /** returns true if node is up */
    public void determineIfNodeIsRunning() {
        try {
            nodeProxy.execute("i2p.q.ping", new Vector());
            nodeIsRunning = true;
        } catch (Exception e) {
            nodeIsRunning = false;
            return;
        }
    }

    public void sleep(int n) {
        try {
            Thread.sleep(n * 1000);
        } catch (Exception e) {}
    }

    public Hashtable parseVars(String raw) {
        Hashtable h = new Hashtable();

        if (raw == null) {
            return h;
        }

        URLDecoder u = new URLDecoder();
        String [] items = raw.split("[&]");
        String dec;
        for (int i=0; i<items.length; i++) {
            try {
                dec = u.decode(items[i], "ISO-8859-1");
                String [] items1 = dec.split("[=]",2);
                //System.out.println("parseVars: "+items1[0]+"="+items1[1]);
                h.put(items1[0], items1.length > 1 ? items1[1] : "");
            } catch (Exception e) {
                    e.printStackTrace();
            }
        }
        
        return h;
    }

}
