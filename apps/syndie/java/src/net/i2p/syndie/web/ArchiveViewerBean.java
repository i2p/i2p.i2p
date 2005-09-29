package net.i2p.syndie.web;

import java.io.*;
import java.text.*;
import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.client.naming.PetName;
import net.i2p.client.naming.PetNameDB;
import net.i2p.data.*;
import net.i2p.syndie.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.sml.*;

/**
 *
 */
public class ArchiveViewerBean {
    public static String getBlogName(String keyHash) {
        BlogInfo info = BlogManager.instance().getArchive().getBlogInfo(new Hash(Base64.decode(keyHash)));
        if (info == null)
            return HTMLRenderer.sanitizeString(keyHash);
        else
            return HTMLRenderer.sanitizeString(info.getProperty("Name"));
    }
    
    /** base64 encoded hash of the blog's public key, or null for no filtering by blog */
    public static final String PARAM_BLOG = "blog";
    /** base64 encoded tag to filter by, or blank for no filtering by tags */
    public static final String PARAM_TAG = "tag";
    /** entry id within the blog if we only want to see that one */
    public static final String PARAM_ENTRY = "entry";
    /** base64 encoded group within the user's filters */
    public static final String PARAM_GROUP = "group";
    /** how many entries per page to show at once */
    public static final String PARAM_NUM_PER_PAGE = "pageSize";
    /** which page of entries to render */
    public static final String PARAM_PAGE_NUMBER = "pageNum";
    /** should we expand each entry to show the full contents */
    public static final String PARAM_EXPAND_ENTRIES = "expand";
    /** should entries be rendered with the images shown inline */
    public static final String PARAM_SHOW_IMAGES = "images";
    /** should we regenerate an index to the archive before rendering */
    public static final String PARAM_REGENERATE_INDEX = "regenerateIndex";
    /** which attachment should we serve up raw */
    public static final String PARAM_ATTACHMENT = "attachment";
    /** we are replying to a particular blog/tag/entry/whatever (value == base64 encoded selector) */
    public static final String PARAM_IN_REPLY_TO = "inReplyTo";
    
    /**
     * Drop down multichooser:
     *  blog://base64(key)
     *  tag://base64(tag)
     *  blogtag://base64(key)/base64(tag)
     *  entry://base64(key)/entryId
     *  group://base64(groupName)
     *  ALL
     */
    public static final String PARAM_SELECTOR = "selector";
    public static final String SEL_ALL = "ALL";
    public static final String SEL_BLOG = "blog://";
    public static final String SEL_TAG = "tag://";
    public static final String SEL_BLOGTAG = "blogtag://";
    public static final String SEL_ENTRY = "entry://";
    public static final String SEL_GROUP = "group://";
    /** submit field for the selector form */
    public static final String PARAM_SELECTOR_ACTION = "action";
    public static final String SEL_ACTION_SET_AS_DEFAULT = "Set as default";
    
