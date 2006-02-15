package net.i2p.syndie.sml;

import java.io.*;
import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.client.naming.PetName;
import net.i2p.data.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.web.*;
import net.i2p.syndie.*;

/**
 * Renders posts for display within the blog view
 *
 */
public class BlogRenderer extends HTMLRenderer {
    private BlogInfo _blog;
    private BlogInfoData _data;
    private boolean _isComment;
    public BlogRenderer(I2PAppContext ctx, BlogInfo info, BlogInfoData data) {
        super(ctx);
        _blog = info;
        _data = data;
        _isComment = false;
    }

    public void renderPost(User user, Archive archive, EntryContainer entry, Writer out, boolean cutBody, boolean showImages) throws IOException {
        _isComment = false;
        render(user, archive, entry, out, cutBody, showImages);
    }
    public void renderComment(User user, Archive archive, EntryContainer entry, Writer out) throws IOException {
        _isComment = true;
        render(user, archive, entry, out, false, true);
    }
    
    public void receiveHeaderEnd() {
        _preBodyBuffer.append("<div class=\"syndieBlogPost\"><hr style=\"display: none\" />\n");
        _preBodyBuffer.append("<div class=\"syndieBlogPostHeader\">\n");
        _preBodyBuffer.append("<div class=\"syndieBlogPostSubject\">");
        String subject = (String)_headers.get(HEADER_SUBJECT);
        if (subject == null)
            subject = "[no subject]";
        String tags[] = _entry.getTags();
        for (int i = 0; (tags != null) && (i < tags.length); i++)
            displayTag(_preBodyBuffer, _data, tags[i]);
        _preBodyBuffer.append(getSpan("subjectText")).append(sanitizeString(subject)).append("</span></div>\n");
        
        String name = getAuthor();
        String when = getEntryDate(_entry.getURI().getEntryId());
        _preBodyBuffer.append("<div class=\"syndieBlogPostFrom\">Posted by: <a href=\"");
        _preBodyBuffer.append(getMetadataURL(_entry.getURI().getKeyHash()));
        _preBodyBuffer.append("\" title=\"View their profile\">");
        _preBodyBuffer.append(sanitizeString(name));
        _preBodyBuffer.append("</a> on ");
        _preBodyBuffer.append(when);
        _preBodyBuffer.append("</div>\n");
        _preBodyBuffer.append("</div><!-- end syndieBlogPostHeader -->\n");
        
        _preBodyBuffer.append("<div class=\"syndieBlogPostSummary\">\n");
    }
    
    public void receiveEnd() { 
        _postBodyBuffer.append("</div><!-- end syndieBlogPostSummary -->\n");
        _postBodyBuffer.append("<div class=\"syndieBlogPostDetails\">\n");
        int childCount = getChildCount(_archive.getIndex().getThreadedIndex().getNode(_entry.getURI()));
        if ( (_cutReached || childCount > 0) && (_cutBody) ) {
            _postBodyBuffer.append("<a href=\"");
            _postBodyBuffer.append(getEntryURL()).append("\" title=\"View comments on this post\">Read more</a> ");
        }
        if (childCount > 0) {
            _postBodyBuffer.append(childCount).append(" ");
            if (childCount > 1)
                _postBodyBuffer.append(" comments already, ");
            else
                _postBodyBuffer.append(" comment already, ");
        }
        _postBodyBuffer.append("<a href=\"");
        _postBodyBuffer.append(getReplyURL()).append("\" title=\"Reply to this post\">Leave a comment</a>\n");
        if (_isComment)
            renderCommentMeta();
        _postBodyBuffer.append("</div><!-- end syndieBlogPostDetails -->\n");
        _postBodyBuffer.append("</div><!-- end syndieBlogPost -->\n\n");
    }
    
    private void renderCommentMeta() {
        BlogURI postURI = null;
        Attachment attachments[] = null;
        if (_entry != null) {
            postURI = _entry.getURI();
            attachments = _entry.getAttachments();
        }
        BlogPostInfoRenderer.renderAttachments(postURI, "syndieBlogCommentInfo", attachments, _postBodyBuffer);
        BlogPostInfoRenderer.renderBlogs(postURI, _user, "syndieBlogCommentInfo", _blogs, _postBodyBuffer);
        BlogPostInfoRenderer.renderLinks(postURI, _user, "syndieBlogCommentInfo", _links, _postBodyBuffer);
        BlogPostInfoRenderer.renderAddresses(postURI, _user, "syndieBlogCommentInfo", _addresses, _postBodyBuffer);
        BlogPostInfoRenderer.renderArchives(postURI, _user, "syndieBlogCommentInfo", _archives, _postBodyBuffer);
    }
    
    private int getChildCount(ThreadNode node) {
        int nodes = 0;
        for (int i = 0; i < node.getChildCount(); i++) {
            nodes++;
            nodes += getChildCount(node.getChild(i));
        }
        return nodes;
    }
    
