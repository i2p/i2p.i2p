package net.i2p.syndie.web;

import java.io.*;
import java.util.*;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.*;
import net.i2p.data.*;
import net.i2p.syndie.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.sml.*;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;

/**
 * Render the appropriate posts for the current blog, using any blog info data available    
 *
 */
public class ViewBlogServlet extends BaseServlet {    
    public static final String PARAM_OFFSET = "offset";
    /** $blogHash */
    public static final String PARAM_BLOG = "blog";
    /** $blogHash/$entryId */
    public static final String PARAM_ENTRY = "entry";
    /** tag,tag,tag */
    public static final String PARAM_TAG = "tag";
    /** $blogHash/$entryId/$attachmentId */
    public static final String PARAM_ATTACHMENT = "attachment";
    
    public void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        String attachment = req.getParameter(PARAM_ATTACHMENT);
        if (attachment != null) {
            // if they requested an attachment, serve it up to 'em
            if (renderAttachment(req, resp, attachment))
                return;
        }
        //todo: take care of logo requests, etc
        super.service(req, resp);
    }
    
    protected void render(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index) throws ServletException, IOException {
        Archive archive = BlogManager.instance().getArchive();

        Hash blog = null;
        String name = req.getParameter(PARAM_BLOG);
        if ( (name == null) || (name.trim().length() <= 0) ) {
            blog = user.getBlog();
        } else {
            byte val[] = Base64.decode(name);
            if ( (val != null) && (val.length == Hash.HASH_LENGTH) )
                blog = new Hash(val);
        }
        
        BlogInfo info = null;
        if (blog != null)
            info = archive.getBlogInfo(blog);
        
        int offset = 0;
        String off = req.getParameter(PARAM_OFFSET);
        if (off != null) try { offset = Integer.parseInt(off); } catch (NumberFormatException nfe) {}

        List posts = getPosts(user, archive, info, req, index);
        render(user, req, out, archive, info, posts, offset);
    }
    
    private BlogURI getEntry(HttpServletRequest req) {
        String param = req.getParameter(PARAM_ENTRY);
        if (param != null)
            return new BlogURI("blog://" + param);
        return null;
    }
    
    private List getPosts(User user, Archive archive, BlogInfo info, HttpServletRequest req, ThreadIndex index) {
        List rv = new ArrayList(1);
        if (info == null) return rv;
        
        ArchiveIndex aindex = archive.getIndex();
        
        BlogURI uri = getEntry(req);
        if (uri != null) {
            rv.add(uri);
            return rv;
        }
        
        aindex.selectMatchesOrderByEntryId(rv, info.getKey().calculateHash(), null);

        // lets filter out any posts that are not roots
        for (int i = 0; i < rv.size(); i++) {
            BlogURI curURI = (BlogURI)rv.get(i);
            ThreadNode node = index.getNode(curURI);
            if ( (node != null) && (node.getParent() == null) ) {
                // ok, its a root
                Collection tags = node.getTags();
                if ( (tags != null) && (tags.contains(BlogInfoData.TAG)) ) {
                    // skip this, as its an info post
                    rv.remove(i);
                    i--;
                }
            } else {
                rv.remove(i);
                i--;
            }
        }
        return rv;
    }
    
    private void render(User user, HttpServletRequest req, PrintWriter out, Archive archive, BlogInfo info, List posts, int offset) throws IOException {
        String title = null;
        String desc = null;
        BlogInfoData data = null;
        if (info != null) {
            title = info.getProperty(BlogInfo.NAME);
            desc = info.getProperty(BlogInfo.DESCRIPTION);
            String dataURI = info.getProperty(BlogInfo.SUMMARY_ENTRY_ID);
            if (dataURI != null) {
                EntryContainer entry = archive.getEntry(new BlogURI(dataURI));
                if (entry != null) {
                    data = new BlogInfoData();
                    try {
                        data.load(entry);
                    } catch (IOException ioe) {
                        data = null;
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Error loading the blog info data from " + dataURI, ioe);
                    }
                }
            }
        }
        String pageTitle = "Syndie :: Blogs" + (desc != null ? " :: " + desc : "");
        if (title != null) pageTitle = pageTitle + " (" + title + ")";
        pageTitle = HTMLRenderer.sanitizeString(pageTitle);
        out.write("<html>\n<head>\n<title>" + pageTitle + "</title>\n");
        out.write("<style>");
        renderStyle(out, info, data, req);
        out.write("</style></head>");
        renderHeader(user, req, out, info, data, title, desc);
        renderReferences(out, info, data);
        renderBody(user, out, info, data, posts, offset, archive, req);
        out.write("</body></html>\n");
    }
    private void renderStyle(PrintWriter out, BlogInfo info, BlogInfoData data, HttpServletRequest req) throws IOException {
        // modify it based on data.getStyleOverrides()...
        out.write(CSS);
        Reader css = null;
        try {
            InputStream in = req.getSession().getServletContext().getResourceAsStream("/syndie.css");
            if (in != null) {
                css = new InputStreamReader(in, "UTF-8");
                char buf[] = new char[1024];
                int read = 0;
                while ( (read = css.read(buf)) != -1) 
                    out.write(buf, 0, read);
            }
        } finally {
            if (css != null)
                css.close();
        }
        String content = FileUtil.readTextFile("./docs/syndie_standard.css", -1, true); 
        if (content != null) out.write(content);
    }
    
    private void renderHeader(User user, HttpServletRequest req, PrintWriter out, BlogInfo info, BlogInfoData data, String title, String desc) throws IOException {
        out.write("<body class=\"syndieBlog\">\n<span style=\"display: none\">" +
                  "<a href=\"#content\" title=\"Skip to the blog content\">Content</a></span>\n");
        renderNavBar(user, req, out);
        out.write("<div class=\"syndieBlogHeader\">\n");
        if (data != null) {
            if (data.isLogoSpecified()) {
                out.write("<img src=\"logo.png\" alt=\"\" />\n");
            }
        }
        String name = desc;
        if ( (name == null) || (name.trim().length() <= 0) )
            name = title;
        if ( ( (name == null) || (name.trim().length() <= 0) ) && (info != null) )
            name = info.getKey().calculateHash().toBase64();
        if (name != null) {
            String url = "blog.jsp?" + (info != null ? PARAM_BLOG + "=" + info.getKey().calculateHash().toBase64() : "");
            out.write("<b><a href=\"" + url + "\" title=\"Go to the blog root\">" 
                      + HTMLRenderer.sanitizeString(name) + "</a></b>");
        }
        out.write("</div>\n");
    }
    
    private static final String DEFAULT_GROUP_NAME = "References";
    private void renderReferences(PrintWriter out, BlogInfo info, BlogInfoData data) throws IOException {
        out.write("<div class=\"syndieBlogLinks\">\n");
        if (data != null) {
            for (int i = 0; i < data.getReferenceGroupCount(); i++) {
                List group = data.getReferenceGroup(i);
                if (group.size() <= 0) continue;
                PetName pn = (PetName)group.get(0);
                String name = null;
                if (pn.getGroupCount() <= 0)
                    name = DEFAULT_GROUP_NAME;
                else
                    name = HTMLRenderer.sanitizeString(pn.getGroup(0));
                out.write("<!-- group " + name + " -->\n");
                out.write("<div class=\"syndieBlogLinkGroup\">\n");
                out.write("<span class=\"syndieBlogLinkGroupName\">" + name + "</span>\n");
                out.write("<ul>\n");
                for (int j = 0; j < group.size(); j++) {
                    pn = (PetName)group.get(j);
                    out.write("<li>" + renderLink(pn) + "</li>\n");
                }
                out.write("</ul>\n</div>\n<!-- end " + name + " -->\n");
            }
        }
        out.write("<div class=\"syndieBlogLinkGroup\">\n");
        out.write("<span class=\"syndieBlogLinkGroupName\">Custom links</span>\n");
        out.write("<ul><li><a href=\"\">are not yet implemented</a></li><li><a href=\"\">but are coming soon</a></li></ul>\n");
        out.write("</div><!-- end fake group -->");
        out.write("<div class=\"syndieBlogMeta\">");
        out.write("Secured by <a href=\"http://syndie.i2p.net/\">Syndie</a>");
        out.write("</div>\n");
        out.write("</div><!-- end syndieBlogLinks -->\n\n");
    }
    
    private String renderLink(PetName pn) {
        return "<a href=\"\" title=\"go somewhere\">" + HTMLRenderer.sanitizeString(pn.getName()) + "</a>";
    }

    private static final int POSTS_PER_PAGE = 5;
    private void renderBody(User user, PrintWriter out, BlogInfo info, BlogInfoData data, List posts, int offset, Archive archive, HttpServletRequest req) throws IOException {
        out.write("<div class=\"syndieBlogBody\">\n<span style=\"display: none\" id=\"content\"></span>\n\n");
        if (info == null) {
            out.write("No blog specified\n");
            return;
        }
        
        BlogRenderer renderer = new BlogRenderer(_context, info, data);
        
        if ( (posts.size() == 1) && (req.getParameter(PARAM_ENTRY) != null) ) {
            BlogURI uri = (BlogURI)posts.get(0);
            EntryContainer entry = archive.getEntry(uri);
            renderer.render(user, archive, entry, out, false, true);
            renderComments(user, out, info, data, entry, archive, renderer);
        } else {
            for (int i = offset; i < posts.size() && i < offset + POSTS_PER_PAGE; i++) {
                BlogURI uri = (BlogURI)posts.get(i);
                EntryContainer entry = archive.getEntry(uri);
                renderer.render(user, archive, entry, out, true, true);
            }

            renderNav(out, info, data, posts, offset, archive, req);
        }

        out.write("</div><!-- end syndieBlogBody -->\n");
    }
    
    private void renderComments(User user, PrintWriter out, BlogInfo info, BlogInfoData data, EntryContainer entry, 
                                Archive archive, BlogRenderer renderer) throws IOException {
        ArchiveIndex index = archive.getIndex();
        out.write("<div class=\"syndieBlogComments\">\n");
        renderComments(user, out, entry.getURI(), archive, index, renderer);
        out.write("</div>\n");
    }
    private void renderComments(User user, PrintWriter out, BlogURI parentURI, Archive archive, ArchiveIndex index, BlogRenderer renderer) throws IOException {
        List replies = index.getReplies(parentURI);
        if (replies.size() > 0) {
            out.write("<ul>\n");
            for (int i = 0; i < replies.size(); i++) {
                BlogURI uri = (BlogURI)replies.get(i);
                out.write("<li>");
                if (!shouldIgnore(user, uri)) {
                    EntryContainer cur = archive.getEntry(uri);
                    renderer.render(user, archive, cur, out, false, true);
                    // recurse
                    renderComments(user, out, uri, archive, index, renderer);
                }
                out.write("</li>\n");
            }
            out.write("</ul>\n");
        }
    }
    
    private boolean shouldIgnore(User user, BlogURI uri) {
        PetName pn = user.getPetNameDB().getByLocation(uri.getKeyHash().toBase64());
        return ( (pn != null) && pn.isMember(FilteredThreadIndex.GROUP_IGNORE));
    }
    
    private void renderNav(PrintWriter out, BlogInfo info, BlogInfoData data, List posts, int offset, Archive archive, HttpServletRequest req) throws IOException {
        out.write("<div class=\"syndieBlogNav\"><hr style=\"display: none\" />\n");
        String uri = req.getRequestURI() + "?";
        if (info != null)
            uri = uri + PARAM_BLOG + "=" + info.getKey().calculateHash().toBase64() + "&amp;";
        if (offset + POSTS_PER_PAGE >= posts.size())
            out.write(POSTS_PER_PAGE + " more older entries");
        else
            out.write("<a href=\"" + uri + "offset=" + (offset+POSTS_PER_PAGE) + "\">" 
                      + POSTS_PER_PAGE + " older entries</a>");
        out.write(" | ");
        if (offset <= 0)
            out.write(POSTS_PER_PAGE + " more recent entries");
        else
            out.write("<a href=\"" + uri + "offset=" + 
                      (offset >= POSTS_PER_PAGE ? offset-POSTS_PER_PAGE : 0) 
                      + "\">" + POSTS_PER_PAGE + " more recent entries</a>");
        
        out.write("</div><!-- end syndieBlogNav -->\n");
    }

    /** 
     * render the attachment to the browser, using the appropriate mime types, etc
     * @param attachment formatted as $blogHash/$entryId/$attachmentId
     * @return true if rendered 
     */
    private boolean renderAttachment(HttpServletRequest req, HttpServletResponse resp, String attachment) throws ServletException, IOException {
        int split = attachment.lastIndexOf('/');
        if (split <= 0)
            return false;
        BlogURI uri = new BlogURI("blog://" + attachment.substring(0, split));
        try { 
            int attachmentId = Integer.parseInt(attachment.substring(split+1)); 
            if (attachmentId < 0) return false;
            EntryContainer entry = BlogManager.instance().getArchive().getEntry(uri);
            if (entry == null) {
                System.out.println("Could not render the attachment [" + uri + "] / " + attachmentId);
                return false;
            }
            Attachment attachments[] = entry.getAttachments();
            if (attachmentId >= attachments.length) {
                System.out.println("Out of range attachment on " + uri + ": " + attachmentId);
                return false;
            }
            
            resp.setContentType(ArchiveViewerBean.getAttachmentContentType(attachments[attachmentId]));
            boolean inline = ArchiveViewerBean.getAttachmentShouldShowInline(attachments[attachmentId]);
            String filename = ArchiveViewerBean.getAttachmentFilename(attachments[attachmentId]);
            if (inline)
                resp.setHeader("Content-Disposition", "inline; filename=\"" + filename + "\"");
            else
                resp.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            int len = ArchiveViewerBean.getAttachmentContentLength(attachments[attachmentId]);
            if (len >= 0)
                resp.setContentLength(len);
            ArchiveViewerBean.renderAttachment(attachments[attachmentId], resp.getOutputStream());
            return true;
        } catch (NumberFormatException nfe) {}
        return false;
    }
    
    private static final String CSS = 
