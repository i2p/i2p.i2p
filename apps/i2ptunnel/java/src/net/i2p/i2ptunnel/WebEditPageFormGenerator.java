package net.i2p.i2ptunnel;

import java.io.File;

import java.util.Iterator;
import java.util.Properties;
import java.util.Random;
import java.util.StringTokenizer;

/**
 * Uuuugly code to generate the edit/add forms for the various 
 * I2PTunnel types (httpclient/client/server)
 *
 */
class WebEditPageFormGenerator {
    private static final String SELECT_TYPE_FORM =
      "<form action=\"edit.jsp\"> Type of tunnel: <select name=\"type\">" +
      "<option value=\"httpclient\">HTTP proxy</option>" +
      "<option value=\"client\">Client tunnel</option>" +
      "<option value=\"server\">Server tunnel</option>" +
      "</select> <input type=\"submit\" value=\"GO\" />" +
      "</form>\n";
    
    /**
     * Retrieve the form requested
     *
     */
    public static String getForm(WebEditPageHelper helper) {
        TunnelController controller = helper.getTunnelController();

        if ( (helper.getType() == null) && (controller == null) )
            return SELECT_TYPE_FORM;
        
        String id = helper.getNum();
        String type = helper.getType();
        if (controller != null)
            type = controller.getType();
        
        if ("httpclient".equals(type))
            return getEditHttpClientForm(controller, id);
        else if ("client".equals(type))
            return getEditClientForm(controller, id);
        else if ("server".equals(type))
            return getEditServerForm(controller, id);
        else
            return "WTF, unknown type [" + type + "]";
    }
    
    private static String getEditHttpClientForm(TunnelController controller, String id) {
        StringBuffer buf = new StringBuffer(1024);
        addGeneral(buf, controller, id);
        buf.append("<b>Type:</b> <i>HTTP proxy</i><input type=\"hidden\" name=\"type\" value=\"httpclient\" /><br />\n");
        
        addListeningOn(buf, controller, 4444);
        
        buf.append("<b>Outproxies:</b> <input type=\"text\" name=\"proxyList\" size=\"20\" ");
        if ( (controller != null) && (controller.getProxyList() != null) )
            buf.append("value=\"").append(controller.getProxyList()).append("\" ");
        else
            buf.append("value=\"squid.i2p\" ");
        buf.append("/><br />\n");
        
        buf.append("<hr />Note: the following options are shared across all client tunnels and");
        buf.append(" HTTP proxies<br />\n");
        
        addOptions(buf, controller);
        buf.append("<input type=\"submit\" name=\"action\" value=\"Save\">\n");
        buf.append("<input type=\"submit\" name=\"action\" value=\"Remove\">\n");
        buf.append(" <i>confirm removal:</i> <input type=\"checkbox\" name=\"removeConfirm\" value=\"true\" />\n");
        buf.append("</form>\n");
        return buf.toString();
    }
    
    private static String getEditClientForm(TunnelController controller, String id) {
        StringBuffer buf = new StringBuffer(1024);
        addGeneral(buf, controller, id);
        buf.append("<b>Type:</b> <i>Client tunnel</i><input type=\"hidden\" name=\"type\" value=\"client\" /><br />\n");
        
        addListeningOn(buf, controller, 2025 + new Random().nextInt(1000)); // 2025 since nextInt can be negative

        buf.append("<b>Target:</b> <input type=\"text\" size=\"40\" name=\"targetDestination\" ");
        if ( (controller != null) && (controller.getTargetDestination() != null) )
            buf.append("value=\"").append(controller.getTargetDestination()).append("\" ");
        buf.append(" /> (either the hosts.txt name or the full base64 destination)<br />\n");
        
        buf.append("<hr />Note: the following options are shared across all client tunnels and");
        buf.append(" HTTP proxies<br />\n");
        
        addOptions(buf, controller);
        buf.append("<input type=\"submit\" name=\"action\" value=\"Save\"><br />\n");
        buf.append("<input type=\"submit\" name=\"action\" value=\"Remove\">\n");
        buf.append(" <i>confirm removal:</i> <input type=\"checkbox\" name=\"removeConfirm\" value=\"true\" />\n");
        buf.append("</form>\n");
        return buf.toString();
    }
    
