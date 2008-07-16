package net.i2p.syndie.sml;

import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.PetName;
import net.i2p.data.Base64;
import net.i2p.data.Hash;
import net.i2p.syndie.Archive;
import net.i2p.syndie.User;
import net.i2p.syndie.data.Attachment;
import net.i2p.syndie.data.BlogInfo;
import net.i2p.syndie.data.EntryContainer;

/**
 *
 */
public class RSSRenderer extends HTMLRenderer {
    
    public RSSRenderer(I2PAppContext ctx) {
        super(ctx);
    }
    
    private static final boolean RSS_EXCERPT_ONLY = false;
    
    public void render(User user, Archive archive, EntryContainer entry, String urlPrefix, Writer out) throws IOException {
        if (entry == null) return;
        prepare(user, archive, entry, entry.getEntry().getText(), out, RSS_EXCERPT_ONLY, false);
        BlogInfo info = archive.getBlogInfo(entry.getURI());
        
        out.write("   <item>\n");
        String subject = sanitizeXML(sanitizeString((String)_headers.get(HEADER_SUBJECT)));
        if ( (subject == null) || (subject.length() <= 0) )
            subject = "not specified";
        out.write("    <title>" + subject + "</title>\n");
        out.write("    <link>" + urlPrefix + BlogRenderer.getEntryURL(entry, info, true) + "</link>\n");
        out.write("    <guid isPermalink=\"false\">syndie://" + entry.getURI().toString() + "</guid>\n");
        out.write("    <pubDate>" + getRFC822Date(entry.getURI().getEntryId()) + "</pubDate>\n");
        PetName pn = user.getPetNameDB().getByLocation(entry.getURI().getKeyHash().toBase64());
        String author = null;
        if (pn != null)
            author = pn.getName();
        if (author == null) {
            if (info != null)
                author = info.getProperty(BlogInfo.NAME);
        }
        if (author == null)
            author = entry.getURI().getKeyHash().toBase64();
        out.write("    <author>" + sanitizeXML(sanitizeString(author)) + "@syndie.invalid</author>\n");
        String tags[] = entry.getTags();
        if (tags != null) 
            for (int i = 0; i < tags.length; i++) 
                out.write("    <category>" + sanitizeXML(sanitizeString(tags[i])) + "</category>\n");
        
        out.write("    <description>" + sanitizeXML(_bodyBuffer.toString()) + "</description>\n");

        renderEnclosures(user, entry, urlPrefix, out);
        
        out.write("   </item>\n");
    }
    
    
    public void receiveBold(String text) { 
        if (!continueBody()) { return; }
        _bodyBuffer.append(sanitizeString(text));
    }
    public void receiveItalic(String text) { 
        if (!continueBody()) { return; }
        _bodyBuffer.append(sanitizeString(text));
    }
    public void receiveUnderline(String text) { 
        if (!continueBody()) { return; }
        _bodyBuffer.append(sanitizeString(text));
    }
    public void receiveHR() {
        if (!continueBody()) { return; }
    }
    public void receiveH1(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append(sanitizeString(body));
    }
    public void receiveH2(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append(sanitizeString(body));
    }
    public void receiveH3(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append(sanitizeString(body));
    }
    public void receiveH4(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append(sanitizeString(body));
    }
    public void receiveH5(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append(sanitizeString(body));
    }
    public void receivePre(String body) {
        if (!continueBody()) { return; }
        _bodyBuffer.append(sanitizeString(body));
    }
    
