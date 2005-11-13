package net.i2p.syndie.web;

import java.io.*;
import java.util.*;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletException;

import org.mortbay.servlet.MultiPartRequest;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.*;
import net.i2p.data.*;
import net.i2p.syndie.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.sml.*;

/**
 * Post and preview form
 *
 */
public class PostServlet extends BaseServlet {
    public static final String PARAM_ACTION = "action";
    public static final String ACTION_CONFIRM = "confirm";
    
    public static final String PARAM_SUBJECT = "entrysubject";
    public static final String PARAM_TAGS = "entrytags";
    public static final String PARAM_INCLUDENAMES = "includenames";
    public static final String PARAM_TEXT = "entrytext";
    public static final String PARAM_HEADERS = "entryheaders";
    
    public static final String PARAM_PARENT = "parentURI";
    public static final String PARAM_IN_NEW_THREAD = "replyInNewThread";
    public static final String PARAM_REFUSE_REPLIES = "refuseReplies";
    
    public static final String PARAM_REMOTE_ARCHIVE = "archive";
    
    private static final String ATTR_POST_BEAN = "post";
    
    protected void renderServletDetails(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index, 
                                        int threadOffset, BlogURI visibleEntry, Archive archive) throws IOException {
        if (!user.getAuthenticated()) {
            out.write("<tr><td colspan=\"3\">You must be logged in to post</td></tr>\n");
        } else {
            PostBean post = getPostBean(user, req);
            String action = req.getParameter(PARAM_ACTION);
            if (!empty(action) && ACTION_CONFIRM.equals(action)) {
                postEntry(user, req, archive, post, out);
            } else {
                String contentType = req.getContentType();
                if (!empty(contentType) && (contentType.indexOf("boundary=") != -1)) {
                    previewPostedData(user, req, archive, contentType, post, out);
                } else {
                    displayNewForm(user, req, post, out);
                }
            }
        }
    }
    
    private void previewPostedData(User user, HttpServletRequest rawRequest, Archive archive, String contentType, PostBean post, PrintWriter out) throws IOException {
        MultiPartRequest req = new MultiPartRequest(rawRequest);
        
        if (!authAction(req.getString(PARAM_AUTH_ACTION))) {
            out.write("<tr><td colspan=\"3\"><span class=\"b_postMsgErro\">Invalid form submission... stale data?</span></td></tr>");
            return;
        }
        
        // not confirmed but they posted stuff... gobble up what they give
        // and display it as a prview (then we show the confirm form
        
        out.write("<tr><td colspan=\"3\">");
        
        post.reinitialize();
        post.setUser(user);
        
        boolean inNewThread = getInNewThread(req.getString(PARAM_IN_NEW_THREAD));
        boolean refuseReplies = getRefuseReplies(req.getString(PARAM_REFUSE_REPLIES));
        
        String entrySubject = req.getString(PARAM_SUBJECT);
        String entryTags = req.getString(PARAM_TAGS);
        String entryText = req.getString(PARAM_TEXT);
        String entryHeaders = req.getString(PARAM_HEADERS);
        String style = ""; //req.getString("style");
        if ( (style != null) && (style.trim().length() > 0) ) {
          if (entryHeaders == null) entryHeaders = HTMLRenderer.HEADER_STYLE + ": " + style;
          else entryHeaders = entryHeaders + '\n' + HTMLRenderer.HEADER_STYLE + ": " + style;
        }
        String replyTo = req.getString(PARAM_PARENT);
        if ( (replyTo != null) && (replyTo.trim().length() > 0) ) {
          byte r[] = Base64.decode(replyTo);
          if (r != null) {
            if (entryHeaders == null) entryHeaders = HTMLRenderer.HEADER_IN_REPLY_TO + ": entry://" + new String(r, "UTF-8");
            else entryHeaders = entryHeaders + '\n' + HTMLRenderer.HEADER_IN_REPLY_TO + ": entry://" + new String(r, "UTF-8");
          } else {
            replyTo = null;
          }
        }
        
        if ( (entryHeaders == null) || (entryHeaders.trim().length() <= 0) )
            entryHeaders = ThreadedHTMLRenderer.HEADER_FORCE_NEW_THREAD + ": " + inNewThread + '\n' +
                           ThreadedHTMLRenderer.HEADER_REFUSE_REPLIES + ": " + refuseReplies;
        else
            entryHeaders = entryHeaders.trim() + '\n' +
                           ThreadedHTMLRenderer.HEADER_FORCE_NEW_THREAD + ": " + inNewThread + '\n' +
                           ThreadedHTMLRenderer.HEADER_REFUSE_REPLIES + ": " + refuseReplies;
        
        String includeNames = req.getString(PARAM_INCLUDENAMES);
        if ( (includeNames != null) && (includeNames.trim().length() > 0) ) {
          PetNameDB db = user.getPetNameDB();
          if (entryHeaders == null) entryHeaders = "";
          for (Iterator iter = db.getNames().iterator(); iter.hasNext(); ) {
            PetName pn = db.getByName((String)iter.next());
            if ( (pn != null) && (pn.getIsPublic()) ) {
              entryHeaders = entryHeaders.trim() + '\n' + HTMLRenderer.HEADER_PETNAME + ": " + 
                             pn.getName() + "\t" + pn.getNetwork() + "\t" + pn.getProtocol() + "\t" + pn.getLocation();
            }
          }
        }
        
        post.setSubject(entrySubject);
        post.setTags(entryTags);
        post.setText(entryText);
        post.setHeaders(entryHeaders);

        for (int i = 0; i < 32; i++) {
          String filename = req.getFilename("entryfile" + i);
          if ( (filename != null) && (filename.trim().length() > 0) ) {
            Hashtable params = req.getParams("entryfile" + i);
            String type = "application/octet-stream";
            for (Iterator iter = params.keySet().iterator(); iter.hasNext(); ) {
              String cur = (String)iter.next();
              if ("content-type".equalsIgnoreCase(cur)) {
                type = (String)params.get(cur);
                break;
              }
            }
            post.addAttachment(filename.trim(), req.getInputStream("entryfile" + i), type);
          }
        }

        post.renderPreview(out);
        out.write("<hr /><span class=\"b_postConfirm\"><form action=\"" + getPostURI() + "\" method=\"POST\">\n");
        writeAuthActionFields(out);
        out.write("Please confirm that the above is ok");
        if (BlogManager.instance().authorizeRemote(user)) { 
            out.write(", and select what additional archives you want the post transmitted to.");
            out.write("Otherwise, just hit your browser's back arrow and make changes.\n");
            out.write("<select class=\"b_postConfirm\" name=\"" + PARAM_REMOTE_ARCHIVE + "\">\n");
            out.write("<option name=\"\">-None-</option>\n");
            PetNameDB db = user.getPetNameDB();
            TreeSet names = new TreeSet();
            for (Iterator iter = db.getNames().iterator(); iter.hasNext(); ) {
              String name = (String)iter.next();
              PetName pn = db.getByName(name);
              if ("syndiearchive".equals(pn.getProtocol()))
                names.add(pn.getName());
            }
            for (Iterator iter = names.iterator(); iter.hasNext(); ) {
              String name = (String)iter.next();
              out.write("<option value=\"" + HTMLRenderer.sanitizeTagParam(name) + "\">"
                        + HTMLRenderer.sanitizeString(name) + "</option>\n");
            }
            
            out.write("</select><br />\n");
        }
        out.write("</span><input class=\"b_postConfirm\" type=\"submit\" name=\"" + PARAM_ACTION 
                  + "\" value=\"" + ACTION_CONFIRM + "\" />\n");
        
        out.write("</td></tr>\n");
    }
    
