package net.i2p.syndie.sml;

import java.io.*;
import java.text.*;
import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.client.naming.PetName;
import net.i2p.client.naming.PetNameDB;
import net.i2p.data.*;
import net.i2p.syndie.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.web.*;
import net.i2p.util.Log;

/**
 * render the metadata of a post for display in the left nav of the blog view
 * (showing the attachments, etc).  Most of this is just duplicated from the HTMLRenderer
 *
 */
public class BlogPostInfoRenderer extends EventReceiverImpl {
    private Log _log;
    protected SMLParser _parser;
    protected Writer _out;
    protected User _user;
    protected Archive _archive;
    protected EntryContainer _entry;
    protected int _lastNewlineAt;
    protected Map _headers;
    protected List _addresses;
    protected List _links;
    protected List _blogs;
    protected List _archives;
    protected StringBuffer _bodyBuffer;
    
    public BlogPostInfoRenderer(I2PAppContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(getClass());
        _parser = new SMLParser(ctx);
    }

    public void render(User user, Archive archive, EntryContainer entry, Writer out) throws IOException {
        if (entry == null)
            return;
        render(user, archive, entry, entry.getEntry().getText(), out);
    }
    public void render(User user, Archive archive, EntryContainer entry, String rawSML, Writer out) throws IOException {
        prepare(user, archive, entry, rawSML, out);
        _out.write(_bodyBuffer.toString());
    }
    protected void prepare(User user, Archive archive, EntryContainer entry, String rawSML, Writer out) throws IOException {
        _user = user;
        _archive = archive;
        _entry = entry;
        _out = out;
        _headers = new HashMap();
        _bodyBuffer = new StringBuffer(1024);
        _addresses = new ArrayList();
        _links = new ArrayList();
        _blogs = new ArrayList();
        _archives = new ArrayList();
        _parser.parse(rawSML, this);
    }
    
    public void receiveEnd() { 
        BlogURI postURI = null;
        Attachment attachments[] = null;
        if (_entry != null) {
            attachments = _entry.getAttachments();
            postURI = _entry.getURI();
        }
        renderAttachments(postURI, attachments);
        renderBlogs(postURI);
        renderLinks(postURI);
        renderAddresses(postURI);
        renderArchives(postURI);
    }
    
    private void renderAttachments(BlogURI postURI, Attachment attachments[]) {
        if ( (attachments != null) && (attachments.length > 0) ) {
            _bodyBuffer.append("<div class=\"syndieBlogPostInfoGroup\">\n");
            _bodyBuffer.append("<span class=\"syndieBlogPostInfoGroupName\">Attachments</span>\n<ol>");
            
            // for each attachment:
            //   <li><a>$name</a>\n($size of type $type)</li>
            for (int i = 0; i < attachments.length; i++) {
                _bodyBuffer.append("<li>");
                String name = attachments[i].getName();
                if ( (name == null) && (name.trim().length() <= 0) )
                    name = "Attachment " + i;
                
                if (postURI != null) {
                    _bodyBuffer.append("<a href=\"blog.jsp?").append(ViewBlogServlet.PARAM_ATTACHMENT).append("=");
                    _bodyBuffer.append(postURI.getKeyHash().toBase64()).append("/").append(postURI.getEntryId());
                    _bodyBuffer.append("/").append(i).append("\" title=\"View the attachment\">");
                }
                _bodyBuffer.append(HTMLRenderer.sanitizeString(name, 40));
                if (postURI != null)
                    _bodyBuffer.append("</a>");
                
                _bodyBuffer.append("\n(");
                int bytes = attachments[i].getDataLength();
                if (bytes > 10*1024*1024)
                    _bodyBuffer.append(bytes/(1024*1024)).append("MBytes");
                else if (bytes > 10*1024)
                    _bodyBuffer.append(bytes/(10*1024)).append("KBytes");
                else
                    _bodyBuffer.append(bytes).append("Bytes");
                
                String type = attachments[i].getMimeType();
                if (type != null) {
                    if ("application/octet-stream".equals(type)) {
                        _bodyBuffer.append(", binary");
                    } else {
                        int split = type.lastIndexOf('/');
                        if (split > 0)
                            _bodyBuffer.append(", ").append(HTMLRenderer.sanitizeString(type.substring(split+1), 30));
                        else
                            _bodyBuffer.append(", ").append(HTMLRenderer.sanitizeString(type, 30));
                    }
                }
                
                _bodyBuffer.append(")");
                
                String desc = attachments[i].getDescription();
                if ( (desc != null) && (desc.trim().length() > 0) )
                    _bodyBuffer.append("<br />\n").append(HTMLRenderer.sanitizeString(desc, 120));
                
                _bodyBuffer.append("</li>\n");
            }
            _bodyBuffer.append("</ol>\n");
            _bodyBuffer.append("</div><!-- syndieBlogPostInfoGroup -->\n");
        }
    }
    