    public static void renderBlogSelector(User user, Map parameters, Writer out) throws IOException {
        String sel = getString(parameters, PARAM_SELECTOR);
        String action = getString(parameters, PARAM_SELECTOR_ACTION);
        if ( (sel != null) && (action != null) && (SEL_ACTION_SET_AS_DEFAULT.equals(action)) ) {
            user.setDefaultSelector(HTMLRenderer.sanitizeString(sel, false));
            BlogManager.instance().saveUser(user);
        }
        
        out.write("<select class=\"b_selector\" name=\"");
        out.write(PARAM_SELECTOR);
        out.write("\">");
        out.write("<option value=\"");
        out.write(getDefaultSelector(user, parameters));
        out.write("\">Default blog filter</option>\n");
        out.write("<option value=\"");
        out.write(SEL_ALL);
        out.write("\">All posts from all blogs</option>\n");
        
        List groups = null;
        if (user != null)
            groups = user.getPetNameDB().getGroups();
        if (groups != null) {
            for (int i = 0; i < groups.size(); i++) {
                String name = (String)groups.get(i);
                out.write("<option value=\"group://" + Base64.encode(DataHelper.getUTF8(name)) + "\">" +
                          "Group: " + HTMLRenderer.sanitizeString(name) + "</option>\n");
            }
        }
        
        Archive archive = BlogManager.instance().getArchive();
        ArchiveIndex index = archive.getIndex();
        
        for (int i = 0; i < index.getNewestBlogCount(); i++) {
            Hash cur = index.getNewestBlog(i);
            String knownName = user.getPetNameDB().getNameByLocation(cur.toBase64());
            PetName pn = null;
            if (knownName != null) {
                pn = user.getPetNameDB().get(knownName);
                knownName = pn.getName();
            }
            if ( (pn != null) && (pn.isMember("Ignore")) )
                continue;
            String blog = Base64.encode(cur.getData());
            out.write("<option value=\"blog://" + blog + "\">");
            out.write("New blog: ");
            BlogInfo info = archive.getBlogInfo(cur);
            String name = knownName;
            if ( (name == null) && (info != null) ) 
                name = info.getProperty(BlogInfo.NAME);
            if (name != null)
                name = HTMLRenderer.sanitizeString(name);
            else
                name = Base64.encode(cur.getData());
            out.write(name);
            out.write("</option>\n");
        }
        
        ////List allTags = new ArrayList();
        // perhaps sort this by name (even though it isnt unique...)
        Set blogs = index.getUniqueBlogs();
        for (Iterator iter = blogs.iterator(); iter.hasNext(); ) {
            Hash cur = (Hash)iter.next();
            String knownName = user.getPetNameDB().getNameByLocation(cur.toBase64());
            PetName pn = null;
            if (knownName != null) {
                pn = user.getPetNameDB().get(knownName);
                knownName = pn.getName();
            }
            if ( (pn != null) && (pn.isMember("Ignore")) )
                continue;
         
            String blog = Base64.encode(cur.getData());
            out.write("<option value=\"blog://");
            out.write(blog);
            out.write("\">");
            BlogInfo info = archive.getBlogInfo(cur);
            String name = knownName;
            if ( (name == null) && (info != null) ) 
                name = info.getProperty(BlogInfo.NAME);
            if (name != null)
                name = HTMLRenderer.sanitizeString(name);
            else
                name = Base64.encode(cur.getData());
            out.write(name);
            if (info != null) {
                int howMany = index.getBlogEntryCount(info.getKey().calculateHash());
                if (howMany == 1)
                    out.write(" [1 post]");
                else
                    out.write(" [" + howMany + " posts]");
            }
            out.write("</option>\n");
            
            /*
            List tags = index.getBlogTags(cur);
            for (int j = 0; j < tags.size(); j++) {
                String tag = (String)tags.get(j);
                if (false) {
                    StringBuffer b = new StringBuffer(tag.length()*2);
                    for (int k = 0; k < tag.length(); k++) {
                        b.append((int)tag.charAt(k));
                        b.append(' ');
                    }
                    System.out.println("tag in select: " + tag + ": " + b.toString());
                }
                
                if (!allTags.contains(tag))
                    allTags.add(tag);
                out.write("<option value=\"blogtag://");
                out.write(blog);
                out.write("/");
                byte utf8tag[] = DataHelper.getUTF8(tag);
                String encoded = Base64.encode(utf8tag);
                if (false) {
                    byte utf8dec[] = Base64.decode(encoded);
                    String travel = DataHelper.getUTF8(utf8dec);
                    StringBuffer b = new StringBuffer();
                    for (int k = 0; k < travel.length(); k++) {
                        b.append((int)travel.charAt(k));
                        b.append(' ');
                    }
                    b.append(" encoded into: ");
                    for (int k = 0; k < encoded.length(); k++) {
                        b.append((int)encoded.charAt(k));
                        b.append(' ');
                    }
                    System.out.println("UTF8(unbase64(base64(UTF8(tag)))) == tag: " + b.toString());
                }
                out.write(encoded);
                out.write("\">");
                out.write(name);
                out.write("- posts with the tag &quot;");
                out.write(tag);
                out.write("&quot;</option>\n");
            }
             */
        }
        /*
        for (int i = 0; i < allTags.size(); i++) {
            String tag = (String)allTags.get(i);
            out.write("<option value=\"tag://");
            out.write(Base64.encode(DataHelper.getUTF8(tag)));
            out.write("\">Posts in any blog with the tag &quot;");
            out.write(tag);
            out.write("&quot;</option>\n");
        }
         */
        out.write("</select>");
        
        int numPerPage = getInt(parameters, PARAM_NUM_PER_PAGE, 5);
        int pageNum = getInt(parameters, PARAM_PAGE_NUMBER, 0);
        boolean expandEntries = getBool(parameters, PARAM_EXPAND_ENTRIES, (user != null ? user.getShowExpanded() : false));
        boolean showImages = getBool(parameters, PARAM_SHOW_IMAGES, (user != null ? user.getShowImages() : false));
        
        out.write("<input type=\"hidden\" name=\"" + PARAM_NUM_PER_PAGE+ "\" value=\"" + numPerPage+ "\" />");
        out.write("<input type=\"hidden\" name=\"" + PARAM_PAGE_NUMBER+ "\" value=\"" + pageNum+ "\" />");
        out.write("<input type=\"hidden\" name=\"" + PARAM_EXPAND_ENTRIES+ "\" value=\"" + expandEntries+ "\" />");
        out.write("<input type=\"hidden\" name=\"" + PARAM_SHOW_IMAGES + "\" value=\"" + showImages + "\" />");
        
    }
    