    private String getAuthor() {
        PetName pn = null;
        if ( (_entry != null) && (_user != null) )
            pn = _user.getPetNameDB().getByLocation(_entry.getURI().getKeyHash().toBase64());
        if (pn != null)
            return pn.getName();
        BlogInfo info = null;
        if (_entry != null) {
            info = _archive.getBlogInfo(_entry.getURI());
            if (info != null) {
                String str = info.getProperty(BlogInfo.NAME);
                if (str != null)
                    return str;
            }
            return _entry.getURI().getKeyHash().toBase64().substring(0,6);
        } else {
            return "No name?";
        }
    }

    private void displayTag(StringBuffer buf, BlogInfoData data, String tag) {
        //buf.append("<a href=\"");
        //buf.append(getPageURL(_blog.getKey().calculateHash(), tag, -1, null, 5, 0, false, true));
        //buf.append("\" title=\"Filter the blog by the tag '").append(sanitizeTagParam(tag)).append("'\">");
        if ( (tag == null) || ("[none]".equals(tag) ) )
            return;
        buf.append("<img src=\"").append(getTagIconURL(tag)).append("\" alt=\"");
        buf.append(sanitizeTagParam(tag)).append("\" />");
        //buf.append("</a>");
        buf.append(" ");
    }
    
    public String getMetadataURL(Hash blog) { return ThreadedHTMLRenderer.buildProfileURL(blog); }
    private String getTagIconURL(String tag) {
        return "viewicon.jsp?tag=" + Base64.encode(tag) + "&amp;" + 
               ViewBlogServlet.PARAM_BLOG + "=" + _blog.getKey().calculateHash().toBase64();
    }
    
    private String getReplyURL() { 
        String subject = (String)_headers.get(HEADER_SUBJECT);
        if (subject != null) {
            if (!subject.startsWith("re:"))
                subject = "re: " + subject;
        } else {
            subject = "re: ";
        }
        return "post.jsp?" + PostServlet.PARAM_PARENT + "=" 
               + Base64.encode(_entry.getURI().getKeyHash().toBase64() + "/" + _entry.getURI().getEntryId()) + "&amp;"
               + PostServlet.PARAM_SUBJECT + "=" + sanitizeTagParam(subject) + "&amp;";
    }
    
    protected String getEntryURL() { return getEntryURL(_user != null ? _user.getShowImages() : true); }
    protected String getEntryURL(boolean showImages) {
        return getEntryURL(_entry, _blog, showImages);
    }
    static String getEntryURL(EntryContainer entry, BlogInfo blog, boolean showImages) {
        if (entry == null) return "unknown";
        return "blog.jsp?" 
               + ViewBlogServlet.PARAM_BLOG + "=" + (blog != null ? blog.getKey().calculateHash().toBase64() : "") + "&amp;"
               + ViewBlogServlet.PARAM_ENTRY + "="
               + Base64.encode(entry.getURI().getKeyHash().getData()) + '/' + entry.getURI().getEntryId();
    }
    
    protected String getAttachmentURLBase() { 
        return "invalid";
    }
    
    protected String getAttachmentURL(int id) {
        if (_entry == null) return "unknown";
        return "blog.jsp?"
               + ViewBlogServlet.PARAM_BLOG + "=" + _blog.getKey().calculateHash().toBase64() + "&amp;"
               + ViewBlogServlet.PARAM_ATTACHMENT + "=" 
               + Base64.encode(_entry.getURI().getKeyHash().getData()) + "/"
               + _entry.getURI().getEntryId() + "/" 
               + id;
    }

    public String getPageURL(String entry) {
        StringBuffer buf = new StringBuffer(128);
        buf.append("blog.jsp?");
        buf.append(ViewBlogServlet.PARAM_BLOG).append(_blog.getKey().calculateHash().toBase64()).append("&amp;");

        if (entry != null) {
            if (entry.startsWith("entry://"))
                entry = entry.substring("entry://".length());
            else if (entry.startsWith("blog://"))
                entry = entry.substring("blog://".length());
            int split = entry.indexOf('/');
            if (split > 0) {
                buf.append(ViewBlogServlet.PARAM_ENTRY).append("=");
                buf.append(sanitizeTagParam(entry.substring(0, split))).append('/');
                buf.append(sanitizeTagParam(entry.substring(split+1))).append("&amp;");
            }
        }
        return buf.toString();
    }
    public String getPageURL(Hash blog, String tag, long entryId, String group, int numPerPage, int pageNum, boolean expandEntries, boolean showImages) {
        StringBuffer buf = new StringBuffer(128);
        buf.append("blog.jsp?");
        buf.append(ViewBlogServlet.PARAM_BLOG).append("=");
        buf.append(_blog.getKey().calculateHash().toBase64()).append("&amp;");
        
        if ( (blog != null) && (entryId > 0) ) {
            buf.append(ViewBlogServlet.PARAM_ENTRY).append("=");
            buf.append(blog.toBase64()).append('/');
            buf.append(entryId).append("&amp;");
        }
        if (tag != null)
            buf.append(ViewBlogServlet.PARAM_TAG).append('=').append(sanitizeTagParam(tag)).append("&amp;");
        if ( (pageNum >= 0) && (numPerPage > 0) )
            buf.append(ViewBlogServlet.PARAM_OFFSET).append('=').append(pageNum*numPerPage).append("&amp;");
        return buf.toString();
    }
}