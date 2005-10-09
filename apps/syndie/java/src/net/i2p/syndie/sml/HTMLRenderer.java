package net.i2p.syndie.sml;

import java.io.*;
import java.text.*;
import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.client.naming.PetName;
import net.i2p.data.*;
import net.i2p.syndie.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.web.*;
import net.i2p.util.Log;

/**
 *
 */
public class HTMLRenderer extends EventReceiverImpl {
    private Log _log;
    protected SMLParser _parser;
    protected Writer _out;
    protected User _user;
    protected Archive _archive;
    protected EntryContainer _entry;
    protected boolean _showImages;
    protected boolean _cutBody;
    protected boolean _cutReached;
    protected int _cutSize;
    protected int _lastNewlineAt;
    protected Map _headers;
    protected List _addresses;
    protected List _links;
    protected List _blogs;
    protected List _archives;
    protected StringBuffer _preBodyBuffer;
    protected StringBuffer _bodyBuffer;
    protected StringBuffer _postBodyBuffer;
    
    public HTMLRenderer(I2PAppContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(HTMLRenderer.class);
        _parser = new SMLParser(ctx);
    }

    /**
     * Usage: HTMLRenderer smlFile outputFile
     */
    public static void main(String args[]) {
        if (args.length != 2) {
            System.err.println("Usage: HTMLRenderer smlFile outputFile");
            return;
        }
        HTMLRenderer renderer = new HTMLRenderer(I2PAppContext.getGlobalContext());
        Writer out = null;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(1024*512);
            FileInputStream in = new FileInputStream(args[0]);
            byte buf[] = new byte[1024];
            int read = 0;
            while ( (read = in.read(buf)) != -1)
                baos.write(buf, 0, read);
            out = new OutputStreamWriter(new FileOutputStream(args[1]), "UTF-8");
            renderer.render(new User(), BlogManager.instance().getArchive(), null, DataHelper.getUTF8(baos.toByteArray()), out, false, true);   
        } catch (IOException ioe) {
            ioe.printStackTrace();
        } finally {
            if (out != null) try { out.close(); } catch (IOException ioe) {}
        }
    }
    
    /**
     * Retrieve: class="s_summary_$element" or class="s_detail_$element ss_$style_detail_$element"
     */
    protected String getClass(String element) {
        StringBuffer rv = new StringBuffer(64);
        rv.append(" class=\"s_");
        if (_cutBody)
            rv.append("summary_");
        else
            rv.append("detail_");
        rv.append(element);
        if (_entry != null) {
            String style = sanitizeStyle(_entry.getHeader(HEADER_STYLE));
            if (style != null) {
                rv.append(" ss_").append(style);
                if (_cutBody)
                    rv.append("summary_");
                else
                    rv.append("detail_");
                rv.append(element);
            }
        }
        rv.append("\" ");
        return rv.toString();
    }
    protected String getSpan(String element) {
        return "<span " + getClass(element) + ">";
    }
    
    public void renderUnknownEntry(User user, Archive archive, BlogURI uri, Writer out) throws IOException {
        BlogInfo info = archive.getBlogInfo(uri);
        if (info == null)
            out.write("<br /><span " + getClass("unknownBlog") + ">The blog <span " + getClass("blogURI") + ">" + uri.getKeyHash().toBase64() + "</span> is not known locally.  "
                      + "Please get it from an archive and <a " + getClass("unknownRetry") + " href=\"" 
                      + getPageURL(uri.getKeyHash(), null, uri.getEntryId(), -1, -1, user.getShowExpanded(), user.getShowImages())
                      + "\">try again</a></span>");
        else
            out.write("<br /><span " + getClass("unknownEntry") + ">The blog <a " + getClass("unknownRetry") + " href=\""
                      + getPageURL(uri.getKeyHash(), null, -1, -1, -1, user.getShowExpanded(), user.getShowImages())
                      + "\">" + info.getProperty(BlogInfo.NAME) + "</a> is known, but the entry " + uri.getEntryId() + " is not.  "
                      + "Please get it from an archive and <a " + getClass("unknownRetry") + " href=\"" 
                      + getPageURL(uri.getKeyHash(), null, uri.getEntryId(), -1, -1, user.getShowExpanded(), user.getShowImages())
                      + "\">try again</a></span>");
    }
    
    public void render(User user, Archive archive, EntryContainer entry, Writer out, boolean cutBody, boolean showImages) throws IOException {
        if (entry == null)
            return;
        render(user, archive, entry, entry.getEntry().getText(), out, cutBody, showImages);
    }
    public void render(User user, Archive archive, EntryContainer entry, String rawSML, Writer out, boolean cutBody, boolean showImages) throws IOException {
        prepare(user, archive, entry, rawSML, out, cutBody, showImages);
        
        _out.write(_preBodyBuffer.toString());
        _out.write(_bodyBuffer.toString());
        _out.write(_postBodyBuffer.toString());
        //int len = _preBodyBuffer.length() + _bodyBuffer.length() + _postBodyBuffer.length();
        //System.out.println("Wrote " + len);
    }
    protected void prepare(User user, Archive archive, EntryContainer entry, String rawSML, Writer out, boolean cutBody, boolean showImages) throws IOException {
        _user = user;
        _archive = archive;
        _entry = entry;
        _out = out;
        _headers = new HashMap();
        _preBodyBuffer = new StringBuffer(1024);
        _bodyBuffer = new StringBuffer(1024);
        _postBodyBuffer = new StringBuffer(1024);
        _addresses = new ArrayList();
        _links = new ArrayList();
        _blogs = new ArrayList();
        _archives = new ArrayList();
        _cutBody = cutBody;
        _showImages = showImages;
        _cutReached = false;
        _cutSize = 1024;
        _parser.parse(rawSML, this);
    }
    
    public void receivePlain(String text) { 
        if (!continueBody()) { return; }
        _bodyBuffer.append(sanitizeString(text)); 
    }
    
    public void receiveBold(String text) { 
        if (!continueBody()) { return; }
        _bodyBuffer.append("<em ").append(getClass("bold")).append(" >").append(sanitizeString(text)).append("</em>");
    }
    public void receiveItalic(String text) { 
        if (!continueBody()) { return; }
        _bodyBuffer.append("<em ").append(getClass("italic")).append(" >").append(sanitizeString(text)).append("</em>");
    }
    public void receiveUnderline(String text) { 
        if (!continueBody()) { return; }
        _bodyBuffer.append("<em ").append(getClass("underline")).append(" >").append(sanitizeString(text)).append("</em>");
    }
    public void receiveHR() {
        if (!continueBody()) { return; }
        _bodyBuffer.append(getSpan("hr")).append("<hr /></span>");
    }
    public void receiveH1(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append("<h1 ").append(getClass("h1")).append(" >").append(sanitizeString(body)).append("</span></h1>");
    }
    public void receiveH2(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append("<h2 ").append(getClass("h2")).append(" >").append(sanitizeString(body)).append("</span></h2>");
    }
    public void receiveH3(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append("<h3 ").append(getClass("h3")).append(" >").append(sanitizeString(body)).append("</span></h3>");
    }
    public void receiveH4(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append("<h4 ").append(getClass("h4")).append(" >").append(sanitizeString(body)).append("</span></h4>");
    }
    public void receiveH5(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append("<h5 ").append(getClass("h5")).append(" >").append(sanitizeString(body)).append("</span></h5>");
    }
    public void receivePre(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append("<pre ").append(getClass("pre")).append(" >").append(sanitizeString(body)).append("</pre>");
    }
    
    public void receiveQuote(String text, String whoQuoted, String quoteLocationSchema, String quoteLocation) {
        if (!continueBody()) { return; }
        _bodyBuffer.append("<quote ").append(getClass("quote")).append(" >").append(sanitizeString(text)).append("</quote>");
    }
    public void receiveCode(String text, String codeLocationSchema, String codeLocation) { 
        if (!continueBody()) { return; }
           _bodyBuffer.append("<code ").append(getClass("code")).append(" >").append(sanitizeString(text)).append("</code>");
    }
    public void receiveImage(String alternateText, int attachmentId) {
        if (!continueBody()) { return; }
        if (_showImages) {
            _bodyBuffer.append("<img ").append(getClass("img")).append(" src=\"").append(getAttachmentURL(attachmentId)).append("\"");
            if (alternateText != null)
                _bodyBuffer.append(" alt=\"").append(sanitizeTagParam(alternateText)).append("\"");
            _bodyBuffer.append(" />");
        } else {
            _bodyBuffer.append(getSpan("imgSummary")).append("[image: ").append(getSpan("imgSummaryAttachment")).append(" attachment ").append(attachmentId);
            _bodyBuffer.append(":</span> ").append(getSpan("imgSummaryAlt")).append(sanitizeString(alternateText));
            _bodyBuffer.append("</span> <a ").append(getClass("imgSummaryLink")).append(" href=\"").append(getEntryURL(true)).append("\">view images</a>]</span>");
        }
    }
    
    public void receiveCut(String summaryText) { 
        if (!continueBody()) { return; }
        _cutReached = true;
        if (_cutBody) {
            _bodyBuffer.append("<a ").append(getClass("cutExplicit")).append(" href=\"").append(getEntryURL()).append("\">");
            if ( (summaryText != null) && (summaryText.length() > 0) )
                _bodyBuffer.append(sanitizeString(summaryText));
            else
                _bodyBuffer.append("more inside...");
            _bodyBuffer.append("</a>\n");
        } else {
            if (summaryText != null)
                _bodyBuffer.append(getSpan("cutIgnore")).append(sanitizeString(summaryText)).append("</span>\n");
        }
    }
    
    /** are we either before the cut or rendering without cutting? */
    protected boolean continueBody() {
        boolean rv = ( (!_cutReached) && (_bodyBuffer.length() <= _cutSize) ) || (!_cutBody);
        //if (!rv) 
        //    System.out.println("rv: " + rv + " Cut reached: " + _cutReached + " bodyBufferSize: " + _bodyBuffer.length() + " cutBody? " + _cutBody);
        if (!rv && !_cutReached) {
            // exceeded the allowed size
            _bodyBuffer.append("<a ").append(getClass("cutImplicit")).append(" href=\"").append(getEntryURL()).append("\">more inside...</a>\n");
            _cutReached = true;
        }
        return rv;
    }
    
    public void receiveNewline() { 
        if (!continueBody()) { return; }
        if (true || (_lastNewlineAt >= _bodyBuffer.length()))
            _bodyBuffer.append(getSpan("nl")).append("<br /></span>\n");
        else
            _lastNewlineAt = _bodyBuffer.length();
    }
    public void receiveLT() { 
        if (!continueBody()) { return; }
        _bodyBuffer.append(getSpan("lt")).append("&lt;</span>");
    }
    public void receiveGT() { 
        if (!continueBody()) { return; }
        _bodyBuffer.append(getSpan("gt")).append("&gt;</span>");
    }
    public void receiveBegin() {}
    public void receiveLeftBracket() { 
        if (!continueBody()) { return; }
        _bodyBuffer.append(getSpan("lb")).append("[</span>");
    }
    public void receiveRightBracket() { 
        if (!continueBody()) { return; }
        _bodyBuffer.append(getSpan("rb")).append("]</span>");
    }
    
    protected static class Blog {
        public String name;
        public String hash;
        public String tag;
        public long entryId;
        public List locations;
        public int hashCode() { return -1; }
        public boolean equals(Object o) {
            Blog b = (Blog)o;
            return DataHelper.eq(hash, b.hash) && DataHelper.eq(tag, b.tag) && DataHelper.eq(name, b.name) 
                   && DataHelper.eq(entryId, b.entryId) && DataHelper.eq(locations, b.locations);
        }
    }
    /**
     * when we see a link to a blog, we may want to:
     * = view the blog entry
     * = view all entries in that blog
     * = view all entries in that blog with the given tag
     * = view the blog's metadata
     * = [fetch the blog from other locations]
     * = [add the blog's locations to our list of known locations]
     * = [shitlist the blog]
     * = [add the blog to one of our groups]
     *
     * [blah] implies *later*.
     *
     * Currently renders to:
     *  <a href="$entryURL">$description</a> 
     *   [blog: <a href="$blogURL">$name</a> (<a href="$metaURL">meta</a>) 
     *   [tag: <a href="$blogTagURL">$tag</a>] 
     *   archived at $location*]
     *
     */
    public void receiveBlog(String name, String hash, String tag, long entryId, List locations, String description) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receiving the blog: " + name + "/" + hash + "/" + tag + "/" + entryId +"/" + locations + ": "+ description);
        byte blogData[] = Base64.decode(hash);
        if ( (blogData == null) || (blogData.length != Hash.HASH_LENGTH) )
            return;
    
        Blog b = new Blog();
        b.name = name;
        b.hash = hash;
        b.tag = tag;
        b.entryId = entryId;
        b.locations = locations;
        if (!_blogs.contains(b))
            _blogs.add(b);
    
        if (!continueBody()) { return; }
        if (hash == null) return;
        
        Hash blog = new Hash(blogData);
        if (entryId > 0) {
            String pageURL = getPageURL(blog, tag, entryId, -1, -1, true, (_user != null ? _user.getShowImages() : false));
            _bodyBuffer.append("<a ").append(getClass("blogEntryLink")).append(" href=\"").append(pageURL).append("\">");
            if ( (description != null) && (description.trim().length() > 0) ) {
                _bodyBuffer.append(sanitizeString(description));
            } else if ( (name != null) && (name.trim().length() > 0) ) {
                _bodyBuffer.append(sanitizeString(name));
            } else {
                _bodyBuffer.append("[view entry]");
            }
            _bodyBuffer.append("</a>");
        }
        
        
        String url = getPageURL(blog, null, -1, -1, -1, (_user != null ? _user.getShowExpanded() : false), (_user != null ? _user.getShowImages() : false));
        _bodyBuffer.append(getSpan("blogEntrySummary")).append(" [<a ").append(getClass("blogLink")).append(" href=\"").append(url);
        _bodyBuffer.append("\">");
        if ( (name != null) && (name.trim().length() > 0) )
            _bodyBuffer.append(sanitizeString(name));
        else
            _bodyBuffer.append("view");
        _bodyBuffer.append("</a> (<a ").append(getClass("blogMeta")).append(" href=\"").append(getMetadataURL(blog)).append("\">meta</a>)");
        if ( (tag != null) && (tag.trim().length() > 0) ) {
            url = getPageURL(blog, tag, -1, -1, -1, false, false);
            _bodyBuffer.append(" <a ").append(getClass("blogTagLink")).append(" href=\"").append(url);
            _bodyBuffer.append("\">Tag: ").append(sanitizeString(tag)).append("</a>");
        }
        if ( (locations != null) && (locations.size() > 0) ) {
            _bodyBuffer.append(getSpan("blogArchive")).append(" Archives: ");
            for (int i = 0; i < locations.size(); i++) {
                SafeURL surl = (SafeURL)locations.get(i);
                if (_user.getAuthenticated() && BlogManager.instance().authorizeRemote(_user) )
                    _bodyBuffer.append("<a ").append(getClass("blogArchiveView")).append(" href=\"").append(getArchiveURL(blog, surl)).append("\">").append(sanitizeString(surl.toString())).append("</a> ");
                else
                    _bodyBuffer.append(getSpan("blogArchiveURL")).append(sanitizeString(surl.toString())).append("</span> ");
            }
            _bodyBuffer.append("</span>");
        }
        _bodyBuffer.append("]</span> ");
    }
    
    protected static class ArchiveRef {
        public String name;
        public String description;
        public String locationSchema;
        public String location;
        public int hashCode() { return -1; }
        public boolean equals(Object o) {
            ArchiveRef a = (ArchiveRef)o;
            return DataHelper.eq(name, a.name) && DataHelper.eq(description, a.description) 
                   && DataHelper.eq(locationSchema, a.locationSchema) 
                   && DataHelper.eq(location, a.location);
        }
    }
    public void receiveArchive(String name, String description, String locationSchema, String location, 
                               String postingKey, String anchorText) {        
        ArchiveRef a = new ArchiveRef();
        a.name = name;
        a.description = description;
        a.locationSchema = locationSchema;
        a.location = location;
        if (!_archives.contains(a))
            _archives.add(a);
    
        if (!continueBody()) { return; }
        
        _bodyBuffer.append(getSpan("archive")).append(sanitizeString(anchorText)).append("</span>");
        _bodyBuffer.append(getSpan("archiveSummary")).append(" [Archive ");
        if (name != null)
            _bodyBuffer.append(getSpan("archiveSummaryName")).append(sanitizeString(name)).append("</span>");
        if (location != null) {
            _bodyBuffer.append(" at ");
            SafeURL surl = new SafeURL(locationSchema + "://" + location);
            _bodyBuffer.append("<a ").append(getClass("archiveSummaryLink")).append(" href=\"").append(getArchiveURL(null, surl));
            _bodyBuffer.append("\">").append(sanitizeString(surl.toString())).append("</a>");
            if (_user.getAuthenticated()) {
                _bodyBuffer.append(" <a ").append(getClass("archiveBookmarkLink")).append(" href=\"");
                _bodyBuffer.append(getBookmarkURL(sanitizeString(name), surl.getLocation(), surl.getSchema(), "syndiearchive"));
                _bodyBuffer.append("\">bookmark it</a>");
            }
        }
        if (description != null)
            _bodyBuffer.append(": ").append(getSpan("archiveSummaryDesc")).append(sanitizeString(description)).append("</span>");
        _bodyBuffer.append("]</span>");
    }
    
    protected static class Link {
        public String schema;
        public String location;
        public int hashCode() { return -1; }
        public boolean equals(Object o) {
            Link l = (Link)o;
            return DataHelper.eq(schema, l.schema) && DataHelper.eq(location, l.location);
        }
    }
    public void receiveLink(String schema, String location, String text) {
        Link l = new Link();
        l.schema = schema;
        l.location = location;
        if (!_links.contains(l))
            _links.add(l);
        if (!continueBody()) { return; }
        if ( (schema == null) || (location == null) ) return;
        _bodyBuffer.append("<a ").append(getClass("externalLink")).append(" href=\"externallink.jsp?schema=");
        _bodyBuffer.append(sanitizeURL(schema)).append("&location=");
        _bodyBuffer.append(sanitizeURL(location)).append("&description=");
        _bodyBuffer.append(sanitizeURL(text)).append("\">").append(sanitizeString(text)).append("</a>");
    }

    protected static class Address {
        public String name;
        public String schema;
        public String location;
        public String protocol;
        public int hashCode() { return -1; }
        public boolean equals(Object o) {
            Address a = (Address)o;
            return DataHelper.eq(schema, a.schema) && DataHelper.eq(location, a.location) && DataHelper.eq(protocol, a.protocol) && DataHelper.eq(name, a.name);
        }
    }
    
    public void importAddress(Address a) {
        if (I2PAppContext.getGlobalContext().getProperty("syndie.addressExport", "false").equalsIgnoreCase("true") 
                && I2PAppContext.getGlobalContext().namingService().lookup(a.name) == null 
                && a.schema.equalsIgnoreCase("i2p")) {
            PetName pn = new PetName(a.name, a.schema, a.protocol, a.location);
            I2PAppContext.getGlobalContext().petnameDb().add(pn);
            try {
                I2PAppContext.getGlobalContext().petnameDb().store();
            } catch (IOException ioe) {
                //ignore
            }
        }
    }
    
    public void receiveAddress(String name, String schema, String protocol, String location, String anchorText) {
        Address a = new Address();
        a.name = name;
        a.schema = schema;
        a.location = location;
        a.protocol = protocol;
        if (!_addresses.contains(a))
            _addresses.add(a);
        if (!continueBody()) { return; }
        if ( (schema == null) || (location == null) ) return;
        PetName pn = null;
        if (_user != null)
            pn = _user.getPetNameDB().getLocation(location);
        if (pn != null) {
            _bodyBuffer.append(getSpan("addr")).append(sanitizeString(anchorText)).append("</span>");
            _bodyBuffer.append(getSpan("addrKnownName")).append("(").append(sanitizeString(pn.getName())).append(")</span>");
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Receiving address [" + location + "]");
            _bodyBuffer.append("<a ").append(getClass("addrAdd")).append(" href=\"addresses.jsp?");
            if (schema != null)
                _bodyBuffer.append("network=").append(sanitizeTagParam(schema)).append('&');
            if (name != null)
                _bodyBuffer.append("name=").append(sanitizeTagParam(name)).append('&');
            if (protocol != null)
                _bodyBuffer.append("protocol=").append(sanitizeTagParam(protocol)).append('&');
            if (location != null)
                _bodyBuffer.append("location=").append(sanitizeTagParam(location));
            _bodyBuffer.append("\">").append(sanitizeString(anchorText)).append("</a>");
        }
    }
    
    public void receiveAttachment(int id, String anchorText) {
        if (!continueBody()) { return; }
        Attachment attachments[] = _entry.getAttachments();
        if ( (id < 0) || (id >= attachments.length)) {
            _bodyBuffer.append(getSpan("attachmentUnknown")).append(sanitizeString(anchorText)).append("</span>");
        } else {
            _bodyBuffer.append("<a ").append(getClass("attachmentView")).append(" href=\"").append(getAttachmentURL(id)).append("\">");
            _bodyBuffer.append(sanitizeString(anchorText)).append("</a>");
            _bodyBuffer.append(getSpan("attachmentSummary")).append(" (");
            _bodyBuffer.append(getSpan("attachmentSummarySize")).append(attachments[id].getDataLength()/1024).append("KB</span>, ");
            _bodyBuffer.append(getSpan("attachmentSummaryName")).append(" \"").append(sanitizeString(attachments[id].getName())).append("\"</span>, ");
            _bodyBuffer.append(getSpan("attachmentSummaryDesc")).append(" \"").append(sanitizeString(attachments[id].getDescription())).append("\"</span>, ");
            _bodyBuffer.append(getSpan("attachmentSummaryType")).append(sanitizeString(attachments[id].getMimeType())).append("</span>)</span>");
        }
    }
    
    public void receiveEnd() { 
        _postBodyBuffer.append("</td></tr>\n<!-- end of the post body -->");
        if (_cutBody) {
            _postBodyBuffer.append("<!-- beginning of the post summary -->\n");
            _postBodyBuffer.append("<tr ").append(getClass("summ")).append(">\n");
            _postBodyBuffer.append("<td colspan=\"2\" valign=\"top\" align=\"left\" ").append(getClass("summ")).append(" >");
            _postBodyBuffer.append("<a ").append(getClass("summLink")).append(" href=\"").append(getEntryURL()).append("\">View details...</a> ");
            _postBodyBuffer.append(getSpan("summ"));
            if ( (_entry != null) && (_entry.getAttachments() != null) && (_entry.getAttachments().length > 0) ) {
                int num = _entry.getAttachments().length;
                if (num == 1)
                    _postBodyBuffer.append("1 attachment ");
                else
                    _postBodyBuffer.append(num + " attachments ");
            }
            
            int blogs = _blogs.size();
            if (blogs == 1)
                _postBodyBuffer.append("1 blog reference ");
            else if (blogs > 1)
                _postBodyBuffer.append(blogs).append(" blog references ");
            
            int links = _links.size();
            if (links == 1)
                _postBodyBuffer.append("1 external link ");
            else if (links > 1)
                _postBodyBuffer.append(links).append(" external links ");

            int addrs = _addresses.size();
            if (addrs == 1)
                _postBodyBuffer.append("1 address ");
            else if (addrs > 1)
                _postBodyBuffer.append(addrs).append(" addresses ");
            
            int archives = _archives.size();
            if (archives == 1)
                _postBodyBuffer.append("1 archive ");
            else if (archives > 1)
                _postBodyBuffer.append(archives).append(" archives ");
            
            if (_entry != null) {
                List replies = _archive.getIndex().getReplies(_entry.getURI());
                if ( (replies != null) && (replies.size() > 0) ) {
                    if (replies.size() == 1)
                        _postBodyBuffer.append("1 reply ");
                    else
                        _postBodyBuffer.append(replies.size()).append(" replies ");
                }
            }
        
            String inReplyTo = (String)_headers.get(HEADER_IN_REPLY_TO);
            if ( (inReplyTo != null) && (inReplyTo.trim().length() > 0) )
                _postBodyBuffer.append(" <a ").append(getClass("summParent")).append(" href=\"").append(getPageURL(sanitizeTagParam(inReplyTo))).append("\">(view parent)</a>\n");
            
            _postBodyBuffer.append("</span></td></tr>\n");
            _postBodyBuffer.append("<!-- end of the post summary -->\n");
        } else {
            _postBodyBuffer.append("<!-- beginning of the post summary details -->\n");
            _postBodyBuffer.append("<tr ").append(getClass("summDetail")).append(">\n");
            _postBodyBuffer.append("<form action=\"").append(getAttachmentURLBase()).append("\">\n");
            _postBodyBuffer.append("<input type=\"hidden\" name=\"").append(ArchiveViewerBean.PARAM_BLOG);
            _postBodyBuffer.append("\" value=\"");
            if (_entry != null)
                _postBodyBuffer.append(Base64.encode(_entry.getURI().getKeyHash().getData()));
            else
                _postBodyBuffer.append("unknown");
            _postBodyBuffer.append("\" />\n");
            _postBodyBuffer.append("<input type=\"hidden\" name=\"").append(ArchiveViewerBean.PARAM_ENTRY);
            _postBodyBuffer.append("\" value=\"");
            if (_entry != null) 
                _postBodyBuffer.append(_entry.getURI().getEntryId());
            else
                _postBodyBuffer.append("unknown");
            _postBodyBuffer.append("\" />\n");
            _postBodyBuffer.append("<td colspan=\"2\" valign=\"top\" align=\"left\" ").append(getClass("summDetail")).append(" >\n");

            if ( (_entry != null) && (_entry.getAttachments() != null) && (_entry.getAttachments().length > 0) ) {
                _postBodyBuffer.append(getSpan("summDetailAttachment")).append("Attachments:</span> ");
                _postBodyBuffer.append("<select ").append(getClass("summDetailAttachmentId")).append(" name=\"").append(ArchiveViewerBean.PARAM_ATTACHMENT).append("\">\n");
                for (int i = 0; i < _entry.getAttachments().length; i++) {
                    _postBodyBuffer.append("<option value=\"").append(i).append("\">");
                    Attachment a = _entry.getAttachments()[i];
                    _postBodyBuffer.append(sanitizeString(a.getName()));
                    if ( (a.getDescription() != null) && (a.getDescription().trim().length() > 0) ) {
                        _postBodyBuffer.append(": ");
                        _postBodyBuffer.append(sanitizeString(a.getDescription()));
                    }
                    _postBodyBuffer.append(" (").append(a.getDataLength()/1024).append("KB");
                    _postBodyBuffer.append(", type ").append(sanitizeString(a.getMimeType())).append(")</option>\n");
                }
                _postBodyBuffer.append("</select>\n");
                _postBodyBuffer.append("<input ").append(getClass("summDetailAttachmentDl")).append(" type=\"submit\" value=\"Download\" name=\"Download\" /><br />\n");
            }

            if (_blogs.size() > 0) {
                _postBodyBuffer.append(getSpan("summDetailBlog")).append("Blog references:</span>");
                for (int i = 0; i < _blogs.size(); i++) {
                    Blog b = (Blog)_blogs.get(i);
                    _postBodyBuffer.append("<a ").append(getClass("summDetailBlogLink")).append(" href=\"");
                    boolean expanded = (_user != null ? _user.getShowExpanded() : false);
                    boolean images = (_user != null ? _user.getShowImages() : false);
                    _postBodyBuffer.append(getPageURL(new Hash(Base64.decode(b.hash)), b.tag, b.entryId, -1, -1, expanded, images));
                    _postBodyBuffer.append("\">").append(sanitizeString(b.name)).append("</a> ");
                }
                _postBodyBuffer.append("<br />\n");
            }

            if (_links.size() > 0) {
                _postBodyBuffer.append(getSpan("summDetailExternal")).append("External links:</span> ");
                for (int i = 0; i < _links.size(); i++) {
                    Link l = (Link)_links.get(i);
                    _postBodyBuffer.append("<a ").append(getClass("summDetailExternalLink")).append(" href=\"externallink.jsp?");
                    if (l.schema != null)
                        _postBodyBuffer.append("schema=").append(sanitizeURL(l.schema)).append('&');
                    if (l.location != null)
                        _postBodyBuffer.append("location=").append(sanitizeURL(l.location)).append('&');
                    _postBodyBuffer.append("\">").append(sanitizeString(l.location));
                    _postBodyBuffer.append(getSpan("summDetailExternalNet")).append(" (").append(sanitizeString(l.schema)).append(")</span></a> ");
                }
                _postBodyBuffer.append("<br />\n");
            }

            if (_addresses.size() > 0) {
                _postBodyBuffer.append(getSpan("summDetailAddr")).append("Addresses:</span>");
                for (int i = 0; i < _addresses.size(); i++) {
                    Address a = (Address)_addresses.get(i);
                    
                    PetName pn = null;
                    if (_user != null)
                        pn = _user.getPetNameDB().getLocation(a.location);
                    if (pn != null) {
                        _postBodyBuffer.append(' ').append(getSpan("summDetailAddrKnown"));
                        _postBodyBuffer.append(sanitizeString(pn.getName())).append("</span>");
                    } else {
                        _postBodyBuffer.append(" <a ").append(getClass("summDetailAddrLink")).append(" href=\"addresses.jsp?");
                        if (a.schema != null)
                            _postBodyBuffer.append("network=").append(sanitizeTagParam(a.schema)).append('&');
                        if (a.location != null)
                            _postBodyBuffer.append("location=").append(sanitizeTagParam(a.location)).append('&');
                        if (a.name != null)
                            _postBodyBuffer.append("name=").append(sanitizeTagParam(a.name)).append('&');
                        if (a.protocol != null)
                            _postBodyBuffer.append("protocol=").append(sanitizeTagParam(a.protocol)).append('&');
                        _postBodyBuffer.append("\">").append(sanitizeString(a.name)).append("</a>");
                    }                    
                    importAddress(a);
                }
                _postBodyBuffer.append("<br />\n");
            }

            if (_archives.size() > 0) {
                _postBodyBuffer.append(getSpan("summDetailArchive")).append("Archives:</span>");
                for (int i = 0; i < _archives.size(); i++) {
                    ArchiveRef a = (ArchiveRef)_archives.get(i);
                    _postBodyBuffer.append(" <a ").append(getClass("summDetailArchiveLink")).append(" href=\"").append(getArchiveURL(null, new SafeURL(a.locationSchema + "://" + a.location)));
                    _postBodyBuffer.append("\">").append(sanitizeString(a.name)).append("</a>");
                    if (a.description != null)
                        _postBodyBuffer.append(": ").append(getSpan("summDetailArchiveDesc")).append(sanitizeString(a.description)).append("</span>");
                    if (null == _user.getPetNameDB().getLocation(a.location)) {
                        _postBodyBuffer.append(" <a ").append(getClass("summDetailArchiveBookmark")).append(" href=\"");
                        _postBodyBuffer.append(getBookmarkURL(a.name, a.location, a.locationSchema, "syndiearchive"));
                        _postBodyBuffer.append("\">bookmark it</a>");
                    }
                }
                _postBodyBuffer.append("<br />\n");
            }

            if (_entry != null) {
                List replies = _archive.getIndex().getReplies(_entry.getURI());
                if ( (replies != null) && (replies.size() > 0) ) {
                    _postBodyBuffer.append(getSpan("summDetailReplies")).append("Replies:</span> ");
                    for (int i = 0; i < replies.size(); i++) { 
                        BlogURI reply = (BlogURI)replies.get(i);
                        _postBodyBuffer.append("<a ").append(getClass("summDetailReplyLink")).append(" href=\"");
                        _postBodyBuffer.append(getPageURL(reply.getKeyHash(), null, reply.getEntryId(), -1, -1, true, _user.getShowImages()));
                        _postBodyBuffer.append("\">");
                        _postBodyBuffer.append(getSpan("summDetailReplyAuthor"));
                        BlogInfo replyAuthor = _archive.getBlogInfo(reply);
                        if (replyAuthor != null) {
                            _postBodyBuffer.append(sanitizeString(replyAuthor.getProperty(BlogInfo.NAME)));
                        } else {
                            _postBodyBuffer.append(reply.getKeyHash().toBase64().substring(0,16));
                        }
                        _postBodyBuffer.append("</span> on ");
                        _postBodyBuffer.append(getSpan("summDetailReplyDate"));
                        _postBodyBuffer.append(getEntryDate(reply.getEntryId()));
                        _postBodyBuffer.append("</a></span> ");
                    }
                    _postBodyBuffer.append("<br />");
                }
            }
        
            String inReplyTo = (String)_headers.get(HEADER_IN_REPLY_TO);
            if ( (inReplyTo != null) && (inReplyTo.trim().length() > 0) ) {
                _postBodyBuffer.append(" <a ").append(getClass("summDetailParent")).append(" href=\"").append(getPageURL(sanitizeTagParam(inReplyTo))).append("\">(view parent)</a><br />\n");
            }
                
            _postBodyBuffer.append("</td>\n</form>\n</tr>\n");
            _postBodyBuffer.append("<!-- end of the post summary details -->\n");
        }
        _postBodyBuffer.append("</table>\n");
    }
    
    public void receiveHeader(String header, String value) { 
        //System.err.println("Receive header [" + header + "] = [" + value + "]");
        if (HEADER_PETNAME.equals(header)) {
            StringTokenizer tok = new StringTokenizer(value, "\t\n");
            if (tok.countTokens() != 4)
                return;
            String name = tok.nextToken();
            String net = tok.nextToken();
            String proto = tok.nextToken();
            String loc = tok.nextToken();
            Address a = new Address();
            a.name = sanitizeString(name, false);
            a.schema = sanitizeString(net, false);
            a.protocol = sanitizeString(proto, false);
            a.location = sanitizeString(loc, false);
            _addresses.add(a);
        } else {
            _headers.put(header, value); 
        }
    }
    
    public void receiveHeaderEnd() {
        _preBodyBuffer.append("<table ").append(getClass("overall")).append(" width=\"100%\" border=\"0\">\n");
        renderSubjectCell();
        renderMetaCell();
        renderPreBodyCell();
    }
    
    public static final String HEADER_SUBJECT = "Subject";
    public static final String HEADER_BGCOLOR = "bgcolor";
    public static final String HEADER_IN_REPLY_TO = "InReplyTo";
    public static final String HEADER_STYLE = "Style";
    public static final String HEADER_PETNAME = "PetName";
    
    private void renderSubjectCell() {
        _preBodyBuffer.append("<form action=\"index.jsp\">");
        _preBodyBuffer.append("<tr ").append(getClass("subject")).append(">");
        _preBodyBuffer.append("<td ").append(getClass("subject")).append(" align=\"left\" valign=\"top\" width=\"400\"> ");
        String subject = (String)_headers.get(HEADER_SUBJECT);
        if (subject == null)
            subject = "[no subject]";
        _preBodyBuffer.append(getSpan("subjectText")).append(sanitizeString(subject));
        _preBodyBuffer.append("</span></td>\n");
    }
    
    private void renderPreBodyCell() {
        _preBodyBuffer.append("</form>");
        String bgcolor = (String)_headers.get(HEADER_BGCOLOR);
        _preBodyBuffer.append("<tr ").append(getClass("body")).append(" >");
        _preBodyBuffer.append("<td colspan=\"2\" align=\"left\" valign=\"top\" ").append(getClass("body"));
        _preBodyBuffer.append((bgcolor != null ? " bgcolor=\"" + sanitizeTagParam(bgcolor) + "\"" : "") + ">");
    }
    
    private void renderMetaCell() {
        String tags[] = (_entry != null ? _entry.getTags() : null);
        _preBodyBuffer.append("<td nowrap=\"nowrap\" align=\"right\" valign=\"top\" ");
        _preBodyBuffer.append(getClass("meta")).append(">\n");
        
        PetName pn = null;
        if ( (_entry != null) && (_user != null) )
            pn = _user.getPetNameDB().getLocation(_entry.getURI().getKeyHash().toBase64());
        //if (knownName != null)
        //    _preBodyBuffer.append("Pet name: ").append(sanitizeString(knownName)).append(" ");

        BlogInfo info = null;
        if (_entry != null) 
            info = _archive.getBlogInfo(_entry.getURI());
        if (info != null) {
            _preBodyBuffer.append("<a ").append(getClass("metaLink")).append(" href=\"").append(getMetadataURL()).append("\">");
            if (pn != null) {
                _preBodyBuffer.append(getSpan("metaKnown")).append(sanitizeString(pn.getName())).append("</span>");
            } else {
                String nameStr = info.getProperty("Name");
                if (nameStr == null)
                    _preBodyBuffer.append(getSpan("metaUnknown")).append("[no name]</span>");
                else
                    _preBodyBuffer.append(getSpan("metaUnknown")).append(sanitizeString(nameStr)).append("</span>");
            }
            _preBodyBuffer.append("</a>");
        } else {
            _preBodyBuffer.append(getSpan("metaUnknown")).append("[unknown blog]</span>");
        }

        
        if ( (_user != null) && (_user.getAuthenticated()) && (_entry != null) ) {
            if ( (pn == null) || (!pn.isMember("Favorites")) )
                _preBodyBuffer.append(" <input ").append(getClass("bookmark")).append(" type=\"submit\" name=\"action\" value=\"Bookmark blog\" />");
            if ( (pn == null) || (!pn.isMember("Ignore")) )
                _preBodyBuffer.append(" <input ").append(getClass("ignore")).append(" type=\"submit\" name=\"action\" value=\"Ignore blog\" />");
            else
                _preBodyBuffer.append(" <input ").append(getClass("unignore")).append(" type=\"submit\" name=\"action\" value=\"Unignore blog\" />");
            _preBodyBuffer.append(" <input type=\"hidden\" name=\"blog\" value=\"").append(_entry.getURI().getKeyHash().toBase64()).append("\" />");
            if (info != null)
                _preBodyBuffer.append(" <input type=\"hidden\" name=\"name\" value=\"").append(sanitizeTagParam(info.getProperty("Name"))).append("\" />");
        }

        
        if ( (tags != null) && (tags.length > 0) ) {
            _preBodyBuffer.append(getSpan("metaTags")).append(" Tags: ");
            _preBodyBuffer.append("<select ").append(getClass("metaTagList")).append(" name=\"selector\">");
            for (int i = 0; tags != null && i < tags.length; i++) {
                _preBodyBuffer.append("<option value=\"blogtag://");
                _preBodyBuffer.append(_entry.getURI().getKeyHash().toBase64());
                _preBodyBuffer.append('/').append(Base64.encode(DataHelper.getUTF8(tags[i]))).append("\">");
                _preBodyBuffer.append(sanitizeString(tags[i]));
                _preBodyBuffer.append("</option>\n");
                /*
                _preBodyBuffer.append("<a href=\"");
                _preBodyBuffer.append(getPageURL(_entry.getURI().getKeyHash(), tags[i], -1, -1, -1, (_user != null ? _user.getShowExpanded() : false), (_user != null ? _user.getShowImages() : false)));
                _preBodyBuffer.append("\">");
                _preBodyBuffer.append(sanitizeString(tags[i]));
                _preBodyBuffer.append("</a>");
                if (i + 1 < tags.length)
                    _preBodyBuffer.append(", ");
                 */
            }
            _preBodyBuffer.append("</select>");
            _preBodyBuffer.append("<input ").append(getClass("metaTagView")).append(" type=\"submit\" value=\"View\" /></span>\n");
            //_preBodyBuffer.append("</i>");
        }
        _preBodyBuffer.append(" ");
        /*
        String inReplyTo = (String)_headers.get(HEADER_IN_REPLY_TO);
        if ( (inReplyTo != null) && (inReplyTo.trim().length() > 0) )
            _preBodyBuffer.append(" <a href=\"").append(getPageURL(sanitizeTagParam(inReplyTo))).append("\">In reply to</a>\n");
         */
        
        _preBodyBuffer.append(getSpan("metaDate"));
        if (_entry != null)
            _preBodyBuffer.append(getEntryDate(_entry.getURI().getEntryId()));
        else
            _preBodyBuffer.append(getEntryDate(new Date().getTime()));
        _preBodyBuffer.append("</span>");
        
        if ( (_user != null) && (_user.getAuthenticated()) ) {
            _preBodyBuffer.append(" <a ").append(getClass("replyLink"));
            _preBodyBuffer.append(" href=\"").append(getPostURL(_user.getBlog(), true)).append("\">Reply</a>\n");
        }
        _preBodyBuffer.append("\n</td>");
        _preBodyBuffer.append("</tr>\n");
    }
    
    private final SimpleDateFormat _dateFormat = new SimpleDateFormat("yyyy/MM/dd", Locale.UK);
    private final String getEntryDate(long when) {
        synchronized (_dateFormat) {
            try {
                String str = _dateFormat.format(new Date(when));
                long dayBegin = _dateFormat.parse(str).getTime();
                return str + " [" + (when - dayBegin) + "]";
            } catch (ParseException pe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error formatting", pe);
                // wtf
                return "unknown";
            }
        }
    }
    
    public static final String sanitizeString(String str) { return sanitizeString(str, true); }
    public static final String sanitizeString(String str, boolean allowNL) {
        if (str == null) return null;
        boolean unsafe = false;
        unsafe = unsafe || str.indexOf('<') >= 0;
        unsafe = unsafe || str.indexOf('>') >= 0;
        if (!allowNL) {
            unsafe = unsafe || str.indexOf('\n') >= 0;
            unsafe = unsafe || str.indexOf('\r') >= 0;
            unsafe = unsafe || str.indexOf('\f') >= 0;
        }
        if (!unsafe) return str;
        
        str = str.replace('<', '_'); // this should be &lt;
        str = str.replace('>', '-'); // this should be &gt;
        if (!allowNL) {
            str = str.replace('\n', ' ');
            str = str.replace('\r', ' ');
            str = str.replace('\f', ' ');
        }
        return str;
    }

    public static final String sanitizeURL(String str) { 
        if (str == null) return "";
        return Base64.encode(DataHelper.getUTF8(str)); 
    }
    public static final String sanitizeTagParam(String str) {
        if (str == null) return "";
        str = str.replace('&', '_'); // this should be &amp;
        if (str.indexOf('\"') < 0)
            return sanitizeString(str);
        str = str.replace('\"', '\'');
        return sanitizeString(str);
    }
    
    public static final String sanitizeXML(String orig) {
        if (orig == null) return "";
        if (orig.indexOf('&') < 0) return orig;
        StringBuffer rv = new StringBuffer(orig.length()+32);
        for (int i = 0; i < orig.length(); i++) {
            if (orig.charAt(i) == '&')
                rv.append("&amp;");
            else
                rv.append(orig.charAt(i));
        }
        return rv.toString();
    }
    public static final String sanitizeXML(StringBuffer orig) {
        if (orig == null) return "";
        if (orig.indexOf("&") < 0) return orig.toString();
        for (int i = 0; i < orig.length(); i++) {
            if (orig.charAt(i) == '&') {
                orig = orig.replace(i, i+1, "&amp;");
                i += "&amp;".length();
            }
        }
        return orig.toString();
    }

    private static final String STYLE_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_";
    public static String sanitizeStyle(String style) {
        if ( (style == null) || (style.trim().length() <= 0) ) return null;
        char c[] = style.toCharArray();
        for (int i = 0; i < c.length; i++)
            if (STYLE_CHARS.indexOf(c[i]) < 0)
                c[i] = '_';
        return new String(c);
    }
        
    protected String getEntryURL() { return getEntryURL(_user != null ? _user.getShowImages() : false); }
    protected String getEntryURL(boolean showImages) {
        if (_entry == null) return "unknown";
        return "index.jsp?" + ArchiveViewerBean.PARAM_BLOG + "=" +
               Base64.encode(_entry.getURI().getKeyHash().getData()) +
               "&" + ArchiveViewerBean.PARAM_ENTRY + "=" + _entry.getURI().getEntryId() +
               "&" + ArchiveViewerBean.PARAM_SHOW_IMAGES + (showImages ? "=true" : "=false") +
               "&" + ArchiveViewerBean.PARAM_EXPAND_ENTRIES + "=true";
    }

    protected String getAttachmentURLBase() { return "viewattachment.jsp"; }
    protected String getAttachmentURL(int id) {
        if (_entry == null) return "unknown";
        return getAttachmentURLBase() + "?" + 
               ArchiveViewerBean.PARAM_BLOG + "=" +
               Base64.encode(_entry.getURI().getKeyHash().getData()) +
               "&" + ArchiveViewerBean.PARAM_ENTRY + "=" + _entry.getURI().getEntryId() +
               "&" + ArchiveViewerBean.PARAM_ATTACHMENT + "=" + id;
    }

    public String getMetadataURL() { 
        if (_entry == null) return "unknown";
        return getMetadataURL(_entry.getURI().getKeyHash()); 
    }
    public static String getMetadataURL(Hash blog) {
        return "viewmetadata.jsp?" + ArchiveViewerBean.PARAM_BLOG + "=" +
               Base64.encode(blog.getData());
    }

    public static String getPostURL(Hash blog) {
        return "post.jsp?" + ArchiveViewerBean.PARAM_BLOG + "=" + Base64.encode(blog.getData());
    }
    public String getPostURL(Hash blog, boolean asReply) { 
        if (asReply && _entry != null) {
            return "post.jsp?" + ArchiveViewerBean.PARAM_BLOG + "=" + Base64.encode(blog.getData())
                   + "&" + ArchiveViewerBean.PARAM_IN_REPLY_TO + '=' 
                   + Base64.encode("entry://" + _entry.getURI().getKeyHash().toBase64() + "/" + _entry.getURI().getEntryId());
        } else {
            return getPostURL(blog);
        }
    }

    public String getPageURL(String selector) { return getPageURL(_user, selector); }
    public static String getPageURL(User user, String selector) { return getPageURL(user, selector, -1, -1); }
    public static String getPageURL(User user, String selector, int numPerPage, int pageNum) {
        StringBuffer buf = new StringBuffer(128);
        buf.append("index.jsp?");
        buf.append("selector=").append(sanitizeTagParam(selector)).append("&");
        if ( (pageNum >= 0) && (numPerPage > 0) ) {
            buf.append(ArchiveViewerBean.PARAM_PAGE_NUMBER).append('=').append(pageNum).append('&');
            buf.append(ArchiveViewerBean.PARAM_NUM_PER_PAGE).append('=').append(numPerPage).append('&');
        }
        buf.append(ArchiveViewerBean.PARAM_EXPAND_ENTRIES).append('=').append(user.getShowExpanded()).append('&');
        buf.append(ArchiveViewerBean.PARAM_SHOW_IMAGES).append('=').append(user.getShowImages()).append('&');
        return buf.toString();
    }
    
    public static String getPageURL(Hash blog, String tag, long entryId, int numPerPage, int pageNum, boolean expandEntries, boolean showImages) {
        return getPageURL(blog, tag, entryId, null, numPerPage, pageNum, expandEntries, showImages);
    }
    public static String getPageURL(Hash blog, String tag, long entryId, String group, int numPerPage, int pageNum, boolean expandEntries, boolean showImages) {
        StringBuffer buf = new StringBuffer(128);
        buf.append("index.jsp?");
        if (blog != null)
            buf.append(ArchiveViewerBean.PARAM_BLOG).append('=').append(Base64.encode(blog.getData())).append('&');
        if (tag != null)
            buf.append(ArchiveViewerBean.PARAM_TAG).append('=').append(Base64.encode(DataHelper.getUTF8(tag))).append('&');
        if (entryId >= 0)
            buf.append(ArchiveViewerBean.PARAM_ENTRY).append('=').append(entryId).append('&');
        if (group != null)
            buf.append(ArchiveViewerBean.PARAM_GROUP).append('=').append(Base64.encode(DataHelper.getUTF8(group))).append('&');
        if ( (pageNum >= 0) && (numPerPage > 0) ) {
            buf.append(ArchiveViewerBean.PARAM_PAGE_NUMBER).append('=').append(pageNum).append('&');
            buf.append(ArchiveViewerBean.PARAM_NUM_PER_PAGE).append('=').append(numPerPage).append('&');
        }
        buf.append(ArchiveViewerBean.PARAM_EXPAND_ENTRIES).append('=').append(expandEntries).append('&');
        buf.append(ArchiveViewerBean.PARAM_SHOW_IMAGES).append('=').append(showImages).append('&');
        return buf.toString();
    }
    public static String getArchiveURL(Hash blog, SafeURL archiveLocation) {
        return "remote.jsp?" 
               //+ "action=Continue..." // should this be the case?
               + "&schema=" + sanitizeTagParam(archiveLocation.getSchema()) 
               + "&location=" + sanitizeTagParam(archiveLocation.getLocation());
    }
    public static String getBookmarkURL(String name, String location, String schema, String protocol) {
        return "addresses.jsp?name=" + sanitizeTagParam(name)
               + "&network=" + sanitizeTagParam(schema)
               + "&protocol=" + sanitizeTagParam(protocol)
               + "&location=" + sanitizeTagParam(location);
               
    }
}