    private void renderBlogs(BlogURI postURI) {
        if ( (_blogs != null) && (_blogs.size() > 0) ) {
            _bodyBuffer.append("<div class=\"syndieBlogPostInfoGroup\">\n");
            _bodyBuffer.append("<span class=\"syndieBlogPostInfoGroupName\">Blogs</span>\n<ol>");
            
            // for each blog ref:
            //   <li><a>$name</a>\n ? :) :(</li>
            for (int i = 0; i < _blogs.size(); i++) {
                _bodyBuffer.append("<li>");
                Blog blog = (Blog)_blogs.get(i);
                PetNameDB db = _user.getPetNameDB();
                PetName pn = db.getByLocation(blog.hash);
                
                if ( (blog.entryId > 0) && (blog.hash != null) ) {
                    // view a specific post in their blog (jumping to their blog, rather than keeping the
                    // current blog's formatting... is that the right thing to do?)
                    _bodyBuffer.append("<a href=\"blog.jsp?").append(ViewBlogServlet.PARAM_BLOG).append("=");
                    _bodyBuffer.append(HTMLRenderer.sanitizeTagParam(blog.hash)).append("&amp;");
                    _bodyBuffer.append(ViewBlogServlet.PARAM_ENTRY).append("=").append(HTMLRenderer.sanitizeTagParam(blog.hash));
                    _bodyBuffer.append("/").append(blog.entryId);
                    _bodyBuffer.append("&amp;\" title=\"View the blog post\">");
                    if (pn != null)
                        _bodyBuffer.append(HTMLRenderer.sanitizeString(pn.getName()));
                    else
                        _bodyBuffer.append(HTMLRenderer.sanitizeString(blog.name));
                    _bodyBuffer.append(" on ").append(getEntryDate(blog.entryId));
                    _bodyBuffer.append("</a>");
                } else if (blog.hash != null) {
                    // view their full blog
                    _bodyBuffer.append("<a href=\"blog.jsp?").append(ViewBlogServlet.PARAM_BLOG);
                    _bodyBuffer.append("=").append(HTMLRenderer.sanitizeString(blog.hash)).append("\" title=\"View their blog\">");

                    if (pn != null) {
                        // we already have a petname for this user
                        _bodyBuffer.append(pn.getName()).append("</a>");
                        /* <a href=\"profile.jsp?");
                        _bodyBuffer.append(ThreadedHTMLRenderer.PARAM_AUTHOR).append("=");
                        _bodyBuffer.append(HTMLRenderer.sanitizeTagParam(pn.getLocation())).append("\" title=\"View their profile\">");
                        _bodyBuffer.append("?</a>");
                         */
                    } else {
                        // this name is already in the addressbook with another location, 
                        // generate a new nym
                        while ( (pn = db.getByName(blog.name)) != null)
                            blog.name = blog.name + ".";
                        _bodyBuffer.append(HTMLRenderer.sanitizeString(blog.name)).append("</a>");
                        /* <a href=\"profile.jsp?");
                        _bodyBuffer.append(ThreadedHTMLRenderer.PARAM_AUTHOR).append("=");
                        _bodyBuffer.append(HTMLRenderer.sanitizeTagParam(blog.hash)).append("\" title=\"View their profile\">");
                        _bodyBuffer.append("?</a>");
                         */
                        // should probably add on some inline-bookmarking support, but we'd need requestURL for that
                    }
                }
                _bodyBuffer.append("</li>\n");
            }
            _bodyBuffer.append("</div><!-- end syndieBlogPostInfoGroup -->\n");
        }
    }
       