    private static String getEditServerForm(TunnelController controller, String id) {
        StringBuffer buf = new StringBuffer(1024);
        addGeneral(buf, controller, id);
        buf.append("<b>Type:</b> <i>Server tunnel</i><input type=\"hidden\" name=\"type\" value=\"server\" /><br />\n");
        
        buf.append("<b>Target host:</b> <input type=\"text\" size=\"40\" name=\"targetHost\" ");
        if ( (controller != null) && (controller.getTargetHost() != null) )
            buf.append("value=\"").append(controller.getTargetHost()).append("\" ");
        else
            buf.append("value=\"localhost\" ");
        buf.append(" /><br />\n");
        
        buf.append("<b>Target port:</b> <input type=\"text\" size=\"4\" name=\"targetPort\" ");
        if ( (controller != null) && (controller.getTargetPort() != null) )
            buf.append("value=\"").append(controller.getTargetPort()).append("\" ");
        else
            buf.append("value=\"80\" ");
        buf.append(" /><br />\n");
        
        buf.append("<b>Private key file:</b> <input type=\"text\" name=\"privKeyFile\" value=\"");
        if ( (controller != null) && (controller.getPrivKeyFile() != null) ) {
            buf.append(controller.getPrivKeyFile()).append("\" /><br />");
        } else {
            buf.append("myServer.privKey\" /><br />");
            buf.append("<input type=\"hidden\" name=\"privKeyGenerate\" value=\"true\" />");
        }
        
        addOptions(buf, controller);
        buf.append("<input type=\"submit\" name=\"action\" value=\"Save\">\n");
        buf.append("<input type=\"submit\" name=\"action\" value=\"Remove\">\n");
        buf.append(" <i>confirm removal:</i> <input type=\"checkbox\" name=\"removeConfirm\" value=\"true\" />\n");
        buf.append("</form>\n");
        return buf.toString();
    }
    
    /**
     * Start off the form and add some common fields (name, num, description)
     *
     * @param buf where to shove the form
     * @param controller tunnel in question, or null if we're creating a new tunnel
     * @param id index into the current list of tunnelControllerGroup.getControllers() list
     *           (or null if we are generating an 'add' form)
     */
    private static void addGeneral(StringBuffer buf, TunnelController controller, String id) {
        buf.append("<form action=\"edit.jsp\">");
        if (id != null)
            buf.append("<input type=\"hidden\" name=\"num\" value=\"").append(id).append("\" />");
            
        buf.append("<b>Name:</b> <input type=\"text\" name=\"name\" size=\"20\" ");
        if ( (controller != null) && (controller.getName() != null) )
            buf.append("value=\"").append(controller.getName()).append("\" ");
        buf.append("/><br />\n");
        
        buf.append("<b>Description:</b> <input type=\"text\" name=\"description\" size=\"60\" ");
        if ( (controller != null) && (controller.getDescription() != null) )
            buf.append("value=\"").append(controller.getDescription()).append("\" ");
        buf.append("/><br />\n");
        
        buf.append("<b>Start automatically?</b> \n");
        buf.append("<input type=\"checkbox\" name=\"startOnLoad\" value=\"true\" ");
        if ( (controller != null) && (controller.getStartOnLoad()) )
            buf.append(" checked=\"true\" />\n<br />\n");
        else
            buf.append(" />\n<br />\n");
    }

    /**
     * Generate the fields asking for what port and interface the tunnel should 
     * listen on.
     *
     * @param buf where to shove the form
     * @param controller tunnel in question, or null if we're creating a new tunnel
     * @param defaultPort if we are creating a new tunnel, default the form to the given port
     */
    private static void addListeningOn(StringBuffer buf, TunnelController controller, int defaultPort) {
        buf.append("<b>Listening on port:</b> <input type=\"text\" name=\"port\" size=\"20\" ");
        if ( (controller != null) && (controller.getListenPort() != null) )
            buf.append("value=\"").append(controller.getListenPort()).append("\" ");
        else
            buf.append("value=\"").append(defaultPort).append("\" ");
        buf.append("/><br />\n");

        String selectedOn = null;
        if ( (controller != null) && (controller.getListenOnInterface() != null) )
            selectedOn = controller.getListenOnInterface();
        
        buf.append("<b>Reachable by:</b> ");
        buf.append("<select name=\"reachableBy\">");
        buf.append("<option value=\"127.0.0.1\" ");
        if ( (selectedOn != null) && ("127.0.0.1".equals(selectedOn)) )
            buf.append("selected=\"true\" ");
        buf.append(">Locally (127.0.0.1)</option>\n");
        buf.append("<option value=\"0.0.0.0\" ");
        if ( (selectedOn != null) && ("0.0.0.0".equals(selectedOn)) )
            buf.append("selected=\"true\" ");
        buf.append(">Everyone (0.0.0.0)</option>\n");
        buf.append("</select> ");
        buf.append("Other: <input type=\"text\" name=\"reachableByOther\" value=\"");
        if ( (selectedOn != null) && (!"127.0.0.1".equals(selectedOn)) && (!"0.0.0.0".equals(selectedOn)) )
            buf.append(selectedOn);
        buf.append("\"><br />\n");
    }
    
