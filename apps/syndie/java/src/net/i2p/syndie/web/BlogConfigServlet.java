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
 * Display our blog config, and let us edit it through several screens
 *
 */
public class BlogConfigServlet extends BaseServlet {
    private static final String ATTR_CONFIG_BEAN = "__blogConfigBean";
    public static final String PARAM_CONFIG_SCREEN = "screen";
    public static final String SCREEN_REFERENCES = "references";
    public static final String SCREEN_IMAGES = "images";
    
    public static BlogConfigBean getConfigBean(HttpServletRequest req, User user) {
        BlogConfigBean bean = (BlogConfigBean)req.getSession().getAttribute(ATTR_CONFIG_BEAN);
        if (bean == null) {
            bean = new BlogConfigBean();
            bean.setUser(user);
            req.getSession().setAttribute(ATTR_CONFIG_BEAN, bean);
        }
        return bean;
    }
    public static BlogConfigBean getConfigBean(HttpServletRequest req) {
        return (BlogConfigBean)req.getSession().getAttribute(ATTR_CONFIG_BEAN);
    }
    
    protected void renderServletDetails(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index, 
                                        int threadOffset, BlogURI visibleEntry, Archive archive) throws IOException {
        if ( (user == null) || (!user.getAuthenticated() && !BlogManager.instance().isSingleUser())) {
            out.write("You must be logged in to edit your profile");
            return;
        }
        
        BlogConfigBean bean = getConfigBean(req, user);
        
        String screen = req.getParameter(PARAM_CONFIG_SCREEN);
        if (screen == null)
            screen = SCREEN_REFERENCES;
        out.write("<tr><td colspan=\"3\">\n");
        showConfigNav(req, out);
        
        if (isAuthed(req)) {
            StringBuffer buf = handleOtherAuthedActions(user, req, bean);
            if (buf != null) out.write(buf.toString());
        } else {
            String contentType = req.getContentType();
            if (!empty(contentType) && (contentType.indexOf("boundary=") != -1)) {
                StringBuffer buf = handlePost(user, req, bean);
                if (buf != null) out.write(buf.toString());
            }
        }
        if (bean.isUpdated())
            showCommitForm(req, out);
        
        if (SCREEN_REFERENCES.equals(screen)) {
            displayReferencesScreen(req, out, user, bean);
        } else if (SCREEN_IMAGES.equals(screen)) {
            displayImagesScreen(req, out, user, bean);
        } else {
            displayUnknownScreen(out, screen);
        }
        out.write("</td></tr>\n");
    }
    private StringBuffer handlePost(User user, HttpServletRequest rawRequest, BlogConfigBean bean) throws IOException {
        StringBuffer rv = new StringBuffer(64);
        MultiPartRequest req = new MultiPartRequest(rawRequest);
        if (authAction(req.getString(PARAM_AUTH_ACTION))) {
            // read in the logo if specified
            String filename = req.getFilename("newLogo");
            if ( (filename != null) && (filename.trim().length() > 0) ) {
                Hashtable params = req.getParams("newLogo");
                String type = "image/png";
                for (Iterator iter = params.keySet().iterator(); iter.hasNext(); ) {
                  String cur = (String)iter.next();
                  if ("content-type".equalsIgnoreCase(cur)) {
                    type = (String)params.get(cur);
                    break;
                  }
                }
                InputStream logoSrc = req.getInputStream("newLogo");
                
                File tmpLogo = File.createTempFile("blogLogo", ".png", BlogManager.instance().getTempDir());
                FileOutputStream out = null;
                try {
                    out = new FileOutputStream(tmpLogo);
                    byte buf[] = new byte[4096];
                    int read = 0;
                    while ( (read = logoSrc.read(buf)) != -1)
                        out.write(buf, 0, read);
                } finally {
                    if (out != null) try { out.close(); } catch (IOException ioe) {}
                }

                long len = tmpLogo.length();
                if (len > BlogInfoData.MAX_LOGO_SIZE) {
                    tmpLogo.delete();
                    rv.append("Proposed logo is too large (" + len + ", max of " + BlogInfoData.MAX_LOGO_SIZE + ")<br />\n");
                } else {
                    bean.setLogo(tmpLogo);
                    rv.append("Logo updated<br />");
                }
            } else {
                // logo not specified
            }
        } else {
            // noop
        }
        return rv;
    }
    