    private static String getDefaultSelector(User user, Map parameters) {
        if ( (user == null) || (user.getDefaultSelector() == null) )
            return BlogManager.instance().getArchive().getDefaultSelector();
        else
            return user.getDefaultSelector();
    }
    
    public static void renderBlogs(User user, Map parameters, Writer out, String afterPagination) throws IOException {
        String blogStr = getString(parameters, PARAM_BLOG);
        Hash blog = null;
        if (blogStr != null) blog = new Hash(Base64.decode(blogStr));
        if ( (blog != null) && (blog.getData() == null) ) blog = null;
        String tag = getString(parameters, PARAM_TAG);
        if (tag != null) tag = DataHelper.getUTF8(Base64.decode(tag));
        
        long entryId = -1;
        if (blog != null) {
            String entryIdStr = getString(parameters, PARAM_ENTRY);
            try { 
                entryId = Long.parseLong(entryIdStr); 
            } catch (NumberFormatException nfe) {}
        }
        String group = getString(parameters, PARAM_GROUP);
        if (group != null) group = DataHelper.getUTF8(Base64.decode(group));
        
        String sel = getString(parameters, PARAM_SELECTOR);
        
        if (getString(parameters, "action") != null) {
            tag = null;
            blog = null;
            sel = null;
            group = null;
        }
        
        if ( (sel == null) && (blog == null) && (group == null) && (tag == null) )
            sel = getDefaultSelector(user, parameters);
        if (sel != null) {
            Selector s = new Selector(sel);
            blog = s.blog;
            tag = s.tag;
            entryId = s.entry;
            group = s.group;
        }
        
        int numPerPage = getInt(parameters, PARAM_NUM_PER_PAGE, 5);
        int pageNum = getInt(parameters, PARAM_PAGE_NUMBER, 0);
        boolean expandEntries = getBool(parameters, PARAM_EXPAND_ENTRIES, (user != null ? user.getShowExpanded() : false));
        boolean showImages = getBool(parameters, PARAM_SHOW_IMAGES, (user != null ? user.getShowImages() : false));
        boolean regenerateIndex = getBool(parameters, PARAM_REGENERATE_INDEX, false);
        try {
            renderBlogs(user, blog, tag, entryId, group, numPerPage, pageNum, expandEntries, showImages, regenerateIndex, sel, out, afterPagination);
        } catch (IOException ioe) { 
            ioe.printStackTrace();
            throw ioe; 
        } catch (RuntimeException re) {
            re.printStackTrace();
            throw re;
        }
    }
    
