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
    /** image within the BlogInfoData to load (e.g. logo.png, icon_$tagHash.png, etc) */
    public static final String PARAM_IMAGE = "image";
    
    public void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        String attachment = req.getParameter(PARAM_ATTACHMENT);
        if (attachment != null) {
            // if they requested an attachment, serve it up to 'em
            if (renderAttachment(req, resp, attachment))
                return;
        }
        String img = req.getParameter(PARAM_IMAGE);
        if (img != null) {
            boolean rendered = renderUpdatedImage(img, req, resp);
            if (!rendered)
                rendered = renderPublishedImage(img, req, resp);
            if (!rendered)
                rendered = renderDefaultImage(img, req, resp);
            if (rendered) return;
        }
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
    
        String entrySelected = req.getParameter(PARAM_ENTRY);
        if (entrySelected != null) {
            // $blogKey/$entryId
            BlogURI uri = null;
            if (entrySelected.startsWith("blog://"))
                uri = new BlogURI(entrySelected);
            else
                uri = new BlogURI("blog://" + entrySelected.trim());
            if (uri.getEntryId() >= 0) {
                rv.add(uri);
                return rv;
            }
        }
        
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
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.write("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n");
        out.write("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"en\">\n<head>\n<title>" + pageTitle + "</title>\n");
        out.write("<style>");
        renderStyle(out, info, data, req);
        out.write("</style></head>");
        renderHeader(user, req, out, info, data, title, desc);
        renderReferences(user, out, info, data, req, posts);
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
    
    public static String getLogoURL(Hash blog) {
        return "blog.jsp?" + PARAM_BLOG + "=" + blog.toBase64() + "&amp;" 
               + PARAM_IMAGE + "=" + BlogInfoData.ATTACHMENT_LOGO;
    }
    
    private void renderHeader(User user, HttpServletRequest req, PrintWriter out, BlogInfo info, BlogInfoData data, String title, String desc) throws IOException {
        out.write("<body class=\"syndieBlog\">\n<span style=\"display: none\">" +
                  "<a href=\"#content\" title=\"Skip to the blog content\">Content</a></span>\n");
        renderNavBar(user, req, out);
        out.write("<div class=\"syndieBlogHeader\">\n");
        out.write("<img class=\"syndieBlogLogo\" src=\"" + getLogoURL(info.getKey().calculateHash()) + "\" alt=\"\" />\n");
        String name = desc;
        if ( (name == null) || (name.trim().length() <= 0) )
            name = title;
        if ( ( (name == null) || (name.trim().length() <= 0) ) && (info != null) )
            name = info.getKey().calculateHash().toBase64();
        if (name != null) {
            String url = "blog.jsp?" + (info != null ? PARAM_BLOG + "=" + info.getKey().calculateHash().toBase64() : "");
            out.write("<b><a href=\"" + url + "\" title=\"Go to the blog root\">" 
                      + HTMLRenderer.sanitizeString(name) + "</a></b>");
            out.write("<br /><a href=\"profile.jsp?" + ThreadedHTMLRenderer.PARAM_AUTHOR + "=" + info.getKey().calculateHash().toBase64() 
                      + "\" title=\"View their profile\">profile</a> <a href=\"threads.jsp?" + ThreadedHTMLRenderer.PARAM_AUTHOR + "=" 
                      + info.getKey().calculateHash().toBase64() + "\" title=\"View threads they have participated in\">threads</a>");
        }
        out.write("</div>\n");
    }
    
    public static final String DEFAULT_GROUP_NAME = "References";
    private void renderReferences(User user, PrintWriter out, BlogInfo info, BlogInfoData data, HttpServletRequest req, List posts) throws IOException {
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
                    out.write("<li>" + renderLink(info.getKey().calculateHash(), pn) + "</li>\n");
                }
                out.write("</ul>\n</div>\n<!-- end " + name + " -->\n");
            }
        }
        //out.write("<div class=\"syndieBlogLinkGroup\">\n");
        //out.write("<span class=\"syndieBlogLinkGroupName\">Custom links</span>\n");
        //out.write("<ul><li><a href=\"\">are not yet implemented</a></li><li><a href=\"\">but are coming soon</a></li></ul>\n");
        //out.write("</div><!-- end fake group -->");
        
        renderPostReferences(user, req, out, posts);
        
        out.write("<div class=\"syndieBlogMeta\">");
        out.write("Secured by <a href=\"http://syndie.i2p.net/\">Syndie</a>");
        out.write("</div>\n");
        out.write("</div><!-- end syndieBlogLinks -->\n\n");
    }
    
    private void renderPostReferences(User user, HttpServletRequest req, PrintWriter out, List posts) throws IOException {
        if (!empty(req, PARAM_ENTRY) && (posts.size() == 1)) {
            BlogURI uri = (BlogURI)posts.get(0);
            Archive archive = BlogManager.instance().getArchive();
            EntryContainer entry = archive.getEntry(uri);
            if (entry != null) {
                out.write("<div class=\"syndieBlogPostInfo\"><!-- foo -->\n");

                BlogPostInfoRenderer renderer = new BlogPostInfoRenderer(_context);
                renderer.render(user, archive, entry, out);
                
                out.write("</div><!-- end syndieBlogPostInfo -->\n");   
            }
        }
    }
    
    /** generate a link for the given petname within the scope of the given blog */
    public static String renderLink(Hash blogFrom, PetName pn) {
        StringBuffer buf = new StringBuffer(64);
        String type = pn.getProtocol();
        if ("syndieblog".equals(type)) {
            String loc = pn.getLocation();
            if (loc != null) {
                buf.append("<a href=\"blog.jsp?").append(PARAM_BLOG).append("=");
                buf.append(HTMLRenderer.sanitizeTagParam(pn.getLocation()));
                buf.append("\" title=\"View ").append(HTMLRenderer.sanitizeTagParam(pn.getName())).append("\">");
            }
            buf.append(HTMLRenderer.sanitizeString(pn.getName()));
            if (loc != null) {
                buf.append("</a>");
                //buf.append(" <a href=\"").append(HTMLRenderer.getBookmarkURL(pn.getName(), pn.getLocation(), "syndie", "syndieblog"));
                //buf.append("\" title=\"Bookmark ").append(HTMLRenderer.sanitizeTagParam(pn.getName())).append("\"><image src=\"images/addToFavorites.png\" alt=\"\" /></a>\n");
            }
        } else if ("syndieblogpost".equals(type)) {
            String loc = pn.getLocation();
            if (loc != null) {
                buf.append("<a href=\"blog.jsp?").append(PARAM_BLOG).append("=");
                buf.append(blogFrom.toBase64()).append("&amp;");
                buf.append(PARAM_ENTRY).append("=").append(HTMLRenderer.sanitizeTagParam(pn.getLocation()));
                buf.append("\" title=\"View the specified post\">");
            }
            buf.append(HTMLRenderer.sanitizeString(pn.getName()));
            if (loc != null) {
                buf.append("</a>");
            }
        } else if ("syndieblogattachment".equals(type)) {
            String loc = pn.getLocation();
            if (loc != null) {
                int split = loc.lastIndexOf('/');
                try {
                    int attachmentId = -1;
                    if (split > 0)
                        attachmentId = Integer.parseInt(loc.substring(split+1));
                    
                    if (attachmentId < 0) {
                        loc = null;
                    } else {
                        BlogURI post = null;
                        if (loc.startsWith("blog://"))
                            post = new BlogURI(loc.substring(0, split));
                        else
                            post = new BlogURI("blog://" + loc.substring(0, split));

                        EntryContainer entry = BlogManager.instance().getArchive().getEntry(post);
                        if (entry != null) {
                            Attachment attachments[] = entry.getAttachments();
                            if (attachmentId < attachments.length) {
                                buf.append("<a href=\"blog.jsp?").append(PARAM_BLOG).append("=");
                                buf.append(blogFrom.toBase64()).append("&amp;");
                                buf.append(PARAM_ATTACHMENT).append("=").append(HTMLRenderer.sanitizeTagParam(loc));
                                buf.append("\" title=\"");
                                buf.append("'");
                                buf.append(HTMLRenderer.sanitizeTagParam(attachments[attachmentId].getName()));
                                buf.append("', ");
                                buf.append(attachments[attachmentId].getDataLength()/1024).append("KB, ");
                                buf.append("of type ").append(HTMLRenderer.sanitizeTagParam(attachments[attachmentId].getMimeType()));
                                buf.append("\">");
                                buf.append(HTMLRenderer.sanitizeString(pn.getName()));
                                buf.append("</a>");
                            } else {
                                loc = null;
                            }
                        } else {
                            loc = null;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    loc = null;
                }
            }
            if (loc == null)
                buf.append(HTMLRenderer.sanitizeString(pn.getName()));
        } else if ( ("eepsite".equals(type)) || ("i2p".equals(type)) ||
                   ("website".equals(type)) || ("http".equals(type)) || ("web".equals(type)) ) {
            String loc = pn.getLocation();
            if (loc != null) {
                buf.append("<a href=\"externallink.jsp?");
                if (pn.getNetwork() != null)
                    buf.append("schema=").append(Base64.encode(pn.getNetwork())).append("&amp;");
                if (pn.getLocation() != null)
                    buf.append("location=").append(Base64.encode(pn.getLocation())).append("&amp;");
                buf.append("\" title=\"View ").append(HTMLRenderer.sanitizeTagParam(pn.getLocation())).append("\">");
            }
            buf.append(HTMLRenderer.sanitizeString(pn.getName()));
            if (loc != null) {
                buf.append("</a>");
            }
        } else {
            buf.append("<a href=\"\" title=\"go somewhere? ").append(HTMLRenderer.sanitizeString(pn.toString())).append("\">");
            buf.append(HTMLRenderer.sanitizeString(pn.getName())).append("</a>");
        }
        return buf.toString();
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
            renderer.renderPost(user, archive, entry, out, false, true);
            renderComments(user, out, info, data, entry, archive, renderer);
        } else {
            for (int i = offset; i < posts.size() && i < offset + POSTS_PER_PAGE; i++) {
                BlogURI uri = (BlogURI)posts.get(i);
                EntryContainer entry = archive.getEntry(uri);
                renderer.renderPost(user, archive, entry, out, true, true);
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
                    renderer.renderComment(user, archive, cur, out);
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

    
    private boolean renderUpdatedImage(String requestedImage, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        BlogConfigBean bean = BlogConfigServlet.getConfigBean(req);
        if ( (bean != null) && (bean.isUpdated()) && (bean.getLogo() != null) ) {
            // the updated image only affects *our* blog...
            User u = bean.getUser();
            if (u != null) {
                String reqBlog = req.getParameter(PARAM_BLOG);
                if ( (reqBlog == null) || (u.getBlog().toBase64().equals(reqBlog)) ) {
                    if (BlogInfoData.ATTACHMENT_LOGO.equals(requestedImage)) {
                        File logo = bean.getLogo();
                        if (logo != null) {
                            byte buf[] = new byte[4096];
                            resp.setContentType("image/png");
                            resp.setContentLength((int)logo.length());
                            OutputStream out = resp.getOutputStream();
                            FileInputStream in = null;
                            try {
                                in = new FileInputStream(logo);
                                int read = 0;
                                while ( (read = in.read(buf)) != -1) 
                                    out.write(buf, 0, read);
                                _log.debug("Done writing the updated full length logo");
                            } finally {
                                if (in != null) try { in.close(); } catch (IOException ioe) {}
                                if (out != null) try { out.close(); } catch (IOException ioe) {}
                            }
                            _log.debug("Returning from writing the updated full length logo");
                            return true;
                        }
                    } else {
                        // ok, the blogConfigBean doesn't let people configure other things yet... fall through
                    }
                }
            }
        }
        return false;
    }
    
    private boolean renderPublishedImage(String requestedImage, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // nothing matched in the updated config, lets look at the current published info
        String blog = req.getParameter(PARAM_BLOG);
        if (blog != null) {
            Archive archive = BlogManager.instance().getArchive();
            byte h[] = Base64.decode(blog);
            if ( (h != null) && (h.length == Hash.HASH_LENGTH) ) {
                Hash blogHash = new Hash(h);
                BlogInfo info = archive.getBlogInfo(blogHash);
                String entryId = info.getProperty(BlogInfo.SUMMARY_ENTRY_ID);
                _log.debug("Author's entryId: " + entryId);
                if (entryId != null) {
                    BlogURI dataURI = new BlogURI(entryId);
                    EntryContainer entry = archive.getEntry(dataURI);
                    if (entry != null) {
                        BlogInfoData data = new BlogInfoData();
                        try {
                            data.load(entry);

                            _log.debug("Blog info data loaded from: " + entryId);
                            Attachment toWrite = null;
                            if (BlogInfoData.ATTACHMENT_LOGO.equals(requestedImage)) {
                                toWrite = data.getLogo();
                            } else {
                                toWrite = data.getOtherAttachment(requestedImage);
                            }
                            if (toWrite != null) {
                                resp.setContentType("image/png");
                                resp.setContentLength(toWrite.getDataLength());
                                InputStream in = null;
                                OutputStream out = null;
                                try {
                                    in = toWrite.getDataStream();
                                    out = resp.getOutputStream();
                                    byte buf[] = new byte[4096];
                                    int read = -1;
                                    while ( (read = in.read(buf)) != -1)
                                        out.write(buf, 0, read);
                                    
                                    _log.debug("Write image from: " + entryId);
                                    return true;
                                } finally { 
                                    if (in != null) try { in.close(); } catch (IOException ioe) {}
                                    if (out != null) try { out.close(); } catch (IOException ioe) {}
                                }
                            }
                        } catch (IOException ioe) {
                            _log.debug("Error reading/writing: " + entryId, ioe);
                            data = null;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** 1px png, base64 encoded, used if they asked for an image that we dont know of */
    private static final byte BLANK_IMAGE[] = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVQI12NgYAAAAAMAASDVlMcAAAAASUVORK5CYII=");    
    private boolean renderDefaultImage(String requestedImage, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (requestedImage.equals("logo.png")) {
            InputStream in = req.getSession().getServletContext().getResourceAsStream("/images/default_blog_logo.png");
            if (in != null) {
                resp.setContentType("image/png");
                OutputStream out = resp.getOutputStream();
                try {
                    byte buf[] = new byte[4096];
                    int read = -1;
                    while ( (read = in.read(buf)) != -1) 
                        out.write(buf, 0, read);
                    _log.debug("Done writing default logo");
                } finally {
                    try { in.close(); } catch (IOException ioe) {}
                    try { out.close(); } catch (IOException ioe) {}
                    return true;
                }
            }
        }
        resp.setContentType("img.png");
        resp.setContentLength(BLANK_IMAGE.length);
        OutputStream out = resp.getOutputStream();
        try {
            out.write(BLANK_IMAGE);
        } finally {
            try { out.close(); } catch (IOException ioe) {}
        }
        _log.debug("Done writing default image");
        return true;
    }
    
    private static final String CSS = "\n" +
"* {\n" +
"    margin: 0px;\n" +
"    padding: 0px;\n" +
"}\n" +
"body {\n" +
"    font-family: Arial, Helvetica, sans-serif;\n" +
"    font-size: 100%;\n" +
"    background-color : #EEEEEE;\n" +
"}\n" +
"a {\n" +
"    text-decoration: none;\n" +
"}\n" +
"a:hover {\n" +
"    color: red;\n" +
"}\n" +
"select {\n" +
"    min-width: 1.5em;\n" +
"}\n" +
".syndieBlog {\n" +
"}\n" +
".syndieBlogTopNav {\n" +
"    float:left;\n" +
"    width: 100%;\n" +
"    background-color: #BBBBBB;\n" +
"}\n" +
".syndieBlogTopNavUser {\n" +
"    text-align: left;\n" +
"    float: left;\n" +
"    margin: 2px;\n" +
"}\n" +
".syndieBlogTopNavAdmin {\n" +
"    text-align: left;\n" +
"    float: right;\n" +
"    margin: 2px;\n" +
"}\n" +
".syndieBlogHeader {\n" +
"    width: 100%;\n" +
"    background-color: black;\n" +
"    float:left;\n" +
"}\n" +
".syndieBlogHeader a {\n" +
"    color: white;\n" +
"    padding: 4px;\n" +
"}\n" +
".syndieBlogHeader b {\n" +
"    font-size: 1.2em;\n" +
"}\n" +
".syndieBlogLogo {\n" +
"    float: left;\n" +
"}\n" +
".syndieBlogLinks {\n" +
"    width: 20%;\n" +
"    float: left;\n" +
"}\n" +
".syndieBlogLinkGroup {\n" +
"    font-size: 0.8em;\n" +
"    background-color: #DDD;\n" +
"    border: 1px solid black;\n" +
"    margin: 5px;\n" +
"    padding: 2px;\n" +
"}\n" +
".syndieBlogLinkGroup ul {\n" +
"    list-style: none;\n" +
"}\n" +
".syndieBlogLinkGroup li {\n" +
"    width: 100%;\n" +
"    overflow: hidden;\n" +
"    white-space: nowrap;\n" +
"}\n" +
".syndieBlogLinkGroupName {\n" +
"    font-weight: bold;\n" +
"    width: 100%;\n" +
"    border-bottom: 1px dashed black;\n" +
"    display: block;\n" +
"    overflow: hidden;\n" +
"    white-space: nowrap;\n" +
"}\n" +
".syndieBlogPostInfoGroup {\n" +
"    font-size: 0.8em;\n" +
"    background-color: #FFEA9F;\n" +
"    border: 1px solid black;\n" +
"    margin: 5px;\n" +
"    padding: 2px;\n" +
"}\n" +
".syndieBlogPostInfoGroup ol {\n" +
"    list-style: none;\n" +
"}\n" +
".syndieBlogPostInfoGroup li {\n" +
"    white-space: nowrap;\n" +
"    width: 100%;\n" +
"    overflow: hidden;\n" +
"}\n" +
".syndieBlogPostInfoGroupName {\n" +
"    font-weight: bold;\n" +
"    width: 100%;\n" +
"    border-bottom: 1px dashed black;\n" +
"    display: block;\n" +
"    overflow: hidden;\n" +
"    white-space: nowrap;\n" +
"}\n" +
".syndieBlogMeta {\n" +
"    text-align: left;\n" +
"    font-size: 0.8em;\n" +
"    background-color: #DDD;\n" +
"    border: 1px solid black;\n" +
"    margin: 5px;\n" +
"    padding: 2px;\n" +
"}\n" +
".syndieBlogBody {\n" +
"    width: 80%;\n" +
"    float: left;\n" +
"}\n" +
".syndieBlogPost {\n" +
"    border: 1px solid black;\n" +
"    margin-top: 5px;\n" +
"    margin-right: 5px;\n" +
"    word-wrap: break-word;\n" +
"}\n" +
".syndieBlogPostHeader {\n" +
"    background-color: #BBB;\n" +
"    padding: 2px;\n" +
"}\n" +
".syndieBlogPostSubject {\n" +
"    font-weight: bold;\n" +
"}\n" +
".syndieBlogPostFrom {\n" +
"    text-align: right;\n" +
"}\n" +
".syndieBlogPostSummary {\n" +
"    background-color: #FFFFFF;\n" +
"    padding: 2px;\n" +
"}\n" +
".syndieBlogPostDetails {\n" +
"    background-color: #DDD;\n" +
"    padding: 2px;\n" +
"}\n" +
".syndieBlogNav {\n" +
"    text-align: center;\n" +
"}\n" +
".syndieBlogComments {\n" +
"    border: none;\n" +
"    margin-top: 5px;\n" +
"    margin-left: 0px;\n" +
"    float: left;\n" +
"}\n" +
".syndieBlogComments ul {\n" +
"    list-style: none;\n" +
"    margin-left: 10px;\n" +
"}\n" +
".syndieBlogCommentInfoGroup {\n" +
"    font-size: 0.8em;\n" +
"    margin-right: 5px;\n" +
"}\n" +
".syndieBlogCommentInfoGroup ol {\n" +
"    list-style: none;\n" +
"}\n" +
".syndieBlogCommentInfoGroup li {\n" +
"}\n" +
".syndieBlogCommentInfoGroupName {\n" +
"    font-size: 0.8em;\n" +
"    font-weight: bold;\n" +
"}\n";
    protected String getTitle() { return "unused"; }
    protected void renderServletDetails(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index, 
                                        int threadOffset, BlogURI visibleEntry, Archive archive) throws IOException {
        throw new RuntimeException("unused");
    }
}