    private void postEntry(User user, HttpServletRequest req, Archive archive, PostBean post, PrintWriter out) throws IOException {
        if (!authAction(req)) {
            out.write("<tr><td colspan=\"3\"><span class=\"b_postMsgErro\">Invalid form submission... stale data?</span></td></tr>");
            return;
        }
        String remArchive = req.getParameter(PARAM_REMOTE_ARCHIVE);
        post.setArchive(remArchive);
        BlogURI uri = post.postEntry(); 
        if (uri != null) {
            out.write("<tr><td colspan=\"3\"><span class=\"b_postMsgOk\">Entry <a class=\"b_postOkLink\" href=\"threads.jsp?regenerateIndex=true&post=" +
                      uri.getKeyHash().toBase64() + "/" + uri.getEntryId() + "\">posted</a>!</span></td></tr>");
        } else {
            out.write("<tr><td colspan=\"3\"><span class=\"b_postMsgErro\">There was an unknown error posting the entry...</span></td></tr>");
        }
    }
    
    private void displayNewForm(User user, HttpServletRequest req, PostBean post, PrintWriter out) throws IOException {
        // logged in and not confirmed because they didn't send us anything!  
        // give 'em a new form
        
        post.reinitialize();
        post.setUser(user);
        
        out.write("<form action=\"" + getPostURI() + "\" method=\"POST\" enctype=\"multipart/form-data\">\n");
        writeAuthActionFields(out);
        out.write("<tr><td colspan=\"3\">\n");
        out.write("<span class=\"b_postField\">Post subject:</span> ");
        out.write("<input type=\"text\" class=\"b_postSubject\" size=\"80\" name=\"" + PARAM_SUBJECT 
                  + "\" value=\"" + getParam(req,PARAM_SUBJECT) + "\" /><br />\n");
        out.write("<span class=\"b_postField\">Post tags:</span> ");
        out.write("<input type=\"text\" class=\"b_postTags\" size=\"20\" name=\"" + PARAM_TAGS 
                  + "\" value=\"" + getParam(req, PARAM_TAGS) + "\" /><br />\n");
        out.write("<span class=\"b_postField\">Include public names?</span> ");
        out.write("<input class=\"b_postNames\" type=\"checkbox\" name=\"" + PARAM_INCLUDENAMES 
                  + "\" value=\"true\" /><br />\n");
        out.write("<span class=\"b_postField\">Post content (in raw <a href=\"smlref.jsp\" target=\"_blank\">SML</a>, no headers):</span><br />\n");
        out.write("<textarea class=\"b_postText\" rows=\"6\" cols=\"80\" name=\"" + PARAM_TEXT + "\">" + getParam(req, PARAM_TEXT) + "</textarea><br />\n");
        out.write("<span class=\"b_postField\">SML post headers:</span><br />\n");
        out.write("<textarea class=\"b_postHeaders\" rows=\"3\" cols=\"80\" name=\"" + PARAM_HEADERS + "\">" + getParam(req, PARAM_HEADERS) + "</textarea><br />\n");
        
        
        String parentURI = req.getParameter(PARAM_PARENT);
        if ( (parentURI != null) && (parentURI.trim().length() > 0) )
            out.write("<input type=\"hidden\" name=\"" + PARAM_PARENT + "\" value=\"" + parentURI + "\" />\n");

        out.write(" Tags: <input type=\"text\" size=\"10\" name=\"" + PARAM_TAGS + "\" value=\"" + getParam(req, PARAM_TAGS) + "\" />\n");
        
        boolean inNewThread = getInNewThread(req);
        boolean refuseReplies = getRefuseReplies(req);

        out.write(" in a new thread? <input type=\"checkbox\" value=\"true\" name=\"" + PARAM_IN_NEW_THREAD + 
                  (inNewThread ? "\" checked=\"true\" " : "\" " ) + " />\n");
        out.write(" refuse replies? <input type=\"checkbox\" value=\"true\" name=\"" + PARAM_REFUSE_REPLIES + 
                  (refuseReplies ? "\" checked=\"true\" " : "\" " ) + " />\n");
        
        out.write(ATTACHMENT_FIELDS);

        out.write("<hr />\n");
        out.write("<input class=\"b_postPreview\" type=\"submit\" name=\"Post\" value=\"Preview...\" /> ");
        out.write("<input class=\"b_postReset\" type=\"reset\" value=\"Cancel\" />\n");
        
        if (parentURI != null) {
            out.write("<hr /><span id=\"parentText\" class=\"b_postParent\">");
            String decoded = DataHelper.getUTF8(Base64.decode(parentURI));
            post.renderReplyPreview(out, "entry://" + decoded);
            out.write("</span><hr/>\n");
        } 
        
        out.write("</td></tr>\n");
        out.write("</form>\n");
    }
    