"<style>\n" +
"body {\n" +
"	margin: 0px;\n" +
"	padding: 0px;\n" +
"	font-family: Arial, Helvetica, sans-serif;\n" +
"}\n" +
".syndieBlog {\n" +
"	font-size: 100%;\n" +
"	margin: 0px;\n" +
"	border: 0px;\n" +
"	padding: 0px;\n" +
"	border-width: 0px;\n" +
"	border-spacing: 0px;\n" +
"}\n" +
".syndieBlogTopNav {\n" +
"	width: 100%;\n" +
"	height: 20px;\n" +
"	background-color: #BBBBBB;\n" +
"}\n" +
".syndieBlogTopNavUser {\n" +
"	text-align: left;\n" +
"	float: left;\n" +
"	display: inline;\n" +
"}\n" +
".syndieBlogTopNavAdmin {\n" +
"	text-align: left;\n" +
"	float: right;\n" +
"	display: inline;\n" +
"}\n" +
".syndieBlogHeader {\n" +
"	width: 100%;\n" +
"	height: 50px;\n" +
"	font-size: 120%;\n" +
"	background-color: black;\n" +
"	color: white;\n" +
"}\n" +
".syndieBlogLinks {\n" +
"	width: 200px;\n" +
"}\n" +
".syndieBlogLinkGroup {\n" +
"	text-align: left;\n" +
"	font-size: 80%;\n" +
"	background-color: #DDD;\n" +
"	border: solid;\n" +
"	//border-width: 5px 5px 0px 5px;\n" +
"	//border-color: #FFFFFF;\n" +
"	border-width: 1px 1px 1px 1px;\n" +
"	border-color: #000;\n" +
"	margin-top: 5px;\n" +
"	margin-right: 5px;\n" +
"}\n" +
".syndieBlogLinkGroup ul {\n" +
"	list-style: none;\n" +
"	margin-left: 0;\n" +
"	margin-top: 0;\n" +
"	margin-bottom: 0;\n" +
"	padding-left: 0;\n" +
"}\n" +
".syndieBlogLinkGroup li {\n" +
"	margin: 0;\n" +
"}\n" +
".syndieBlogLinkGroup li a {\n" +
"	display: block;\n" +
"	width: 100%;\n" +
"}\n" +
".syndieBlogLinkGroupName {\n" +
"	font-size: 80%;\n" +
"	font-weight: bold;\n" +
"}\n" +
".syndieBlogMeta {\n" +
"	text-align: left;\n" +
"	font-size: 80%;\n" +
"	background-color: #DDD;\n" +
"	border: solid;\n" +
"	border-width: 1px 1px 1px 1px;\n" +
"	border-color: #000;\n" +
"                   width: 90%;\n" +
"	margin-top: 5px;\n" +
"	margin-right: 5px;\n" +
"}\n" +
".syndieBlogBody {\n" +
"	position: absolute;\n" +
"	top: 70px;\n" +
"	left: 200px;\n" +
"	float: left;\n" +
"}\n" +
".syndieBlogPost {\n" +
"	border: solid;\n" +
"	border-width: 1px 1px 1px 1px;\n" +
"	border-color: #000;\n" +
"	margin-top: 5px;\n" +
"	width: 100%;\n" +
"}\n" +
".syndieBlogPostHeader {\n" +
"	background-color: #BBB;\n" +
"}\n" +
".syndieBlogPostSubject {\n" +
"	text-align: left;\n" +
"}\n" +
".syndieBlogPostFrom {\n" +
"	text-align: right;\n" +
"}\n" +
".syndieBlogPostSummary {\n" +
"	background-color: #FFFFFF;\n" +
"}\n" +
".syndieBlogPostDetails {\n" +
"	background-color: #DDD;\n" +
"}\n" +
".syndieBlogNav {\n" +
"	text-align: center;\n" +
"}\n" +
".syndieBlogComments {\n" +
"                   border: none;\n" +
"                   margin-top: 5px;\n" +
"                   margin-left: 0px;\n" +
"                   float: left;\n" +
"}\n" +
".syndieBlogComments ul {\n" +
"                   list-style: none;\n" +
"                   margin-left: 10;\n" +
"                   padding-left: 0;\n" +
"}\n";

    protected String getTitle() { return "unused"; }
    protected void renderServletDetails(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index, 
                                        int threadOffset, BlogURI visibleEntry, Archive archive) throws IOException {
        throw new RuntimeException("unused");
    }
}