    private void showCommitForm(HttpServletRequest req, PrintWriter out) throws IOException {
        out.write("<form action=\"" + req.getRequestURI() + "\" method=\"GET\">\n");
        writeAuthActionFields(out);
        out.write("<i>Note: Uncommitted changes outstanding</a> <input type=\"submit\" name=\"action\" " +
                  "value=\"Publish blog configuration\" />\n</form>\n");
    }
    
    private void showConfigNav(HttpServletRequest req, PrintWriter out) throws IOException {
        out.write("<span class=\"syndieBlogConfigNav\"><a href=\"" + getScreenURL(req, SCREEN_REFERENCES, false)
                  + "\" title=\"Configure the blog's references\">References</a> "
                  + "<a href=\"" + getScreenURL(req, SCREEN_IMAGES, false)
                  + "\" title=\"Configure the images used on the blog\">Images</a></span><hr />\n");
    }
    
    private String getScreenURL(HttpServletRequest req, String screen, boolean wantAuth) {
        StringBuffer buf = new StringBuffer(128);
        buf.append(req.getRequestURI()).append("?").append(PARAM_CONFIG_SCREEN).append("=");
        buf.append(screen).append("&amp;");
        if (wantAuth)
            buf.append(PARAM_AUTH_ACTION).append('=').append(_authNonce).append("&amp;");
        return buf.toString();
    }
    