    public static class Selector {
        public Hash blog;
        public String tag;
        public long entry;
        public String group;
        public Selector(String selector) {
            entry = -1;
            blog = null;
            tag = null;
            if (selector != null) {
                if (selector.startsWith(SEL_BLOG)) {
                    String blogStr = selector.substring(SEL_BLOG.length());
                    //System.out.println("Selector [" + selector + "] blogString: [" + blogStr + "]");
                    byte h[] = Base64.decode(blogStr);
                    if (h != null)
                        blog = new Hash(h);
                    //else
                    //    System.out.println("blog string does not decode properly: [" + blogStr + "]");
                } else if (selector.startsWith(SEL_BLOGTAG)) {
                    int tagStart = selector.lastIndexOf('/');
                    String blogStr = selector.substring(SEL_BLOGTAG.length(), tagStart);
                    blog = new Hash(Base64.decode(blogStr));
                    if (blog.getData() == null) {
                        System.out.println("Blog string [" + blogStr + "] does not decode");
                        blog = null;
                        return;
                    }
                    tag = selector.substring(tagStart+1);
                    String origTag = tag;
                    byte rawDecode[] = null;
                    if (tag != null) {
                        rawDecode = Base64.decode(tag);
                        tag = DataHelper.getUTF8(rawDecode);
                    }
                    //System.out.println("Selector [" + selector + "] blogString: [" + blogStr + "] tag: [" + tag + "]");
                    if (false && tag != null) {
                        StringBuffer b = new StringBuffer(tag.length()*2);
                        for (int j = 0; j < tag.length(); j++) {
                            b.append((int)tag.charAt(j));
                            if (rawDecode.length > j)
                                b.append('.').append((int)rawDecode[j]);
                            b.append(' ');
                        }
                        b.append("encoded as ");
                        for (int j = 0; j < origTag.length(); j++) {
                            b.append((int)origTag.charAt(j)).append(' ');
                        }
                        //System.out.println("selected tag: " + b.toString());
                    }
                } else if (selector.startsWith(SEL_TAG)) {
                    tag = selector.substring(SEL_TAG.length());
                    byte rawDecode[] = null;
                    if (tag != null) {
                        rawDecode = Base64.decode(tag);
                        tag = DataHelper.getUTF8(rawDecode);
                    }
                    //System.out.println("Selector [" + selector + "] tag: [" + tag + "]");
                    if (false && tag != null) {
                        StringBuffer b = new StringBuffer(tag.length()*2);
                        for (int j = 0; j < tag.length(); j++) {
                            b.append((int)tag.charAt(j));
                            if (rawDecode.length > j)
                                b.append('.').append((int)rawDecode[j]);
                            b.append(' ');
                        }
                        //System.out.println("selected tag: " + b.toString());
                    }
                } else if (selector.startsWith(SEL_ENTRY)) {
                    int entryStart = selector.lastIndexOf('/');
                    String blogStr = blogStr = selector.substring(SEL_ENTRY.length(), entryStart);
                    String entryStr = selector.substring(entryStart+1);
                    try {
                        entry = Long.parseLong(entryStr);
                        Hash h = new Hash(Base64.decode(blogStr));
                        if (h.getData() != null)
                            blog = h;
                        //else
                        //    System.out.println("Blog does not decode [" + blogStr + "]");
                        //System.out.println("Selector [" + selector + "] blogString: [" + blogStr + "] entry: [" + entry + "]");
                    } catch (NumberFormatException nfe) {}
                } else if (selector.startsWith(SEL_GROUP)) {
                    group = DataHelper.getUTF8(Base64.decode(selector.substring(SEL_GROUP.length())));
                    //System.out.println("Selector [" + selector + "] group: [" + group + "]");
                }
            }
        }
    }
    
