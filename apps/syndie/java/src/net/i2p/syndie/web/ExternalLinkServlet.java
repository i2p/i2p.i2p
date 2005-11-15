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
 * Confirm page before hitting a remote site
 *
 */
public class ExternalLinkServlet extends BaseServlet { 
    protected String getTitle() { return "Syndie :: External link"; }
    
    protected void renderServletDetails(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index, 
                                        int threadOffset, BlogURI visibleEntry, Archive archive) throws IOException {
        String b64Schema = req.getParameter("schema");
        String b64Location = req.getParameter("location");
        if ( (b64Schema == null) || (b64Schema.trim().length() <= 0) ||
             (b64Location == null) || (b64Location.trim().length() <= 0) ) {
            out.write("<tr><td colspan=\"3\">No location specified</td></tr>\n");
        } else {
            byte loc[] = Base64.decode(b64Location);
            if ( (loc == null) || (loc.length <= 0) ) {
                out.write("<tr><td colspan=\"3\">Invalid location specified</td></tr>\n");
            } else {
                String location = DataHelper.getUTF8(loc);
                out.write("<tr><td colspan=\"3\">Are you sure you want to go to <a href=\"");
                out.write(HTMLRenderer.sanitizeTagParam(location));
                out.write("\" title=\"Link to an external site\">");
                out.write(HTMLRenderer.sanitizeString(location));
                out.write("</a></td></tr>\n");
            }
        }
    }
}