    private void displayUnknownScreen(PrintWriter out, String screen) throws IOException {
        out.write("<br /><hr />The screen " + HTMLRenderer.sanitizeString(screen) + " has not yet been implemented");
    }
    private void displayReferencesScreen(HttpServletRequest req, PrintWriter out, User user, BlogConfigBean bean) throws IOException {
        out.write("<form action=\"" + getScreenURL(req, SCREEN_REFERENCES, false) + "\" method=\"POST\">\n");
        writeAuthActionFields(out);
        out.write("<ol class=\"syndieReferenceGroupList\">\n");
        boolean defaultFound = false;
        for (int i = 0; i < bean.getGroupCount(); i++) {
            List group = bean.getGroup(i);
            String groupName = null;
            PetName pn = (PetName)group.get(0);
            if (pn.getGroupCount() <= 0) {
                groupName = ViewBlogServlet.DEFAULT_GROUP_NAME;
                defaultFound = true;
            } else {
                groupName = pn.getGroup(0);
            }
            out.write("<li><b>Group:</b> " + HTMLRenderer.sanitizeString(groupName) + "\n");
            if (i > 0)
                out.write(" <a href=\"" + getScreenURL(req, SCREEN_REFERENCES, true) + "moveFrom=" + i + 
                          "&amp;moveTo=" + (i-1) + "\" title=\"Move higher\">^</a>");
            if (i + 1 < bean.getGroupCount())
                out.write(" <a href=\"" + getScreenURL(req, SCREEN_REFERENCES, true) + "moveFrom=" + i + 
                          "&amp;moveTo=" + (i+1) + "\" title=\"Move lower\">v</a>");
            out.write(" <a href=\"" + getScreenURL(req, SCREEN_REFERENCES, true) + "delete=" + i + "\" title=\"Delete\">X</a>");
            
            out.write("<ol class=\"syndieReferenceGroupElementList\">\n");
            for (int j = 0; j < group.size(); j++) {
                out.write("<li>" + ViewBlogServlet.renderLink(user.getBlog(), (PetName)group.get(j)));
                if (j > 0)
                    out.write(" <a href=\"" + getScreenURL(req, SCREEN_REFERENCES, true) + "moveRefFrom=" + i + "." + j + 
                              "&amp;moveRefTo=" + i + "." + (j-1) + "\" title=\"Move higher\">^</a>");
                if (j + 1 < group.size())
                    out.write(" <a href=\"" + getScreenURL(req, SCREEN_REFERENCES, true) + "moveRefFrom=" + i + "." + j + 
                              "&amp;moveRefTo=" + i + "." + (j+1) + "\" title=\"Move lower\">v</a>");
                out.write(" <a href=\"" + getScreenURL(req, SCREEN_REFERENCES, true) + "delete=" + i + "." + j 
                          + "\" title=\"Delete\">X</a>");
                out.write("</li>\n");
            }
            out.write("</ol><!-- end of the syndieReferenceGroupElementList -->\n");
            out.write("</li>\n");
        }
        out.write("</ol><!-- end of the syndieReferenceGroupList -->\n");
        
        
        out.write("Add a new element: <br />Group: <select name=\"new.group\"><option value=\"\">Select a group...</option>");
        for (int i = 0; i < bean.getGroupCount(); i++) {
            List group = bean.getGroup(i);
            String groupName = null;
            PetName pn = (PetName)group.get(0);
            if (pn.getGroupCount() <= 0)
                groupName = ViewBlogServlet.DEFAULT_GROUP_NAME;
            else
                groupName = pn.getGroup(0);
            if (groupName != null)
                out.write("<option value=\"" + HTMLRenderer.sanitizeTagParam(groupName) + "\">" + 
                          HTMLRenderer.sanitizeString(groupName) + "</option>\n");
        }
        if (!defaultFound)
            out.write("<option value=\"" + ViewBlogServlet.DEFAULT_GROUP_NAME + "\">" 
                      + ViewBlogServlet.DEFAULT_GROUP_NAME + "</option>\n");
        out.write("</select> or <input type=\"text\" size=\"12\" name=\"new.groupOther\" /><br />" +
                  "Type: <select name=\"new.type\"><option value=\"blog\">Syndie blog</option>\n" +
                  "<option value=\"blogpost\">Post within a syndie blog</option>\n" +
                  "<option value=\"blogpostattachment\">Attachment within a syndie blog</option>\n" +
                  "<option value=\"eepsite\">Eepsite</option>\n" +
                  "<option value=\"website\">Website</option>\n</select><br />\n" +
                  "Name: <input type=\"text\" size=\"20\" name=\"new.name\" /><br /> " +
                  "Location: <input type=\"text\" name=\"new.location\" size=\"40\" />\n" +
                  "<ul><li>Blogs should be specified as <code>$base64Key</code></li>\n" +
                  "<li>Blog posts should be specified as <code>$base64Key/$postEntryId</code></li>\n" +
                  "<li>Blog post attachments should be specified as <code>$base64Key/$postEntryId/$attachmentNum</code></li>\n" +
                  "</ul><hr />\n");
        
        out.write("<input type=\"submit\" name=\"action\" value=\"Save changes\">\n");
        out.write("</form>\n");
    }
    
    private void writePetnameDropdown(PrintWriter out, PetNameDB db) throws IOException {
        Set names = db.getNames();
        TreeSet ordered = new TreeSet(names);
        for (Iterator iter = ordered.iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            PetName pn = db.getByName(name);
            String proto = pn.getProtocol();
            if ("syndietag".equals(proto))
                continue;
            out.write("<option value=\"" + HTMLRenderer.sanitizeTagParam(pn.getName()) + "\">");
            if ("syndieblog".equals(proto))
                out.write("Blog: ");
            else if ("syndiearchive".equals(proto))
                out.write("Archive: ");
            else if ("eep".equals(proto))
                out.write("Eepsite: ");
            else
                out.write(HTMLRenderer.sanitizeString(proto) + ": ");
            out.write(HTMLRenderer.sanitizeString(pn.getName()) + "</option>\n");
        }
    }
    