    private final SimpleDateFormat _dateFormat = new SimpleDateFormat("yyyy/MM/dd", Locale.UK);
    private final String getEntryDate(long when) {
        synchronized (_dateFormat) {
            try {
                String str = _dateFormat.format(new Date(when));
                long dayBegin = _dateFormat.parse(str).getTime();
                return str + " [" + (when - dayBegin) + "]";
            } catch (ParseException pe) {
                // wtf
                return "unknown";
            }
        }
    }

    private void renderLinks(BlogURI postURI) {
        if ( (_links != null) && (_links.size() > 0) ) {
            _bodyBuffer.append("<div class=\"syndieBlogPostInfoGroup\">\n");
            _bodyBuffer.append("<span class=\"syndieBlogPostInfoGroupName\">Links</span>\n<ol>");
            
            // for each link:
            //   <li><a>$location</a></li>
            for (int i = 0; i < _links.size(); i++) {
                _bodyBuffer.append("<li>");

                Link l = (Link)_links.get(i);
                String schema = l.schema;
                _bodyBuffer.append("<a href=\"externallink.jsp?");
                if (l.schema != null)
                    _bodyBuffer.append("schema=").append(HTMLRenderer.sanitizeURL(l.schema)).append("&amp;");
                if (l.location != null)
                    _bodyBuffer.append("location=").append(HTMLRenderer.sanitizeURL(l.location)).append("&amp;");
                _bodyBuffer.append("\">").append(HTMLRenderer.sanitizeString(l.location, 40)).append(" (");
                _bodyBuffer.append(HTMLRenderer.sanitizeString(l.schema, 10)).append(")</a>");
                
                _bodyBuffer.append("</li>\n");
            }

            _bodyBuffer.append("</div><!-- end syndieBlogPostInfoGroup -->\n");
        }
    }
    
    private void renderAddresses(BlogURI postURI) {
        if ( (_addresses != null) && (_addresses.size() > 0) ) {
            _bodyBuffer.append("<div class=\"syndieBlogPostInfoGroup\">\n");
            _bodyBuffer.append("<span class=\"syndieBlogPostInfoGroupName\">Addresses</span>\n<ol>");
            
            // for each address:
            //   <li><a>$name</a></li>
            for (int i = 0; i < _addresses.size(); i++) {
                _bodyBuffer.append("<li>");
                Address a = (Address)_addresses.get(i);
                importAddress(a);
                PetName pn = null;
                if (_user != null)
                    pn = _user.getPetNameDB().getByLocation(a.location);
                if (pn != null) {
                    _bodyBuffer.append(HTMLRenderer.sanitizeString(pn.getName()));
                } else {
                    _bodyBuffer.append("<a href=\"addresses.jsp?");
                    if (a.schema != null)
                        _bodyBuffer.append(AddressesServlet.PARAM_NET).append("=").append(HTMLRenderer.sanitizeTagParam(a.schema)).append("&amp;");
                    if (a.location != null)
                        _bodyBuffer.append(AddressesServlet.PARAM_LOC).append("=").append(HTMLRenderer.sanitizeTagParam(a.location)).append("&amp;");
                    if (a.name != null)
                        _bodyBuffer.append(AddressesServlet.PARAM_NAME).append("=").append(HTMLRenderer.sanitizeTagParam(a.name)).append("&amp;");
                    if (a.protocol != null)
                        _bodyBuffer.append(AddressesServlet.PARAM_PROTO).append("=").append(HTMLRenderer.sanitizeTagParam(a.protocol)).append("&amp;");
                    _bodyBuffer.append("\" title=\"Add this address to your addressbook\">").append(HTMLRenderer.sanitizeString(a.name)).append("</a>");
                }                    
                _bodyBuffer.append("</li>\n");
            }

            _bodyBuffer.append("</div><!-- end syndieBlogPostInfoGroup -->\n");
        }
    }
    