    private static void renderBlogs(User user, Hash blog, String tag, long entryId, String group, int numPerPage, int pageNum, 
                                   boolean expandEntries, boolean showImages, boolean regenerateIndex, String selector, Writer out, String afterPagination) throws IOException {
        Archive archive = BlogManager.instance().getArchive();
        if (regenerateIndex)
            archive.regenerateIndex();
        ArchiveIndex index = archive.getIndex();
        List entries = pickEntryURIs(user, index, blog, tag, entryId, group);
        //System.out.println("Searching for " + blog + "/" + tag + "/" + entryId + "/" + pageNum + "/" + numPerPage + "/" + group);
        //System.out.println("Entry URIs: " + entries);
        
        HTMLRenderer renderer = new HTMLRenderer(I2PAppContext.getGlobalContext());
        int start = pageNum * numPerPage;
        int end = start + numPerPage;
        int pages = 1;
        if (entries.size() <= 1) {
            // just one, so no pagination, etc
            start = 0;
            end = 1;
        } else {
            if (end >= entries.size())
                end = entries.size();
            if ( (pageNum < 0) || (numPerPage <= 0) ) {
                start = 0;
                end = entries.size() - 1;
            } else {
                pages = entries.size() / numPerPage;
                if (numPerPage * pages < entries.size())
                    pages++;
                if (pageNum > 0) {
                    String prevURL = null;
                    if ( (selector == null) || (selector.trim().length() <= 0) )
                        prevURL = HTMLRenderer.getPageURL(blog, tag, entryId, group, numPerPage, pageNum-1, expandEntries, showImages);
                    else
                        prevURL = HTMLRenderer.getPageURL(user, selector, numPerPage, pageNum-1);
                    //System.out.println("prevURL: " + prevURL);
                    out.write(" <a class=\"b_selectorPrevMore\" href=\"" + prevURL + "\">&lt;&lt;</a>");
                } else {
                    out.write(" <span class=\"b_selectorPrevNone\">&lt;&lt;</span> ");
                }
                out.write("<span class=\"b_selectorPage\">Page " + (pageNum+1) + " of " + pages + "</span>");
                if (pageNum + 1 < pages) {
                    String nextURL = null;
                    if ( (selector == null) || (selector.trim().length() <= 0) )
                        nextURL = HTMLRenderer.getPageURL(blog, tag, entryId, group, numPerPage, pageNum+1, expandEntries, showImages);
                    else
                        nextURL = HTMLRenderer.getPageURL(user, selector, numPerPage, pageNum+1);
                    //System.out.println("nextURL: " + nextURL);
                    out.write(" <a class=\"b_selectorNextMore\" href=\"" + nextURL + "\">&gt;&gt;</a>");
                } else {
                    out.write(" <span class=\"b_selectorNextNone\">&gt;&gt;</span>");
                }
            }
        }
        
        /*
        out.write(" <i>");
        
        if (showImages)
            out.write("<a href=\"" + HTMLRenderer.getPageURL(blog, tag, entryId, group, numPerPage, pageNum, expandEntries, false) +
                      "\">Hide images</a>");
        else
            out.write("<a href=\"" + HTMLRenderer.getPageURL(blog, tag, entryId, group, numPerPage, pageNum, expandEntries, true) +
                      "\">Show images</a>");
        
        if (expandEntries)
            out.write(" <a href=\"" + HTMLRenderer.getPageURL(blog, tag, entryId, group, numPerPage, pageNum, false, showImages) +
                      "\">Hide details</a>");
        else
            out.write(" <a href=\"" + HTMLRenderer.getPageURL(blog, tag, entryId, group, numPerPage, pageNum, true, showImages) +
                      "\">Expand details</a>");
        
        out.write("</i>");
        */
        
        if (afterPagination != null) 
            out.write(afterPagination);
        
        if (entries.size() <= 0) end = -1;
        //System.out.println("Entries.size: " + entries.size() + " start=" + start + " end=" + end);
        for (int i = start; i < end; i++) {
            BlogURI uri = (BlogURI)entries.get(i);
            EntryContainer c = archive.getEntry(uri);
            try {
                if (c == null)
                    renderer.renderUnknownEntry(user, archive, uri, out);
                else
                    renderer.render(user, archive, c, out, !expandEntries, showImages);
            } catch (RuntimeException e) {
                e.printStackTrace();
                throw e;
            }
        }
    }
    
