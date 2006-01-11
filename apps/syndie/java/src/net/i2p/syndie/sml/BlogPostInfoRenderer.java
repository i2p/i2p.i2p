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
        renderAttachments(postURI, "syndieBlogPostInfo", attachments, _bodyBuffer);
    }
    public static void renderAttachments(BlogURI postURI, String baseStyleName, Attachment attachments[], StringBuffer out) {
        if ( (attachments != null) && (attachments.length > 0) ) {
            out.append("<div class=\"").append(baseStyleName).append("Group\">\n");
            out.append("<span class=\"").append(baseStyleName).append("GroupName\">Attachments</span>\n<ol>");
            
            // for each attachment:
            //   <li><a>$name</a>\n($size of type $type)</li>
            for (int i = 0; i < attachments.length; i++) {
                out.append("<li>");
                String name = attachments[i].getName();
                if ( (name == null) && (name.trim().length() <= 0) )
                    name = "Attachment " + i;
                
                if (postURI != null) {
                    out.append("<a href=\"blog.jsp?").append(ViewBlogServlet.PARAM_ATTACHMENT).append("=");
                    out.append(postURI.getKeyHash().toBase64()).append("/").append(postURI.getEntryId());
                    out.append("/").append(i).append("\" title=\"View the attachment\">");
                }
                out.append(HTMLRenderer.sanitizeString(name, 40));
                if (postURI != null)
                    out.append("</a>");
                
                out.append("\n(");
                int bytes = attachments[i].getDataLength();
                if (bytes > 10*1024*1024)
                    out.append(bytes/(1024*1024)).append("MBytes");
                else if (bytes > 10*1024)
                    out.append(bytes/(10*1024)).append("KBytes");
                else
                    out.append(bytes).append("Bytes");
                
                String type = attachments[i].getMimeType();
                if (type != null) {
                    if ("application/octet-stream".equals(type)) {
                        out.append(", binary");
                    } else {
                        int split = type.lastIndexOf('/');
                        if (split > 0)
                            out.append(", ").append(HTMLRenderer.sanitizeString(type.substring(split+1), 30));
                        else
                            out.append(", ").append(HTMLRenderer.sanitizeString(type, 30));
                    }
                }
                
                out.append(")");
                
                String desc = attachments[i].getDescription();
                if ( (desc != null) && (desc.trim().length() > 0) )
                    out.append("<br />\n").append(HTMLRenderer.sanitizeString(desc, 120));
                
                out.append("</li>\n");
            }
            out.append("</ol>\n");
            out.append("</div><!-- syndieBlogPostInfoGroup -->\n");
        }
    }
    
    private void renderBlogs(BlogURI postURI) {
        renderBlogs(postURI, _user, "syndieBlogPostInfo", _blogs, _bodyBuffer);
    }
    public static void renderBlogs(BlogURI postURI, User user, String baseStyleName, List blogs, StringBuffer out) {
        if ( (blogs != null) && (blogs.size() > 0) ) {
            out.append("<div class=\"").append(baseStyleName).append("Group\">\n");
            out.append("<span class=\"").append(baseStyleName).append("GroupName\">Blogs</span>\n<ol>");
            
            // for each blog ref:
            //   <li><a>$name</a>\n ? :) :(</li>
            for (int i = 0; i < blogs.size(); i++) {
                out.append("<li>");
                Blog blog = (Blog)blogs.get(i);
                PetNameDB db = user.getPetNameDB();
                PetName pn = db.getByLocation(blog.hash);
                
                if ( (blog.entryId > 0) && (blog.hash != null) ) {
                    // view a specific post in their blog (jumping to their blog, rather than keeping the
                    // current blog's formatting... is that the right thing to do?)
                    out.append("<a href=\"blog.jsp?").append(ViewBlogServlet.PARAM_BLOG).append("=");
                    out.append(HTMLRenderer.sanitizeTagParam(blog.hash)).append("&amp;");
                    out.append(ViewBlogServlet.PARAM_ENTRY).append("=").append(HTMLRenderer.sanitizeTagParam(blog.hash));
                    out.append("/").append(blog.entryId);
                    out.append("&amp;\" title=\"View the blog post\">");
                    if (pn != null)
                        out.append(HTMLRenderer.sanitizeString(pn.getName()));
                    else
                        out.append(HTMLRenderer.sanitizeString(blog.name));
                    out.append(" on ").append(getEntryDate(blog.entryId));
                    out.append("</a>");
                } else if (blog.hash != null) {
                    // view their full blog
                    out.append("<a href=\"blog.jsp?").append(ViewBlogServlet.PARAM_BLOG);
                    out.append("=").append(HTMLRenderer.sanitizeString(blog.hash)).append("\" title=\"View their blog\">");

                    if (pn != null) {
                        // we already have a petname for this user
                        out.append(pn.getName()).append("</a>");
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
                        out.append(HTMLRenderer.sanitizeString(blog.name)).append("</a>");
                        /* <a href=\"profile.jsp?");
                        _bodyBuffer.append(ThreadedHTMLRenderer.PARAM_AUTHOR).append("=");
                        _bodyBuffer.append(HTMLRenderer.sanitizeTagParam(blog.hash)).append("\" title=\"View their profile\">");
                        _bodyBuffer.append("?</a>");
                         */
                        // should probably add on some inline-bookmarking support, but we'd need requestURL for that
                    }
                }
                out.append("</li>\n");
            }
            out.append("</div><!-- end syndieBlogPostInfoGroup -->\n");
        }
    }
       
    private static final SimpleDateFormat _dateFormat = new SimpleDateFormat("yyyy/MM/dd", Locale.UK);
    private static final String getEntryDate(long when) {
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
        renderLinks(postURI, _user, "syndieBlogPostInfo", _links, _bodyBuffer);
    }
    public static void renderLinks(BlogURI postURI, User user, String baseStyleName, List links, StringBuffer out) {
        if ( (links != null) && (links.size() > 0) ) {
            out.append("<div class=\"").append(baseStyleName).append("Group\">\n");
            out.append("<span class=\"").append(baseStyleName).append("GroupName\">Links</span>\n<ol>");
            
            // for each link:
            //   <li><a>$location</a></li>
            for (int i = 0; i < links.size(); i++) {
                out.append("<li>");

                Link l = (Link)links.get(i);
                String schema = l.schema;
                out.append("<a href=\"externallink.jsp?");
                if (l.schema != null)
                    out.append("schema=").append(HTMLRenderer.sanitizeURL(l.schema)).append("&amp;");
                if (l.location != null)
                    out.append("location=").append(HTMLRenderer.sanitizeURL(l.location)).append("&amp;");
                out.append("\">").append(HTMLRenderer.sanitizeString(l.location, 30)).append(" (");
                out.append(HTMLRenderer.sanitizeString(l.schema, 6)).append(")</a>");
                
                out.append("</li>\n");
            }

            out.append("</div><!-- end syndieBlogPostInfoGroup -->\n");
        }
    }
    
    private void renderAddresses(BlogURI postURI) {
        renderAddresses(postURI, _user, "syndieBlogPostInfo", _addresses, _bodyBuffer);
    }
    public static void renderAddresses(BlogURI postURI, User user, String baseStyleName, List addresses, StringBuffer out) {
        if ( (addresses != null) && (addresses.size() > 0) ) {
            out.append("<div class=\"").append(baseStyleName).append("Group\">\n");
            out.append("<span class=\"").append(baseStyleName).append("GroupName\">Addresses</span>\n<ol>");
            
            // for each address:
            //   <li><a>$name</a></li>
            for (int i = 0; i < addresses.size(); i++) {
                out.append("<li>");
                Address a = (Address)addresses.get(i);
                importAddress(a, user);
                PetName pn = null;
                if (user != null)
                    pn = user.getPetNameDB().getByLocation(a.location);
                if (pn != null) {
                    out.append(HTMLRenderer.sanitizeString(pn.getName()));
                } else {
                    out.append("<a href=\"addresses.jsp?");
                    if (a.schema != null)
                        out.append(AddressesServlet.PARAM_NET).append("=").append(HTMLRenderer.sanitizeTagParam(a.schema)).append("&amp;");
                    if (a.location != null)
                        out.append(AddressesServlet.PARAM_LOC).append("=").append(HTMLRenderer.sanitizeTagParam(a.location)).append("&amp;");
                    if (a.name != null)
                        out.append(AddressesServlet.PARAM_NAME).append("=").append(HTMLRenderer.sanitizeTagParam(a.name)).append("&amp;");
                    if (a.protocol != null)
                        out.append(AddressesServlet.PARAM_PROTO).append("=").append(HTMLRenderer.sanitizeTagParam(a.protocol)).append("&amp;");
                    out.append("\" title=\"Add this address to your addressbook\">").append(HTMLRenderer.sanitizeString(a.name)).append("</a>");
                }                    
                out.append("</li>\n");
            }

            out.append("</div><!-- end syndieBlogPostInfoGroup -->\n");
        }
    }
    
    public static void importAddress(Address a, User user) {
        if (user != null && user.getImportAddresses() && !user.getPetNameDB().containsName(a.name)) {
            PetName pn = new PetName(a.name, a.schema, a.protocol, a.location);
            user.getPetNameDB().add(pn);
            try {
                user.getPetNameDB().store(user.getAddressbookLocation());
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
        renderArchives(postURI, _user, "syndieBlogPostInfo", _archives, _bodyBuffer);
    }
    public static void renderArchives(BlogURI postURI, User user, String baseStyleName, List archives, StringBuffer out) {
        if ( (archives != null) && (archives.size() > 0) ) {
            out.append("<div class=\"").append(baseStyleName).append("Group\">\n");
            out.append("<span class=\"").append(baseStyleName).append("GroupName\">Archives</span>\n<ol>");
            
            // for each archive:
            //   <li><a>$name</a> :)<br/>$description</li>
            for (int i = 0; i < archives.size(); i++) {
                out.append("<li>");
                ArchiveRef a = (ArchiveRef)archives.get(i);
                boolean authRemote = BlogManager.instance().authorizeRemote(user);
                if (authRemote) {
                    out.append("<a href=\"");
                    out.append(HTMLRenderer.getArchiveURL(null, new SafeURL(a.locationSchema + "://" + a.location)));
                    out.append("\" title=\"Browse the remote archive\">");
                }
                
                out.append(HTMLRenderer.sanitizeString(a.name));
                
                if (authRemote) {
                    out.append("</a>");
                }
                
                if ( (a.description != null) && (a.description.trim().length() > 0) )
                    out.append(" ").append(HTMLRenderer.sanitizeString(a.description, 64));
                /*
                _bodyBuffer.append(" <a href=\"").append(HTMLRenderer.getBookmarkURL(a.name, a.location, a.locationSchema, AddressesServlet.PROTO_ARCHIVE));
                _bodyBuffer.append("\" title=\"Bookmark the remote archive\">bookmark it</a>");
                */
                out.append("</li>\n");
            }

            out.append("</div><!-- end syndieBlogPostInfoGroup -->\n");
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
    
    public void receiveLink(String schema, String location, String text) {
        Link l = new Link();
        l.schema = schema;
        l.location = location;
        if (!_links.contains(l))
            _links.add(l);
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