    private boolean getInNewThread(HttpServletRequest req) {
        return getInNewThread(req.getParameter(PARAM_IN_NEW_THREAD));
    }
    private boolean getInNewThread(String val) {
        boolean rv = false;
        String inNewThread = val;
        if ( (inNewThread != null) && (Boolean.valueOf(inNewThread).booleanValue()) )
            rv = true;
        return rv;
    }
    private boolean getRefuseReplies(HttpServletRequest req) {
        return getRefuseReplies(req.getParameter(PARAM_REFUSE_REPLIES));
    }
    private boolean getRefuseReplies(String val) {
        boolean rv = false;
        String refuseReplies = val;
        if ( (refuseReplies != null) && (Boolean.valueOf(refuseReplies).booleanValue()) )
            rv = true;
        return rv;
    }
    
    private PostBean getPostBean(User user, HttpServletRequest req) {
        PostBean bean = (PostBean)req.getSession().getAttribute(ATTR_POST_BEAN);
        if (bean == null) {
            bean = new PostBean();
            req.getSession().setAttribute(ATTR_POST_BEAN, bean);
        }
        return bean;
    }
    
    private String getParam(HttpServletRequest req, String param) {
        String val = req.getParameter(param);
        if (val == null) val = "";
        return val;
    }
    
    private static final String ATTACHMENT_FIELDS = ""
        + "<span class=\"b_postField\">Attachment 0:</span> <input class=\"b_postField\" type=\"file\" name=\"entryfile0\" /><br />"
        + "<span class=\"b_postField\">Attachment 1:</span> <input class=\"b_postField\" type=\"file\" name=\"entryfile1\" /><br />"
        + "<span class=\"b_postField\">Attachment 2:</span> <input class=\"b_postField\" type=\"file\" name=\"entryfile2\" /><br />"
        + "<span class=\"b_postField\">Attachment 3:</span> <input class=\"b_postField\" type=\"file\" name=\"entryfile3\" /><br />\n";

    protected String getTitle() { return "Syndie :: Post new content"; }
}