    public static List pickEntryURIs(User user, ArchiveIndex index, Hash blog, String tag, long entryId, String group) {
        if ( (blog != null) && ( (blog.getData() == null) || (blog.getData().length != Hash.HASH_LENGTH) ) ) 
            blog = null;
        List rv = new ArrayList(16);
        if ( (blog != null) && (entryId >= 0) ) {
            rv.add(new BlogURI(blog, entryId));
            return rv;
        }
        
        if ( (group != null) && (user != null) ) {
            List selectors = (List)user.getBlogGroups().get(group);
            if (selectors != null) {
                //System.out.println("Selectors for group " + group + ": " + selectors);
                for (int i = 0; i < selectors.size(); i++) {
                    String sel = (String)selectors.get(i);
                    Selector s = new Selector(sel);
                    if ( (s.entry >= 0) && (s.blog != null) && (s.group == null) && (s.tag == null) )
                        rv.add(new BlogURI(s.blog, s.entry));
                    else
                        index.selectMatchesOrderByEntryId(rv, s.blog, s.tag);
                }
            }
            PetNameDB db = user.getPetNameDB();
            for (Iterator iter = db.getNames().iterator(); iter.hasNext(); ) {
                String name = (String)iter.next();
                PetName pn = db.get(name);
                if ("syndie".equals(pn.getNetwork()) && "syndieblog".equals(pn.getProtocol()) && pn.isMember(group)) {
                    byte pnLoc[] = Base64.decode(pn.getLocation());
                    if (pnLoc != null) {
                        Hash pnHash = new Hash(pnLoc);
                        index.selectMatchesOrderByEntryId(rv, pnHash, null);
                    }
                }
            }
            sort(rv);
            if (rv.size() > 0)
                return rv;
        }
        index.selectMatchesOrderByEntryId(rv, blog, tag);
        filterIgnored(user, rv);
        return rv;
    }
    
    private static void filterIgnored(User user, List uris) {
        for (int i = 0; i < uris.size(); i++) {
            BlogURI uri = (BlogURI)uris.get(i);
            Hash k = uri.getKeyHash();
            if (k == null) continue;
            String pname = user.getPetNameDB().getNameByLocation(k.toBase64());
            if (pname != null) {
                PetName pn = user.getPetNameDB().get(pname);
                if ( (pn != null) && (pn.isMember("Ignore")) ) {
                    uris.remove(i);
                    i--;
                }
            }
        }
    }
    
    private static void sort(List uris) {
        TreeMap ordered = new TreeMap();
        while (uris.size() > 0) {
            BlogURI uri = (BlogURI)uris.remove(0);
            int off = 0;
            while (ordered.containsKey(new Long(0 - off - uri.getEntryId())))
                off++;
            ordered.put(new Long(0-off-uri.getEntryId()), uri);
        }
        for (Iterator iter = ordered.values().iterator(); iter.hasNext(); )
            uris.add(iter.next());
    }
    
    public static final String getString(Map parameters, String param) {
        if ( (parameters == null) || (parameters.get(param) == null) )
            return null;
        Object vals = parameters.get(param);
        if (vals.getClass().isArray()) {
            String v[] = (String[])vals;
            if (v.length > 0)
                return ((String[])vals)[0];
            else
                return null;
        } else if (vals instanceof Collection) {
            Collection c = (Collection)vals;
            if (c.size() > 0)
                return (String)c.iterator().next();
            else
                return null;
        } else {
            return null;
        }
    }
    public static final String[] getStrings(Map parameters, String param) {
        if ( (parameters == null) || (parameters.get(param) == null) )
            return null;
        Object vals = parameters.get(param);
        if (vals.getClass().isArray()) {
            return (String[])vals;
        } else if (vals instanceof Collection) {
            Collection c = (Collection)vals;
            if (c.size() <= 0) return null;
            String rv[] = new String[c.size()];
            int i = 0;
            for (Iterator iter = c.iterator(); iter.hasNext(); i++) 
                rv[i] = (String)iter.next();
            return rv;
        } else {
            return null;
        }
    }
    
    private static final int getInt(Map param, String key, int defaultVal) {
        String val = getString(param, key);
        if (val != null) {
            try { return Integer.parseInt(val); } catch (NumberFormatException nfe) {}
        }
        return defaultVal;
    }
    
    private static final boolean getBool(Map param, String key, boolean defaultVal) {
        String val = getString(param, key);
        if (val != null) {
            return ("true".equals(val) || "yes".equals(val));
        }
        return defaultVal;
    }
    
    public static void renderAttachment(Map parameters, OutputStream out) throws IOException {
        Attachment a = getAttachment(parameters);
        if (a == null) {
            renderInvalidAttachment(parameters, out);
        } else {
            InputStream data = a.getDataStream();
            byte buf[] = new byte[1024];
            int read = 0;
            while ( (read = data.read(buf)) != -1) 
                out.write(buf, 0, read);
            data.close();
        }
    }
    
    public static final String getAttachmentContentType(Map parameters) {
        Attachment a = getAttachment(parameters);
        if (a == null) 
            return "text/html";
        String mime = a.getMimeType();
        if ( (mime != null) && ((mime.startsWith("image/") || mime.startsWith("text/plain"))) )
            return mime;
        return "application/octet-stream";
    }
    
