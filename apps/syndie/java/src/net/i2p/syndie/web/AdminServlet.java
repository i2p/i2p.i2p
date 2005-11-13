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
 * Admin form
 *
 */
public class AdminServlet extends BaseServlet {   
    protected void renderServletDetails(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index, 
                                        int threadOffset, BlogURI visibleEntry, Archive archive) throws IOException {
        if (BlogManager.instance().authorizeRemote(user)) {
            displayForm(user, req, out);
        } else {
            out.write("<tr><td colspan=\"3\"><span class=\"b_adminMsgErr\">You are not authorized to configure this Syndie instance</span></td></tr>\n");
        }
    }
    
    private void displayForm(User user, HttpServletRequest req, PrintWriter out) throws IOException {
        out.write("<form action=\"" + req.getRequestURI() + "\" method=\"POST\">\n");
        out.write("<tr><td colspan=\"3\">");

        out.write("<em class=\"b_adminField\">Single user?</em> <input type=\"checkbox\" class=\"b_adminField\" name=\"singleuser\" ");
        if (BlogManager.instance().isSingleUser())
            out.write(" checked=\"true\" ");
        out.write(" /><br />\n");
        
        out.write("<span class=\"b_adminDescr\">If this is checked, the registration, admin, and remote passwords are unnecessary - anyone");
        out.write("can register and administer Syndie, as well as use any remote functionality.  This should not be checked if untrusted");
        out.write("parties can access this web interface.</span><br />\n");
        out.write("<span class=\"b_adminField\">Default user:</span> <input class=\"b_adminField\" type=\"text\" name=\"defaultUser\" size=\"10\" value=\"");
        out.write(BlogManager.instance().getDefaultLogin());
        out.write("\" />\n");
        out.write("<span class=\"b_adminField\">pass:</span> <input class=\"b_adminField\" type=\"text\" name=\"defaultPass\" size=\"10\" value=\"");
        out.write(BlogManager.instance().getDefaultPass());
        out.write("\"/><br />\n");
        out.write("<span class=\"b_adminDescr\">If Syndie is in single user mode, it will create a new 'default' user automatically and use that ");
        out.write("whenever you access Syndie unless you explicitly log in to another account.  If you want Syndie to use an existing account as ");
        out.write("your default account, you can specify them here, in which case it will automatically log you in under that account.</span><br />\n");
        out.write("<em class=\"b_adminField\">Registration password:</em> <input class=\"b_adminField\" type=\"text\" name=\"regpass\" size=\"10\" value=\"\" /><br />\n");
        out.write("<span class=\"b_adminDescr\">Users must specify this password on the registration form to proceed.  If this is ");
        out.write("blank, anyone can register.</span><br />\n");
        out.write("<em class=\"b_adminField\">Remote password:</em> <input class=\"b_adminField\" type=\"text\" name=\"remotepass\" size=\"10\" value=\"\" /><br />\n");
        out.write("<span class=\"b_adminDescr\">To access remote archives, users must first provide this password on their ");
        out.write("metadata page.  Remote access is 'dangerous', as it allows the user to instruct ");
        out.write("this Syndie instance to establish HTTP connections with arbitrary locations.  If ");
        out.write("this field is not specified, no one can use remote archives.</span><br />\n");
        out.write("<em class=\"b_adminField\">Default remote proxy host:</em> <input class=\"b_adminField\" type=\"text\" name=\"proxyhost\" size=\"20\" value=\"");
        out.write(BlogManager.instance().getDefaultProxyHost());
        out.write("\" /><br />\n");
        out.write("<em class=\"b_adminField\">Default remote proxy port:</em> <input class=\"b_adminField\" type=\"text\" name=\"proxyport\" size=\"5\" value=\"");
        out.write(BlogManager.instance().getDefaultProxyPort());
        out.write("\" /><br />\n");
        out.write("<span class=\"b_adminDescr\">This is the default HTTP proxy shown on the remote archive page.</span><br />\n");
        out.write("<hr />\n");
        out.write("<input class=\"b_adminSave\" type=\"submit\" name=\"action\" value=\"Save config\" />\n");

        out.write("</td></tr>\n");
        out.write("</form>\n");
    }
}