    /**
     * Add fields for customizing the I2PSession options, including helpers for
     * tunnel depth and count, as well as I2CP host and port.
     *
     * @param buf where to shove the form
     * @param controller tunnel in question, or null if we're creating a new tunnel
     */
    private static void addOptions(StringBuffer buf, TunnelController controller) {
        int tunnelDepth = 2;
        int numTunnels = 2;
        int connectDelay = 0;
        Properties opts = getOptions(controller);
        if (opts != null) {
            String depth = opts.getProperty("tunnels.depthInbound");
            if (depth != null) {
                try {
                    tunnelDepth = Integer.parseInt(depth);
                } catch (NumberFormatException nfe) {
                    tunnelDepth = 2;
                }
            }
            String num = opts.getProperty("tunnels.numInbound");
            if (num != null) {
                try {
                    numTunnels = Integer.parseInt(num);
                } catch (NumberFormatException nfe) {
                    numTunnels = 2;
                }
            }
            String delay = opts.getProperty("i2p.streaming.connectDelay");
            if (delay != null) {
                try {
                    connectDelay = Integer.parseInt(delay);
                } catch (NumberFormatException nfe) {
                    connectDelay = 0;
                }
            }
        }
        
        buf.append("<b>Tunnel depth:</b> ");
        buf.append("<select name=\"tunnelDepth\">");
        buf.append("<option value=\"0\" ");
        if (tunnelDepth == 0) buf.append(" selected=\"true\" ");
        buf.append(">0 hop tunnel (low anonymity, low latency)</option>");
        buf.append("<option value=\"1\" ");
        if (tunnelDepth == 1) buf.append(" selected=\"true\" ");
        buf.append(">1 hop tunnel (medium anonymity, medium latency)</option>");
        buf.append("<option value=\"2\" ");
        if (tunnelDepth == 2) buf.append(" selected=\"true\" ");
        buf.append(">2 hop tunnel (high anonymity, high latency)</option>");
        if (tunnelDepth > 2) {
            buf.append("<option value=\"").append(tunnelDepth).append("\" selected=\"true\" >");
            buf.append(tunnelDepth);
            buf.append(" hop tunnel (custom)</option>");
        }
        buf.append("</select><br />\n");
        
        buf.append("<b>Tunnel count:</b> ");
        buf.append("<select name=\"tunnelCount\">");
        buf.append("<option value=\"1\" ");
        if (numTunnels == 1) buf.append(" selected=\"true\" ");
        buf.append(">1 inbound tunnel (low bandwidth usage, less reliability)</option>");
        buf.append("<option value=\"2\" ");
        if (numTunnels == 2) buf.append(" selected=\"true\" ");
        buf.append(">2 inbound tunnels (standard bandwidth usage, standard reliability)</option>");
        buf.append("<option value=\"3\" ");
        if (numTunnels == 3) buf.append(" selected=\"true\" ");
        buf.append(">3 inbound tunnels (higher bandwidth usage, higher reliability)</option>");
        
        if (numTunnels > 3) {
            buf.append("<option value=\"").append(numTunnels).append("\" selected=\"true\" >");
            buf.append(numTunnels);
            buf.append(" inbound tunnels (custom)</option>");
        }
        buf.append("</select><br />\n");
        
        buf.append("<b>Delay connection briefly? </b> ");
        buf.append("<input type=\"checkbox\" name=\"connectDelay\" value=\"");
        buf.append((connectDelay > 0 ? connectDelay : 1000)).append("\" ");
        if (connectDelay > 0)
            buf.append("checked=\"true\" ");
        buf.append("/> (useful for brief request/response connections)<br />\n");
        
        buf.append("<b>I2CP host:</b> ");
        buf.append("<input type=\"text\" name=\"clientHost\" size=\"20\" value=\"");
        if ( (controller != null) && (controller.getI2CPHost() != null) )
            buf.append(controller.getI2CPHost());
        else
            buf.append("localhost");
        buf.append("\" /><br />\n");
        buf.append("<b>I2CP port:</b> ");
        buf.append("<input type=\"text\" name=\"clientPort\" size=\"20\" value=\"");
        if ( (controller != null) && (controller.getI2CPPort() != null) )
            buf.append(controller.getI2CPPort());
        else
            buf.append("7654");
        buf.append("\" /><br />\n");
        
        buf.append("<b>Other custom options:</b> \n");
        buf.append("<input type=\"text\" name=\"customOptions\" size=\"60\" value=\"");
        if (opts != null) {
            int i = 0;
            for (Iterator iter = opts.keySet().iterator(); iter.hasNext(); ) {
                String key = (String)iter.next();
                String val = opts.getProperty(key);
                if ("tunnels.depthInbound".equals(key)) continue;
                if ("tunnels.numInbound".equals(key)) continue;
                if ("i2p.streaming.connectDelay".equals(key)) continue;
                if (i != 0) buf.append(' ');
                buf.append(key).append('=').append(val);
                i++;
            }
        }
        buf.append("\" /><br />\n");
    }

    /**
     * Retrieve the client options from the tunnel
     *
     * @return map of name=val to be used as I2P session options
     */
    private static Properties getOptions(TunnelController controller) {
        if (controller == null) return null;
        String opts = controller.getClientOptions();
        StringTokenizer tok = new StringTokenizer(opts);
        Properties props = new Properties();
        while (tok.hasMoreTokens()) {
            String pair = tok.nextToken();
            int eq = pair.indexOf('=');
            if ( (eq <= 0) || (eq >= pair.length()) )
                continue;
            String key = pair.substring(0, eq);
            String val = pair.substring(eq+1);
            props.setProperty(key, val);
        }
        return props;
    }
}