    public static final boolean getAttachmentShouldShowInline(Map parameters) {
        Attachment a = getAttachment(parameters);
        if (a == null) 
            return true;
        String mime = a.getMimeType();
        if ( (mime != null) && ((mime.startsWith("image/") || mime.startsWith("text/plain"))) )
            return true;
        else
            return false;
    }
    
    public static final int getAttachmentContentLength(Map parameters) {
        Attachment a = getAttachment(parameters);
        if (a != null)
            return a.getDataLength();
        else
            return -1;
    }
    
    public static final String getAttachmentFilename(Map parameters) {
        Attachment a = getAttachment(parameters);
        if (a != null)
            return a.getName();
        else
            return "attachment.dat";
    }
    
    private static final Attachment getAttachment(Map parameters) {
        String blogStr = getString(parameters, PARAM_BLOG);
        Hash blog = null;
        if (blogStr != null) blog = new Hash(Base64.decode(blogStr));
        long entryId = -1;
        if (blogStr != null) {
            String entryIdStr = getString(parameters, PARAM_ENTRY);
            try { 
                entryId = Long.parseLong(entryIdStr); 
            } catch (NumberFormatException nfe) {}
        }
        int attachment = getInt(parameters, PARAM_ATTACHMENT, -1);
        
        Archive archive = BlogManager.instance().getArchive();
        EntryContainer entry = archive.getEntry(new BlogURI(blog, entryId));
        if ( (entry != null) && (attachment >= 0) && (attachment < entry.getAttachments().length) ) {
            return entry.getAttachments()[attachment];
        }
        return null;
    }
    
    private static void renderInvalidAttachment(Map parameters, OutputStream out) throws IOException {
        out.write(DataHelper.getUTF8("<span class=\"b_msgErr\">No such entry, or no such attachment</span>"));
    }
    
    private static String getURL(String uri, Map parameters) {
        StringBuffer rv = new StringBuffer(128);
        rv.append(uri);
        rv.append('?');
        if (parameters != null) {
            for (Iterator iter = parameters.keySet().iterator(); iter.hasNext(); ) {
                String key = (String)iter.next();
                String vals[] = getStrings(parameters, key);
                // we are already looking at the page with the given parameters, no need to further sanitize
                if ( (key != null) && (vals != null) ) 
                    for (int i = 0; i < vals.length; i++)
                        rv.append(key).append('=').append(vals[i]).append('&');
            }
        }
        return rv.toString();
    }
    
    private static void updateMetadata(User viewer, Map parameters, Writer out) throws IOException {
        if ( (viewer == null) || (!viewer.getAuthenticated()) )
            return;
        String blogStr = getString(parameters, PARAM_BLOG);
        if (blogStr != null) { 
            Hash blog = new Hash(Base64.decode(blogStr));
            Archive archive = BlogManager.instance().getArchive();
            BlogInfo info = archive.getBlogInfo(blog);
            if (info != null) {
                boolean isUser = viewer.getBlog().equals(info.getKey().calculateHash());
                if (!isUser)
                    return;
                Properties toSave = new Properties();
                String existing[] = info.getProperties();
                for (int i = 0; i < existing.length; i++) {
                    String newVal = getString(parameters, existing[i]);
                    if ( (newVal != null) && (newVal.length() > 0) )
                        toSave.setProperty(existing[i], newVal.trim());
                    else
                        toSave.setProperty(existing[i], info.getProperty(existing[i]));
                }
                boolean saved = BlogManager.instance().updateMetadata(viewer, blog, toSave);
                if (saved)
                    out.write("<p><em class=\"b_msgOk\">Blog metadata saved</em></p>\n");
                else
                    out.write("<p><em class=\"b_msgErr\">Blog metadata could not be saved</em></p>\n");
            }
        }
    }
    