    public void importAddress(Address a) {
        if (_user != null && _user.getImportAddresses() && !_user.getPetNameDB().containsName(a.name)) {
            PetName pn = new PetName(a.name, a.schema, a.protocol, a.location);
            _user.getPetNameDB().add(pn);
            try {
                _user.getPetNameDB().store(_user.getAddressbookLocation());
            } catch (IOException ioe) {
                //ignore
            }
        }
        if (BlogManager.instance().getImportAddresses() 
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
    
    private void renderArchives(BlogURI postURI) {
        if ( (_archives != null) && (_archives.size() > 0) ) {
            _bodyBuffer.append("<div class=\"syndieBlogPostInfoGroup\">\n");
            _bodyBuffer.append("<span class=\"syndieBlogPostInfoGroupName\">Archives</span>\n<ol>");
            
            // for each archive:
            //   <li><a>$name</a> :)<br/>$description</li>
            for (int i = 0; i < _archives.size(); i++) {
                _bodyBuffer.append("<li>");
                ArchiveRef a = (ArchiveRef)_archives.get(i);
                boolean authRemote = BlogManager.instance().authorizeRemote(_user);
                if (authRemote) {
                    _bodyBuffer.append("<a href=\"");
                    _bodyBuffer.append(HTMLRenderer.getArchiveURL(null, new SafeURL(a.locationSchema + "://" + a.location)));
                    _bodyBuffer.append("\" title=\"Browse the remote archive\">");
                }
                
                _bodyBuffer.append(HTMLRenderer.sanitizeString(a.name));
                
                if (authRemote) {
                    _bodyBuffer.append("</a>");
                }
                
                if ( (a.description != null) && (a.description.trim().length() > 0) )
                    _bodyBuffer.append(" ").append(HTMLRenderer.sanitizeString(a.description, 64));
                
                _bodyBuffer.append(" <a href=\"").append(HTMLRenderer.getBookmarkURL(a.name, a.location, a.locationSchema, AddressesServlet.PROTO_ARCHIVE));
                _bodyBuffer.append("\" title=\"Bookmark the remote archive\">bookmark it</a>");
                
                _bodyBuffer.append("</li>\n");
            }

            _bodyBuffer.append("</div><!-- end syndieBlogPostInfoGroup -->\n");
        }        
    }
    
    public void receiveHeader(String header, String value) {
        //System.err.println("Receive header [" + header + "] = [" + value + "]");
        if (HTMLRenderer.HEADER_PETNAME.equals(header)) {
            StringTokenizer tok = new StringTokenizer(value, "\t\n");
            if (tok.countTokens() != 4)
                return;
            String name = tok.nextToken();
            String net = tok.nextToken();
            String proto = tok.nextToken();
            String loc = tok.nextToken();
            Address a = new Address();
            a.name = HTMLRenderer.sanitizeString(name, false);
            a.schema = HTMLRenderer.sanitizeString(net, false);
            a.protocol = HTMLRenderer.sanitizeString(proto, false);
            a.location = HTMLRenderer.sanitizeString(loc, false);
            _addresses.add(a);
        }
    }
    
    public void receiveHeaderEnd() { }

    public void receivePlain(String text) { }
    
    public void receiveBold(String text) { }
    public void receiveItalic(String text) { }
    public void receiveUnderline(String text) { }
    public void receiveHR() { }
    public void receiveH1(String body) { }
    public void receiveH2(String body) { }
    public void receiveH3(String body) { }
    public void receiveH4(String body) { }
    public void receiveH5(String body) { }
    public void receivePre(String body) { }
    public void receiveQuote(String text, String whoQuoted, String quoteLocationSchema, String quoteLocation) { }
    public void receiveCode(String text, String codeLocationSchema, String codeLocation) { }
    public void receiveImage(String alternateText, int attachmentId) { }
    public void receiveCut(String summaryText) { }
    public void receiveNewline() { }
    public void receiveLT() { }
    public void receiveGT() { }
    public void receiveBegin() {}
    public void receiveLeftBracket() { }
    public void receiveRightBracket() { }
    
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
    }
    
    public void receiveAttachment(int id, int thumb, String anchorText) { }    
}
