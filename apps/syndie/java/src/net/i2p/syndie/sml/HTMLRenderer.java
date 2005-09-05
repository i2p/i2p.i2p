package net.i2p.syndie.sml;

import java.io.*;
import java.text.*;
import java.util.*;
import net.i2p.data.*;
import net.i2p.syndie.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.web.*;

/**
 *
 */
public class HTMLRenderer extends EventReceiverImpl {
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
    
    public HTMLRenderer() {
        _parser = new SMLParser();
    }

    /**
     * Usage: HTMLRenderer smlFile outputFile
     */
    public static void main(String args[]) {
        if (args.length != 2) {
            System.err.println("Usage: HTMLRenderer smlFile outputFile");
            return;
        }
        HTMLRenderer renderer = new HTMLRenderer();
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
    
    public void renderUnknownEntry(User user, Archive archive, BlogURI uri, Writer out) throws IOException {
        BlogInfo info = archive.getBlogInfo(uri);
        if (info == null)
            out.write("<br />The blog " + uri.getKeyHash().toBase64() + " is not known locally.  "
                      + "Please get it from an archive and <a href=\"" 
                      + getPageURL(uri.getKeyHash(), null, uri.getEntryId(), -1, -1, user.getShowExpanded(), user.getShowImages())
                      + "\">try again</a>");
        else
            out.write("<br />The blog <a href=\""
                      + getPageURL(uri.getKeyHash(), null, -1, -1, -1, user.getShowExpanded(), user.getShowImages())
                      + "\">" + info.getProperty(BlogInfo.NAME) + "</a> is known, but the entry " + uri.getEntryId() + " is not.  "
                      + "Please get it from an archive and <a href=\"" 
                      + getPageURL(uri.getKeyHash(), null, uri.getEntryId(), -1, -1, user.getShowExpanded(), user.getShowImages())
                      + "\">try again</a>");
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
        _bodyBuffer.append("<b>").append(sanitizeString(text)).append("</b>");
    }
    public void receiveItalic(String text) { 
        if (!continueBody()) { return; }
        _bodyBuffer.append("<i>").append(sanitizeString(text)).append("</i>");
    }
    public void receiveUnderline(String text) { 
        if (!continueBody()) { return; }
        _bodyBuffer.append("<u>").append(sanitizeString(text)).append("</u>");
    }
    public void receiveHR() {
        if (!continueBody()) { return; }
        _bodyBuffer.append("<hr />");
    }
    public void receiveH1(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append("<h1>").append(sanitizeString(body)).append("</h1>");
    }
    public void receiveH2(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append("<h2>").append(sanitizeString(body)).append("</h2>");
    }
    public void receiveH3(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append("<h3>").append(sanitizeString(body)).append("</h3>");
    }
    public void receiveH4(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append("<h4>").append(sanitizeString(body)).append("</h4>");
    }
    public void receiveH5(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append("<h5>").append(sanitizeString(body)).append("</h5>");
    }
    public void receivePre(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append("<pre>").append(sanitizeString(body)).append("</pre>");
    }
    
    public void receiveQuote(String text, String whoQuoted, String quoteLocationSchema, String quoteLocation) {
        if (!continueBody()) { return; }
        _bodyBuffer.append("<quote>").append(sanitizeString(text)).append("</quote>");
    }
    public void receiveCode(String text, String codeLocationSchema, String codeLocation) { 
        if (!continueBody()) { return; }
           _bodyBuffer.append("<code>").append(sanitizeString(text)).append("</code>");
    }
    public void receiveImage(String alternateText, int attachmentId) {
        if (!continueBody()) { return; }
        if (_showImages) {
            _bodyBuffer.append("<img src=\"").append(getAttachmentURL(attachmentId)).append("\"");
            if (alternateText != null)
                _bodyBuffer.append(" alt=\"").append(sanitizeTagParam(alternateText)).append("\"");
            _bodyBuffer.append(" />");
        } else {
            _bodyBuffer.append("[image: attachment ").append(attachmentId);
            _bodyBuffer.append(": ").append(sanitizeString(alternateText));
            _bodyBuffer.append(" <a href=\"").append(getEntryURL(true)).append("\">view images</a>]");
        }
    }
    
    public void receiveCut(String summaryText) { 
        if (!continueBody()) { return; }
        _cutReached = true;
        if (_cutBody) {
            _bodyBuffer.append("<a href=\"").append(getEntryURL()).append("\">");
            if ( (summaryText != null) && (summaryText.length() > 0) )
                _bodyBuffer.append(sanitizeString(summaryText));
            else
                _bodyBuffer.append("more inside...");
            _bodyBuffer.append("</a>\n");
        } else {
            if (summaryText != null)
                _bodyBuffer.append(sanitizeString(summaryText));
        }
    }
    
    /** are we either before the cut or rendering without cutting? */
    protected boolean continueBody() {
        boolean rv = ( (!_cutReached) && (_bodyBuffer.length() <= _cutSize) ) || (!_cutBody);
        //if (!rv) 
        //    System.out.println("rv: " + rv + " Cut reached: " + _cutReached + " bodyBufferSize: " + _bodyBuffer.length() + " cutBody? " + _cutBody);
        if (!rv && !_cutReached) {
            // exceeded the allowed size
            _bodyBuffer.append("<a href=\"").append(getEntryURL()).append("\">more inside...</a>");
            _cutReached = true;
        }
        return rv;
    }
    
    public void receiveNewline() { 
        if (!continueBody()) { return; }
        if (true || (_lastNewlineAt >= _bodyBuffer.length()))
            _bodyBuffer.append("<br />\n");
        else
            _lastNewlineAt = _bodyBuffer.length();
    }
    public void receiveLT() { 
        if (!continueBody()) { return; }
        _bodyBuffer.append("&lt;");
    }
    public void receiveGT() { 
        if (!continueBody()) { return; }
        _bodyBuffer.append("&gt;");
    }
    public void receiveBegin() {}
    public void receiveLeftBracket() { 
        if (!continueBody()) { return; }
        _bodyBuffer.append('[');
    }
    public void receiveRightBracket() { 
        if (!continueBody()) { return; }
        _bodyBuffer.append(']');
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
        System.out.println("Receiving the blog: " + name + "/" + hash + "/" + tag + "/" + entryId +"/" + locations + ": "+ description);
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
            _bodyBuffer.append("<a href=\"").append(pageURL).append("\">");
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
        _bodyBuffer.append(" [<a href=\"").append(url);
        _bodyBuffer.append("\">");
        if ( (name != null) && (name.trim().length() > 0) )
            _bodyBuffer.append(sanitizeString(name));
        else
            _bodyBuffer.append("view");
        _bodyBuffer.append("</a> (<a href=\"").append(getMetadataURL(blog)).append("\">meta</a>)");
        if ( (tag != null) && (tag.trim().length() > 0) ) {
            url = getPageURL(blog, tag, -1, -1, -1, false, false);
            _bodyBuffer.append(" <a href=\"").append(url);
            _bodyBuffer.append("\">Tag: ").append(sanitizeString(tag)).append("</a>");
        }
        if ( (locations != null) && (locations.size() > 0) ) {
            _bodyBuffer.append(" Archives: ");
            for (int i = 0; i < locations.size(); i++) {
                SafeURL surl = (SafeURL)locations.get(i);
                if (_user.getAuthenticated() && _user.getAllowAccessRemote())
                    _bodyBuffer.append("<a href=\"").append(getArchiveURL(blog, surl)).append("\">").append(sanitizeString(surl.toString())).append("</a> ");
                else
                    _bodyBuffer.append(sanitizeString(surl.toString())).append(' ');
            }
        }
        _bodyBuffer.append("] ");
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
        
        _bodyBuffer.append(sanitizeString(anchorText)).append(" [Archive ");
        if (name != null)
            _bodyBuffer.append(sanitizeString(name));
        if (location != null) {
            _bodyBuffer.append(" at ");
            SafeURL surl = new SafeURL(locationSchema + "://" + location);
            _bodyBuffer.append("<a href=\"").append(getArchiveURL(null, surl));
            _bodyBuffer.append("\">").append(sanitizeString(surl.toString())).append("</a>");
        }
        if (description != null)
            _bodyBuffer.append(": ").append(sanitizeString(description));
        _bodyBuffer.append("]");
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
        _bodyBuffer.append("<a href=\"externallink.jsp?schema=");
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
        String knownName = null;
        if (_user != null)
            knownName = _user.getPetNameDB().getNameByLocation(location);
        if (knownName != null) {
            _bodyBuffer.append(sanitizeString(anchorText));
            _bodyBuffer.append(" <i>(").append(sanitizeString(knownName)).append(")</i>");
        } else {
            System.err.println("Receiving address [" + location + "]");
            _bodyBuffer.append("<a href=\"addaddress.jsp?schema=");
            _bodyBuffer.append(sanitizeURL(schema)).append("&name=");
            _bodyBuffer.append(sanitizeURL(name)).append("&protocol=");
            _bodyBuffer.append(sanitizeURL(protocol)).append("&location=");
            _bodyBuffer.append(sanitizeURL(location)).append("\">").append(sanitizeString(anchorText)).append("</a>");
        }
    }
    
    public void receiveAttachment(int id, String anchorText) {
        if (!continueBody()) { return; }
        Attachment attachments[] = _entry.getAttachments();
        if ( (id < 0) || (id >= attachments.length)) {
            _bodyBuffer.append(sanitizeString(anchorText));
        } else {
            _bodyBuffer.append("<a href=\"").append(getAttachmentURL(id)).append("\">");
            _bodyBuffer.append(sanitizeString(anchorText)).append("</a>");
            _bodyBuffer.append(" (").append(attachments[id].getDataLength()/1024).append("KB, ");
            _bodyBuffer.append(" \"").append(sanitizeString(attachments[id].getName())).append("\", ");
            _bodyBuffer.append(sanitizeString(attachments[id].getMimeType())).append(")");
        }
    }
    
    public void receiveEnd() { 
        _postBodyBuffer.append("</td></tr>\n");
        if (_cutBody) {
            _postBodyBuffer.append("<tr class=\"syndieEntryAttachmentsCell\">\n");
            _postBodyBuffer.append("<td colspan=\"2\" valign=\"top\" align=\"left\" class=\"syndieEntryAttachmentsCell\">");
            _postBodyBuffer.append("<a href=\"").append(getEntryURL()).append("\">View details...</a> ");
            
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
                _postBodyBuffer.append(links).append(" external links");

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
                _postBodyBuffer.append(" <a href=\"").append(getPageURL(sanitizeTagParam(inReplyTo))).append("\">(view parent)</a>\n");
            
            _postBodyBuffer.append("</td></tr>\n");
        } else {
            _postBodyBuffer.append("<tr class=\"syndieEntryAttachmentsCell\">\n");
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
            _postBodyBuffer.append("<td colspan=\"2\" valign=\"top\" align=\"left\" class=\"syndieEntryAttachmentsCell\">\n");

            if ( (_entry != null) && (_entry.getAttachments() != null) && (_entry.getAttachments().length > 0) ) {
                _postBodyBuffer.append("<b>Attachments:</b> ");
                _postBodyBuffer.append("<select name=\"").append(ArchiveViewerBean.PARAM_ATTACHMENT).append("\">\n");
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
                _postBodyBuffer.append("<input type=\"submit\" value=\"Download\" name=\"Download\" /><br />\n");
            }

            if (_blogs.size() > 0) {
                _postBodyBuffer.append("<b>Blog references:</b> ");
                for (int i = 0; i < _blogs.size(); i++) {
                    Blog b = (Blog)_blogs.get(i);
                    _postBodyBuffer.append("<a href=\"").append(getPageURL(new Hash(Base64.decode(b.hash)), b.tag, b.entryId, -1, -1, (_user != null ? _user.getShowExpanded() : false), (_user != null ? _user.getShowImages() : false)));
                    _postBodyBuffer.append("\">").append(sanitizeString(b.name)).append("</a> ");
                }
                _postBodyBuffer.append("<br />\n");
            }

            if (_links.size() > 0) {
                _postBodyBuffer.append("<b>External links:</b> ");
                for (int i = 0; i < _links.size(); i++) {
                    Link l = (Link)_links.get(i);
                    _postBodyBuffer.append("<a href=\"externallink.jsp?schema=");
                    _postBodyBuffer.append(sanitizeURL(l.schema)).append("&location=");
                    _postBodyBuffer.append(sanitizeURL(l.location));
                    _postBodyBuffer.append("\">").append(sanitizeString(l.location));
                    _postBodyBuffer.append(" (").append(sanitizeString(l.schema)).append(")</a> ");
                }
                _postBodyBuffer.append("<br />\n");
            }

            if (_addresses.size() > 0) {
                _postBodyBuffer.append("<b>Addresses:</b>");
                for (int i = 0; i < _addresses.size(); i++) {
                    Address a = (Address)_addresses.get(i);
                    
                    String knownName = null;
                    if (_user != null)
                        knownName = _user.getPetNameDB().getNameByLocation(a.location);
                    if (knownName != null) {
                        _postBodyBuffer.append(' ').append(sanitizeString(knownName));
                    } else {
                        _postBodyBuffer.append(" <a href=\"addaddress.jsp?schema=");
                        _postBodyBuffer.append(sanitizeURL(a.schema)).append("&location=");
                        _postBodyBuffer.append(sanitizeURL(a.location)).append("&name=");
                        _postBodyBuffer.append(sanitizeURL(a.name)).append("&protocol=");
                        _postBodyBuffer.append(sanitizeURL(a.protocol));
                        _postBodyBuffer.append("\">").append(sanitizeString(a.name));
                    }
                }
                _postBodyBuffer.append("<br />\n");
            }

            if (_archives.size() > 0) {
                _postBodyBuffer.append("<b>Archives:</b>");
                for (int i = 0; i < _archives.size(); i++) {
                    ArchiveRef a = (ArchiveRef)_archives.get(i);
                    _postBodyBuffer.append(" <a href=\"").append(getArchiveURL(null, new SafeURL(a.locationSchema + "://" + a.location)));
                    _postBodyBuffer.append("\">").append(sanitizeString(a.name)).append("</a>");
                    if (a.description != null)
                        _postBodyBuffer.append(": ").append(sanitizeString(a.description));
                    _postBodyBuffer.append(" <a href=\"");
                    _postBodyBuffer.append(getBookmarkURL(a.name, a.location, a.locationSchema, "syndiearchive"));
                    _postBodyBuffer.append("\">bookmark</a>");
                }
                _postBodyBuffer.append("<br />\n");
            }

            if (_entry != null) {
                List replies = _archive.getIndex().getReplies(_entry.getURI());
                if ( (replies != null) && (replies.size() > 0) ) {
                    _postBodyBuffer.append("<b>Replies:</b> ");
                    for (int i = 0; i < replies.size(); i++) { 
                        BlogURI reply = (BlogURI)replies.get(i);
                        _postBodyBuffer.append("<a href=\"");
                        _postBodyBuffer.append(getPageURL(reply.getKeyHash(), null, reply.getEntryId(), -1, -1, true, _user.getShowImages()));
                        _postBodyBuffer.append("\">");
                        BlogInfo replyAuthor = _archive.getBlogInfo(reply);
                        if (replyAuthor != null) {
                            _postBodyBuffer.append(sanitizeString(replyAuthor.getProperty(BlogInfo.NAME)));
                        } else {
                            _postBodyBuffer.append(reply.getKeyHash().toBase64().substring(0,16));
                        }
                        _postBodyBuffer.append(" on ");
                        _postBodyBuffer.append(getEntryDate(reply.getEntryId()));
                        _postBodyBuffer.append("</a> ");
                    }
                    _postBodyBuffer.append("<br />");
                }
            }
        
            String inReplyTo = (String)_headers.get(HEADER_IN_REPLY_TO);
            if ( (inReplyTo != null) && (inReplyTo.trim().length() > 0) ) {
                _postBodyBuffer.append(" <a href=\"").append(getPageURL(sanitizeTagParam(inReplyTo))).append("\">(view parent)</a><br />\n");
            }
                
            _postBodyBuffer.append("</td>\n</form>\n</tr>\n");
        }
        _postBodyBuffer.append("</table>\n");
    }
    
    public void receiveHeader(String header, String value) { 
        //System.err.println("Receive header [" + header + "] = [" + value + "]");
        _headers.put(header, value); 
    }
    
    public void receiveHeaderEnd() {
        _preBodyBuffer.append("<table width=\"100%\" border=\"0\">\n");
        renderSubjectCell();
        renderMetaCell();
        renderPreBodyCell();
    }
    
    public static final String HEADER_SUBJECT = "Subject";
    public static final String HEADER_BGCOLOR = "bgcolor";
    public static final String HEADER_IN_REPLY_TO = "InReplyTo";
    
    private void renderSubjectCell() {
        _preBodyBuffer.append("<tr class=\"syndieEntrySubjectCell\"><td align=\"left\" valign=\"top\" class=\"syndieEntrySubjectCell\" width=\"400\"> ");
        String subject = (String)_headers.get(HEADER_SUBJECT);
        if (subject == null)
            subject = "[no subject]";
        _preBodyBuffer.append(sanitizeString(subject));
        _preBodyBuffer.append("</td>\n");
    }
    
    private void renderPreBodyCell() {
        String bgcolor = (String)_headers.get(HEADER_BGCOLOR);
        if (_cutBody)
            _preBodyBuffer.append("<tr class=\"syndieEntrySummaryCell\"><td colspan=\"2\" align=\"left\" valign=\"top\" class=\"syndieEntrySummaryCell\" " + (bgcolor != null ? "bgcolor=\"" + sanitizeTagParam(bgcolor) + "\"" : "") + "\">");
        else
            _preBodyBuffer.append("<tr class=\"syndieEntryBodyCell\"><td colspan=\"2\" align=\"left\" valign=\"top\" class=\"syndieEntryBodyCell\" " + (bgcolor != null ? "bgcolor=\"" + sanitizeTagParam(bgcolor) + "\"" : "") + "\">");
    }
    
    private void renderMetaCell() {
        String tags[] = (_entry != null ? _entry.getTags() : null);
        if ( (tags != null) && (tags.length > 0) )
            _preBodyBuffer.append("<form action=\"index.jsp\">");
        _preBodyBuffer.append("<td nowrap=\"true\" align=\"right\" valign=\"top\" class=\"syndieEntryMetaCell\">\n");
        
        String knownName = null;
        if ( (_entry != null) && (_user != null) )
            knownName = _user.getPetNameDB().getNameByLocation(_entry.getURI().getKeyHash().toBase64());
        //if (knownName != null)
        //    _preBodyBuffer.append("Pet name: ").append(sanitizeString(knownName)).append(" ");

        BlogInfo info = null;
        if (_entry != null) 
            info = _archive.getBlogInfo(_entry.getURI());
        if (info != null) {
            _preBodyBuffer.append("<a href=\"").append(getMetadataURL()).append("\">");
            if (knownName != null) {
                _preBodyBuffer.append(sanitizeString(knownName));
            } else {
                String nameStr = info.getProperty("Name");
                if (nameStr == null)
                    _preBodyBuffer.append("[no name]");
                else
                    _preBodyBuffer.append(sanitizeString(nameStr));
            }
            _preBodyBuffer.append("</a>");
        } else {
            _preBodyBuffer.append("[unknown blog]");
        }

        
        if ( (_user != null) && (_user.getAuthenticated()) && (_entry != null) ) {
            PetName pn = _user.getPetNameDB().get(knownName);
            if ( (pn == null) || (!pn.isMember("Favorites")) )
                _preBodyBuffer.append(" <input type=\"submit\" name=\"action\" value=\"Bookmark blog\" />");
            if ( (pn == null) || (!pn.isMember("Ignore")) )
                _preBodyBuffer.append(" <input type=\"submit\" name=\"action\" value=\"Ignore blog\" />");
            else
                _preBodyBuffer.append(" <input type=\"submit\" name=\"action\" value=\"Unignore blog\" />");
            _preBodyBuffer.append(" <input type=\"hidden\" name=\"blog\" value=\"").append(_entry.getURI().getKeyHash().toBase64()).append("\" />");
            if (info != null)
                _preBodyBuffer.append(" <input type=\"hidden\" name=\"name\" value=\"").append(sanitizeTagParam(info.getProperty("Name"))).append("\" />");
        }

        
        if ( (tags != null) && (tags.length > 0) ) {
            _preBodyBuffer.append(" Tags: ");
            _preBodyBuffer.append("<select name=\"selector\">");
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
            _preBodyBuffer.append("<input type=\"submit\" value=\"View\" />\n");
            //_preBodyBuffer.append("</i>");
        }
        _preBodyBuffer.append(" ");
        /*
        String inReplyTo = (String)_headers.get(HEADER_IN_REPLY_TO);
        if ( (inReplyTo != null) && (inReplyTo.trim().length() > 0) )
            _preBodyBuffer.append(" <a href=\"").append(getPageURL(sanitizeTagParam(inReplyTo))).append("\">In reply to</a>\n");
         */
        
        if (_entry != null)
            _preBodyBuffer.append(getEntryDate(_entry.getURI().getEntryId()));
        else
            _preBodyBuffer.append(getEntryDate(new Date().getTime()));
        if ( (_user != null) && (_user.getAuthenticated()) ) {
            _preBodyBuffer.append(" <a href=\"").append(getPostURL(_user.getBlog(), true)).append("\">Reply</a>\n");
        }
        _preBodyBuffer.append("\n</td>");
        if ( (tags != null) && (tags.length > 0) )
            _preBodyBuffer.append("</form>");
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
                pe.printStackTrace();
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

    public static final String sanitizeURL(String str) { return Base64.encode(DataHelper.getUTF8(str)); }
    public static final String sanitizeTagParam(String str) {
        str = str.replace('&', '_'); // this should be &amp;
        if (str.indexOf('\"') < 0)
            return sanitizeString(str);
        str = str.replace('\"', '\'');
        return sanitizeString(str);
    }
    
    public static final String sanitizeXML(String orig) {
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
        if (orig.indexOf("&") < 0) return orig.toString();
        for (int i = 0; i < orig.length(); i++) {
            if (orig.charAt(i) == '&') {
                orig = orig.replace(i, i+1, "&amp;");
                i += "&amp;".length();
            }
        }
        return orig.toString();
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