    /**
     * @param currentURI URI of the with current page without any parameters tacked on
     */
    public static void renderMetadata(User viewer, String currentURI, Map parameters, Writer out) throws IOException {
        if (parameters.get("action") != null) {
            updateMetadata(viewer, parameters, out);
        }
        String blogStr = getString(parameters, PARAM_BLOG);
        if (blogStr != null) { 
            Hash blog = new Hash(Base64.decode(blogStr));
            Archive archive = BlogManager.instance().getArchive();
            BlogInfo info = archive.getBlogInfo(blog);
            if (info == null) {
                out.write("Blog " + blog.toBase64() + " does not exist");
                return;
            }
            boolean isUser = ( (viewer != null) && (viewer.getAuthenticated()) && (viewer.getBlog().equals(info.getKey().calculateHash())) );
            String props[] = info.getProperties();
            if (isUser) {
                out.write("<form action=\"" + getURL(currentURI, parameters) + "\" method=\"GET\">\n");
                out.write("<input type=\"hidden\" name=\"submit_blog\" value=\"" + blog.toBase64() + "\" />\n");
            }
            out.write("<table class=\"b_meta\" border=\"0\">");
            for (int i = 0; i < props.length; i++) {
                if (props[i].equals(BlogInfo.OWNER_KEY)) {
                    out.write("<tr class=\"b_metaBlog\"><td class=\"b_metaBlog\"><span class=\"b_metaBlog\">Blog:</span></td>");
                    String blogURL = HTMLRenderer.getPageURL(blog, null, -1, -1, -1, false, false);
                    out.write("<td class=\"b_metaBlog\"><a class=\"b_metaBlog\" href=\"" + blogURL + "\">" + Base64.encode(blog.getData()) + "</td></tr>\n");
                } else if (props[i].equals(BlogInfo.SIGNATURE)) {
                    continue;
                } else if (props[i].equals(BlogInfo.POSTERS)) {
                    SigningPublicKey keys[] = info.getPosters();
                    if ( (keys != null) && (keys.length > 0) ) {
                        out.write("<tr class=\"b_metaAuthor\"><td class=\"b_metaAuthor\"><span class=\"b_metaAuthor\">Allowed authors:</span></td>");
                        out.write("<td class=\"b_metaAuthor\">");
                        for (int j = 0; j < keys.length; j++) {
                            out.write("<span class=\"b_metaAuthor\">" + keys[j].calculateHash().toBase64() + "</span>");
                            if (j + 1 < keys.length)
                                out.write("<br />\n");
                        }
                        out.write("</td></tr>\n");
                    }
                } else {
                    String field = HTMLRenderer.sanitizeString(props[i]);
                    String val = HTMLRenderer.sanitizeString(info.getProperty(props[i]));
                    out.write("<tr class=\"b_metaField\"><td class=\"b_metaField\"><span class=\"b_metaField\">" + field
                              + ":</span></td><td class=\"b_metaValue\"><span class=\"b_metaValue\">" + val + "</span></td></tr>\n");
                    
                    if (isUser && (!field.equals("Edition")))
                        out.write("<tr class=\"b_metaField\"><td>&nbsp;</td><td class=\"b_metaValue\"><input type=\"text\" name=\"" 
                                  + HTMLRenderer.sanitizeTagParam(props[i]) + "\" value=\"" 
                                  + HTMLRenderer.sanitizeTagParam(info.getProperty(props[i])) + "\" size=\"40\" ></td></tr>");
                }
            }
            List tags = BlogManager.instance().getArchive().getIndex().getBlogTags(blog);
            if ( (tags != null) && (tags.size() > 0) ) {
                out.write("<tr class=\"b_metaTags\"><td class=\"b_metaTags\"><span class=\"b_metaTags\">Known tags:</span></td><td class=\"b_metaTags\">");
                for (int i = 0; i < tags.size(); i++) {
                    String tag = (String)tags.get(i);
                    out.write("<a class=\"b_metaTag\" href=\"" + HTMLRenderer.getPageURL(blog, tag, -1, -1, -1, false, false) + "\">" +
                              HTMLRenderer.sanitizeString(tag) + "</a> ");
                }
                out.write("</td></tr>");
            }
            if (isUser)
                out.write("<tr class=\"b_metaField\"><td colspan=\"2\" class=\"b_metaField\"><input type=\"submit\" name=\"action\" value=\"Save changes\" class=\"b_metaSave\" /></td></tr>\n");
            out.write("</table>");
        } else {
            out.write("<span class=\"b_metaMsgErr\">Blog not specified</span>");
        }
    }
}
