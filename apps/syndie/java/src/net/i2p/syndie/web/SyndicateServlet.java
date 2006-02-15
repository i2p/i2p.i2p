package net.i2p.syndie.web;

import java.io.*;
import java.util.*;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletException;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.*;
import net.i2p.data.*;
import net.i2p.syndie.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.sml.*;

/**
 * Syndicate with another remote Syndie node
 *
 */
public class SyndicateServlet extends BaseServlet { 
    protected String getTitle() { return "Syndie :: Syndicate"; }
    
    public static final String PARAM_SCHEMA = "schema";
    public static final String PARAM_LOCATION = "location";
    public static final String PARAM_PETNAME = "petname";
    
    protected void renderServletDetails(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index, 
                                        int threadOffset, BlogURI visibleEntry, Archive archive) throws IOException {
        if (!BlogManager.instance().authorizeRemote(user)) { 
            out.write("<tr><td colspan=\"3\">Sorry, you are not authorized to access remote archives</td></tr>\n");
        } else {
            out.write("<form action=\"" + req.getRequestURI() + "\" method=\"POST\">");
            displayForm(user, req, out);
            handleRequest(user, req, index, out);
            out.write("</form>\n");
        }            
    }
    
    private void handleRequest(User user, HttpServletRequest req, ThreadIndex index, PrintWriter out) throws IOException {
        RemoteArchiveBean remote = getRemote(req);
        String action = req.getParameter("action");
        if ("Continue...".equals(action)) {
            String location = req.getParameter(PARAM_LOCATION);
            String pn = req.getParameter(PARAM_PETNAME);
            if ( (pn != null) && (pn.trim().length() > 0) ) {
                PetName pnval = user.getPetNameDB().getByName(pn);
                if (pnval != null) location = pnval.getLocation();
            }
            
            // dont allow caching if they explicit ask for a fetch
            boolean allowCaching = false;
            remote.fetchIndex(user, req.getParameter(PARAM_SCHEMA), location, 
                              req.getParameter("proxyhost"), 
                              req.getParameter("proxyport"), allowCaching);
        } else if ("Fetch metadata".equals(action)) {
            remote.fetchMetadata(user, req.getParameterMap());
        } else if ("Fetch selected entries".equals(action)) {
            //remote.fetchSelectedEntries(user, request.getParameterMap());
            remote.fetchSelectedBulk(user, req.getParameterMap());
        } else if ("Fetch all new entries".equals(action)) {
            //remote.fetchAllEntries(user, request.getParameterMap());
            remote.fetchSelectedBulk(user, req.getParameterMap());
        } else if ("Post selected entries".equals(action)) {
            remote.postSelectedEntries(user, req.getParameterMap());
        }
        String msgs = remote.getStatus();
        if ( (msgs != null) && (msgs.length() > 0) ) { 
            out.write("<pre class=\"b_remoteProgress\">");
            out.write(msgs);
            out.write("<a class=\"b_remoteProgress\" href=\"");
            out.write(req.getRequestURI());
            out.write("\">Refresh</a></pre><br />\n");
        }
        
        if (remote.getFetchIndexInProgress()) { 
            out.write("<span class=\"b_remoteProgress\">Please wait while the index is being fetched ");
            out.write("from ");
            out.write(remote.getRemoteLocation());
            out.write(".</span>");
        } else if (remote.getRemoteIndex() != null) {
            // remote index is NOT null!
            out.write("<span class=\"b_remoteLocation\">");
            out.write(remote.getRemoteLocation());
            out.write("</span>");
            out.write("<a class=\"b_remoteRefetch\" href=\"");
            out.write(req.getRequestURI());
            out.write("?" + PARAM_SCHEMA + "=" + remote.getRemoteSchema() + "&" + PARAM_LOCATION + "=" + remote.getRemoteLocation());
            if (remote.getProxyHost() != null && remote.getProxyPort() > 0) { 
                out.write("&proxyhost=" + remote.getProxyHost() + "&proxyport=" + remote.getProxyPort());
            } 
            out.write("&action=Continue...\">(refetch)</a>:<br />\n");
            
            remote.renderDeltaForm(user, BlogManager.instance().getArchive().getIndex(), out);
            out.write("<textarea class=\"b_remoteIndex\" rows=\"5\" cols=\"120\">" + 
                      remote.getRemoteIndex().toString() + "</textarea>");
        }
        
        out.write("</td></tr>\n");
    }

    private void displayForm(User user, HttpServletRequest req, PrintWriter out) throws IOException {
        writeAuthActionFields(out);
        out.write("<tr><td colspan=\"3\">");
        out.write("<span class=\"b_remoteChooser\"><span class=\"b_remoteChooserField\">Import from:</span>\n");
        out.write("<select class=\"b_remoteChooserNet\" name=\"" + PARAM_SCHEMA + "\">\n");
        String schema = req.getParameter(PARAM_SCHEMA);
        out.write("<option value=\"web\" ");
        if ("web".equals(schema))
            out.write("selected=\"true\" ");
        out.write(">I2P/Web/Tor/Freenet</option>\n");

        out.write("</select>\n");
        out.write("<span class=\"b_remoteChooserField\">Proxy</span>\n");
        out.write("<input class=\"b_remoteChooserHost\" type=\"text\" size=\"12\" name=\"proxyhost\" value=\"");
        out.write(BlogManager.instance().getDefaultProxyHost());
        out.write("\" title=\"hostname that your HTTP proxy is on, or blank for no proxy\" />\n");
        out.write("<input class=\"b_remoteChooserPort\" type=\"text\" size=\"4\" name=\"proxyport\" value=\"");
        out.write(BlogManager.instance().getDefaultProxyPort());
        out.write("\" title=\"port number that your HTTP proxy is on, or blank for no proxy\" /><br />\n");
        out.write("<span class=\"b_remoteChooserField\">Bookmarked archives:</span>\n");
        out.write("<select class=\"b_remoteChooserPN\" name=\"" + PARAM_PETNAME + "\">");
        out.write("<option value=\"\">Custom location</option>");

        for (Iterator iter = user.getPetNameDB().iterator(); iter.hasNext(); ) {
            PetName pn = (PetName)iter.next();
            if (AddressesServlet.PROTO_ARCHIVE.equals(pn.getProtocol())) {
                out.write("<option value=\"");
                out.write(HTMLRenderer.sanitizeTagParam(pn.getName()));
                out.write("\">");
                out.write(HTMLRenderer.sanitizeString(pn.getName()));
                out.write("</option>");
            }
        }
        out.write("</select> or ");
        out.write("<input type=\"text\" class=\"b_remoteChooserLocation\" name=\"" + PARAM_LOCATION + "\" size=\"30\" value=\"");
        String reqLoc = req.getParameter("location");
        if (reqLoc != null)
            out.write(reqLoc);
        out.write("\" title=\"full URL to the remote location, to be sent to your HTTP proxy\" />\n");
        out.write("<input class=\"b_remoteChooserContinue\" type=\"submit\" name=\"action\" value=\"Continue...\" /><br />\n");
        out.write("</span>\n");
    }

    private static final String ATTR_REMOTE = "remote";
    protected RemoteArchiveBean getRemote(HttpServletRequest req) {
        RemoteArchiveBean remote = (RemoteArchiveBean)req.getSession().getAttribute(ATTR_REMOTE);
        if (remote == null) {
            remote = new RemoteArchiveBean();
            req.getSession().setAttribute(ATTR_REMOTE, remote);
        }
        return remote;
    }
}