    public void receiveQuote(String text, String whoQuoted, String quoteLocationSchema, String quoteLocation) {
        if (!continueBody()) { return; }
        _bodyBuffer.append(sanitizeString(text));
    }
    public void receiveCode(String text, String codeLocationSchema, String codeLocation) { 
        if (!continueBody()) { return; }
           _bodyBuffer.append(sanitizeString(text));
    }
    public void receiveImage(String alternateText, int attachmentId) {
        if (!continueBody()) { return; }
        _bodyBuffer.append(sanitizeString(alternateText));
    }
    public void receiveCut(String summaryText) { 
        if (!continueBody()) { return; }
        _cutReached = true;
        if (_cutBody) {
            if ( (summaryText != null) && (summaryText.length() > 0) )
                _bodyBuffer.append(sanitizeString(summaryText));
            else
                _bodyBuffer.append("more inside...");
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
            _bodyBuffer.append("more inside...");
            _cutReached = true;
        }
        return rv;
    }
    public void receiveNewline() { 
        if (!continueBody()) { return; }
        if (true || (_lastNewlineAt >= _bodyBuffer.length()))
            _bodyBuffer.append("\n");
        else
            _lastNewlineAt = _bodyBuffer.length();
    }
    public void receiveBlog(String name, String hash, String tag, long entryId, List locations, String description) {
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
        if ( (description != null) && (description.trim().length() > 0) ) {
            _bodyBuffer.append(sanitizeString(description));
        } else if ( (name != null) && (name.trim().length() > 0) ) {
            _bodyBuffer.append(sanitizeTagParam(name));
        } else {
            _bodyBuffer.append("[view entry]");
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
        
        _bodyBuffer.append(sanitizeString(anchorText));
    }
    public void receiveLink(String schema, String location, String text) {
        Link l = new Link();
        l.schema = schema;
        l.location = location;
        if (!_links.contains(l))
            _links.add(l);
        if (!continueBody()) { return; }
        if ( (schema == null) || (location == null) ) return;
        _bodyBuffer.append(sanitizeString(text));
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
            pn = _user.getPetNameDB().getByLocation(location);
        if (pn != null) {
            _bodyBuffer.append(sanitizeString(anchorText));
        } else {
            _bodyBuffer.append(sanitizeString(anchorText));
        }
    }
    public void receiveAttachment(int id, int thumb, String anchorText) {
        if (!continueBody()) { return; }
        _bodyBuffer.append(sanitizeString(anchorText));
    }
    
    // Mon, 03 Jun 2005 13:04:11 +0000
    private static final SimpleDateFormat _rfc822Date = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z");
    private static final String getRFC822Date(long when) {
        synchronized (_rfc822Date) {
            return _rfc822Date.format(new Date(when));
        }
    }
    
    private void renderEnclosures(User user, EntryContainer entry, String urlPrefix, Writer out) throws IOException {
        int included = 0;
        if (entry.getAttachments() != null) {
            for (int i = 0; i < _entry.getAttachments().length; i++) {
                Attachment a = _entry.getAttachments()[i];
                String url = urlPrefix + sanitizeXML(getAttachmentURL(i)) 
                             + "#" + sanitizeTagParam(a.getName()); // tacked on for readability
                out.write("    <media:content url=\"" + url
                               + "\" fileSize=\"" + a.getDataLength()
                               + "\" type=\"" + sanitizeTagParam(a.getMimeType()) 
                               + "\">");
                // we can do neat stuff with Media RSS (http://search.yahoo.com/mrss) here, such as
                // include descriptions, titles, keywords, thumbnails, etc
                out.write("    </media:content>\n");

                if (included == 0) // plain RSS enclosures can only have one enclosure per entry, unlike Media RSS
                    out.write("    <enclosure url=\"" + url
                                   + "\" length=\"" + a.getDataLength() 
                                   + "\" type=\"" + sanitizeTagParam(a.getMimeType()) + "\" syndietype=\"attachment\" />\n");
                included++;
            }
        }

        /*
        if (_blogs.size() > 0) {
            for (int i = 0; i < _blogs.size(); i++) {
                Blog b = (Blog)_blogs.get(i);
                out.write("    <enclosure url=\"" + urlPrefix + 
                               sanitizeXML(getPageURL(new Hash(Base64.decode(b.hash)), b.tag, b.entryId, 
                                          -1, -1, (_user != null ? _user.getShowExpanded() : false), 
                                          (_user != null ? _user.getShowImages() : false)))
                               + "\" length=\"1\" type=\"text/html\" syndietype=\"blog\" />\n");
            }
        }

        if (_links.size() > 0) {
            for (int i = 0; i < _links.size(); i++) {
                Link l = (Link)_links.get(i);
                StringBuffer url = new StringBuffer(128);
                url.append("externallink.jsp?schema=");
                url.append(sanitizeURL(l.schema)).append("&location=");
                url.append(sanitizeURL(l.location));
                out.write("    <enclosure url=\"" + urlPrefix + sanitizeXML(url) + "\" length=\"1\" type=\"text/html\" syndietype=\"link\" />\n");
            }
        }

        if (_addresses.size() > 0) {
            for (int i = 0; i < _addresses.size(); i++) {
                Address a = (Address)_addresses.get(i);

                PetName pn = null;
                if (_user != null)
                    pn = _user.getPetNameDB().getByLocation(a.location);
                if (pn == null) {
                    StringBuffer url = new StringBuffer(128);
                    url.append("addresses.jsp?").append(AddressesServlet.PARAM_NAME).append('=');
                    url.append(sanitizeTagParam(a.schema)).append("&").append(AddressesServlet.PARAM_LOC).append("=");
                    url.append(sanitizeTagParam(a.location)).append("&").append(AddressesServlet.PARAM_NAME).append("=");
                    url.append(sanitizeTagParam(a.name)).append("&").append(AddressesServlet.PARAM_PROTO).append("=");
                    url.append(sanitizeTagParam(a.protocol));
                    out.write("    <enclosure url=\"" + urlPrefix + sanitizeXML(url) + "\" length=\"1\" type=\"text/html\" syndietype=\"address\" />\n");
                }
            }
        }

        if (_archives.size() > 0) {
            for (int i = 0; i < _archives.size(); i++) {
                ArchiveRef a = (ArchiveRef)_archives.get(i);
                String url = getArchiveURL(null, new SafeURL(a.locationSchema + "://" + a.location));
                out.write("    <enclosure url=\"" + urlPrefix + sanitizeXML(url) + "\" length=\"1\" type=\"text/html\" syndietype=\"archive\" />\n");
            }
        }

        if (_entry != null) {
            List replies = _archive.getIndex().getReplies(_entry.getURI());
            if ( (replies != null) && (replies.size() > 0) ) {
                for (int i = 0; i < replies.size(); i++) { 
                    BlogURI reply = (BlogURI)replies.get(i);
                    String url = getPageURL(reply.getKeyHash(), null, reply.getEntryId(), -1, -1, true, _user.getShowImages());
                    out.write("    <enclosure url=\"" + urlPrefix + sanitizeXML(url) + "\" length=\"1\" type=\"text/html\" syndietype=\"reply\" />\n");
                }
            }
        }
        
        String inReplyTo = (String)_headers.get(HEADER_IN_REPLY_TO);
        if ( (inReplyTo != null) && (inReplyTo.trim().length() > 0) ) {
            String url = getPageURL(sanitizeTagParam(inReplyTo));
            out.write("    <enclosure url=\"" + urlPrefix + sanitizeXML(url) + "\" length=\"1\" type=\"text/html\" syndietype=\"parent\" />\n");
        } 
         */  
    }
    
    public void receiveHeaderEnd() {}
    public void receiveEnd() {}
    
    public static void main(String args[]) {
        test("");
        test("&");
        test("a&");
        test("&a");
        test("a&a");
        test("aa&aa");
    }
    private static final void test(String str) {
        StringBuffer t = new StringBuffer(str);
        String sanitized = sanitizeXML(t);
        System.out.println("[" + str + "] --> [" + sanitized + "]");
    }
}