    private void displayImagesScreen(HttpServletRequest req, PrintWriter out, User user, BlogConfigBean bean) throws IOException {
        out.write("<form action=\"" + getScreenURL(req, SCREEN_IMAGES, false) + "\" method=\"POST\" enctype=\"multipart/form-data\">\n");
        writeAuthActionFields(out);

        File logo = bean.getLogo();
        if (logo != null)
            out.write("Blog logo: <img src=\"" + ViewBlogServlet.getLogoURL(user.getBlog()) + "\" alt=\"Your blog's logo\" /><br />\n");
        out.write("New logo: <input type=\"file\" name=\"newLogo\" title=\"PNG or JPG format logo\" /><br />\n");
        out.write("<input type=\"submit\" name=\"action\" value=\"Save changes\">\n");
        out.write("</form>\n");        
    }
    
    protected StringBuffer handleOtherAuthedActions(User user, HttpServletRequest req, BlogConfigBean bean) {
        StringBuffer buf = new StringBuffer();
        req.setAttribute(getClass().getName() + ".output", buf);
        String action = req.getParameter("action");
        if ("Publish blog configuration".equals(action)) {
            if (bean.publishChanges()) {
                buf.append("Changes published<br />\n");
            } else {
                buf.append("Changes could not be published (please check the log)<br />\n");
            }
        } else {
            if ("Save changes".equals(action)) {
                String newGroup = req.getParameter("new.group");
                if ( (newGroup == null) || (newGroup.trim().length() <= 0) )
                    newGroup = req.getParameter("new.groupOther");
                if ( (newGroup != null) && (newGroup.trim().length() > 0) ) {
                    addElement(req, user, newGroup, buf, bean);
                } else {
                }
            } else {
            }

            handleDelete(req, user, bean, buf);
            handleReorderGroup(req, user, bean, buf);
            handleReorderRef(req, user, bean, buf);
        }
        return buf;
    }
    
    private void addElement(HttpServletRequest req, User user, String newGroup, StringBuffer actionOutputHTML, BlogConfigBean bean) {
        String type = req.getParameter("new.type");
        String loc = req.getParameter("new.location");
        String name = req.getParameter("new.name");
        
        if (empty(type) || empty(loc) || empty(name)) return;
        
        PetName pn = null;
        if ("blog".equals(type))
            pn = new PetName(name, "syndie", "syndieblog", loc);
        else if ("blogpost".equals(type))
            pn = new PetName(name, "syndie", "syndieblogpost", loc);
        else if ("blogpostattachment".equals(type))
            pn = new PetName(name, "syndie", "syndieblogattachment", loc);
        else if ("eepsite".equals(type))
            pn = new PetName(name, "i2p", "http", loc);
        else if ("website".equals(type))
            pn = new PetName(name, "web", "http", loc);
        else {
            // unknown type
        }
        
        if (pn != null) {
            if (!ViewBlogServlet.DEFAULT_GROUP_NAME.equals(newGroup))
                pn.addGroup(newGroup);
            bean.add(pn);
            actionOutputHTML.append("Reference '").append(HTMLRenderer.sanitizeString(name));
            actionOutputHTML.append("' for ").append(HTMLRenderer.sanitizeString(loc)).append(" added to ");
            actionOutputHTML.append(HTMLRenderer.sanitizeString(newGroup)).append("<br />\n");
        }
    }

    private void handleDelete(HttpServletRequest req, User user, BlogConfigBean bean, StringBuffer actionOutputHTML) {
        // control parameters: 
        //   delete=$i    removes group # $i
        //   delete=$i.$j removes element $j in group $i
        String del = req.getParameter("delete");
        if (empty(del)) return;
        int split = del.indexOf('.');
        int group = -1;
        int elem = -1;
        if (split <= 0) {
            try { group = Integer.parseInt(del); } catch (NumberFormatException nfe) {}
        } else {
            try { 
                group = Integer.parseInt(del.substring(0, split)); 
                elem = Integer.parseInt(del.substring(split+1)); 
            } catch (NumberFormatException nfe) {
                group = -1;
                elem = -1;
            }
        }
        if ( (elem >= 0) && (group >= 0) ) {
            List l = bean.getGroup(group);
            if (elem < l.size()) {
                PetName pn = (PetName)l.get(elem);
                bean.remove(pn);
                actionOutputHTML.append("Reference '").append(HTMLRenderer.sanitizeString(pn.getName()));
                actionOutputHTML.append("' for ").append(HTMLRenderer.sanitizeString(pn.getLocation()));
                actionOutputHTML.append(" removed<br />\n");
            }
        } else if ( (elem == -1) && (group >= 0) ) {
            List l = bean.getGroup(group);
            for (int i = 0; i < l.size(); i++) {
                PetName pn = (PetName)l.get(i);
                bean.remove(pn);
            }
            actionOutputHTML.append("All references in the selected group were removed<br />\n");
        } else {
            // noop
        }
    }

    private void handleReorderGroup(HttpServletRequest req, User user, BlogConfigBean bean, StringBuffer actionOutputHTML) {
        // control parameters: 
        //   moveFrom=$i & moveTo=$j moves group $i to position $j
        int from = -1;
        int to = -1;
        try { 
            String str = req.getParameter("moveFrom");
            if (str != null)
                from = Integer.parseInt(str);
            str = req.getParameter("moveTo");
            if (str != null)
                to = Integer.parseInt(str);
            
            if ( (from >= 0) && (to >= 0) ) {
                List src = bean.getGroup(from);
                List dest = bean.getGroup(to);
                List orig = new ArrayList(dest);
                dest.clear();
                dest.addAll(src);
                src.clear();
                src.addAll(orig);
                bean.groupsUpdated();
                actionOutputHTML.append("Reference group moved<br />\n");
            }
        } catch (NumberFormatException nfe) {
            // ignore
        }
    }

    private void handleReorderRef(HttpServletRequest req, User user, BlogConfigBean bean, StringBuffer actionOutputHTML) {
        // control parameters: 
        //   moveRefFrom=$i.$j & moveRefTo=$k.$l moves element $j in group $i to position $l in group l
        //   (i == k)
        int from = -1;
        int fromElem = -1;
        int to = -1; // ignored
        int toElem = -1;
        try { 
            String str = req.getParameter("moveRefFrom");
            if (str != null) {
                int split = str.indexOf('.');
                if (split > 0) {
                    try { 
                        from = Integer.parseInt(str.substring(0, split)); 
                        fromElem = Integer.parseInt(str.substring(split+1)); 
                    } catch (NumberFormatException nfe) {
                        from = -1;
                        fromElem = -1;
                    }
                }
            }
            str = req.getParameter("moveRefTo");
            if (str != null) {
                int split = str.indexOf('.');
                if (split > 0) {
                    try { 
                        to = Integer.parseInt(str.substring(0, split)); 
                        toElem = Integer.parseInt(str.substring(split+1)); 
                    } catch (NumberFormatException nfe) {
                        to = -1;
                        toElem = -1;
                    }
                }
            }
            
            if ( (from >= 0) && (fromElem >= 0) && (toElem >= 0) ) {
                List src = bean.getGroup(from);
                PetName pn = (PetName)src.remove(fromElem);
                src.add(toElem, pn);
                bean.groupsUpdated();
                actionOutputHTML.append("Reference element moved<br />\n");
            }
        } catch (NumberFormatException nfe) {
            // ignore
        }
    }
    
    
    protected String getTitle() { return "Syndie :: Configure blog"; }
}
