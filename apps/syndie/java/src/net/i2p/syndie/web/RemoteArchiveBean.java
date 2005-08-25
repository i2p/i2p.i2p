package net.i2p.syndie.web;

import java.io.*;
import java.text.*;
import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.data.*;
import net.i2p.util.EepGet;
import net.i2p.util.EepGetScheduler;
import net.i2p.util.EepPost;
import net.i2p.syndie.data.*;
import net.i2p.syndie.sml.*;
import net.i2p.syndie.*;

/**
 *
 */
public class RemoteArchiveBean {
    private String _remoteSchema;
    private String _remoteLocation;
    private String _proxyHost;
    private int _proxyPort;
    private ArchiveIndex _remoteIndex;
    private List _statusMessages;
    private boolean _fetchIndexInProgress;
    
    public RemoteArchiveBean() {
        reinitialize();
    }
    public void reinitialize() {
        _remoteSchema = null;
        _remoteLocation = null;
        _remoteIndex = null;
        _fetchIndexInProgress = false;
        _proxyHost = null;
        _proxyPort = -1;
        _statusMessages = new ArrayList();
    }
    
    public String getRemoteSchema() { return _remoteSchema; }
    public String getRemoteLocation() { return _remoteLocation; }
    public ArchiveIndex getRemoteIndex() { return _remoteIndex; }
    public String getProxyHost() { return _proxyHost; }
    public int getProxyPort() { return _proxyPort; }
    public boolean getFetchIndexInProgress() { return _fetchIndexInProgress; }
    public String getStatus() {
        StringBuffer buf = new StringBuffer();
        while (_statusMessages.size() > 0)
            buf.append(_statusMessages.remove(0)).append("\n");
        return buf.toString();
    }
    
    public void fetchMetadata(User user, Map parameters) {
        String meta = ArchiveViewerBean.getString(parameters, "blog");
        if (meta == null) return;
        Set blogs = new HashSet();
        if ("ALL".equals(meta)) {
            Set localBlogs = BlogManager.instance().getArchive().getIndex().getUniqueBlogs();
            Set remoteBlogs = _remoteIndex.getUniqueBlogs();
            for (Iterator iter = remoteBlogs.iterator(); iter.hasNext(); ) {
                Hash blog = (Hash)iter.next();
                if (!localBlogs.contains(blog)) {
                    blogs.add(blog);
                }
            }
        } else {
            blogs.add(new Hash(Base64.decode(meta.trim())));
        }
        List urls = new ArrayList(blogs.size());
        List tmpFiles = new ArrayList(blogs.size());
        for (Iterator iter = blogs.iterator(); iter.hasNext(); ) {
            Hash blog = (Hash)iter.next();
            urls.add(buildMetaURL(blog));
            try {
                tmpFiles.add(File.createTempFile("fetchMeta", ".txt", BlogManager.instance().getTempDir()));
            } catch (IOException ioe) {
                _statusMessages.add("Internal error creating temporary file to fetch " + blog.toBase64() + ": " + ioe.getMessage());
            }
        }
        
        for (int i = 0; i < urls.size(); i++)
            _statusMessages.add("Scheduling up metadata fetches for " + HTMLRenderer.sanitizeString((String)urls.get(i)));
        fetch(urls, tmpFiles, user, new MetadataStatusListener());
    }
    
    private String buildMetaURL(Hash blog) {
        String loc = _remoteLocation.trim();
        int root = loc.lastIndexOf('/');
        return loc.substring(0, root + 1) +  blog.toBase64() + "/" + Archive.METADATA_FILE;
    }
    
    public void fetchSelectedEntries(User user, Map parameters) {
        String entries[] = ArchiveViewerBean.getStrings(parameters, "entry");
        if ( (entries == null) || (entries.length <= 0) ) return;
        List urls = new ArrayList(entries.length);
        List tmpFiles = new ArrayList(entries.length);
        for (int i = 0; i < entries.length; i++) {
            urls.add(buildEntryURL(new BlogURI(entries[i])));
            try {
                tmpFiles.add(File.createTempFile("fetchBlog", ".txt", BlogManager.instance().getTempDir()));
            } catch (IOException ioe) {
                _statusMessages.add("Internal error creating temporary file to fetch " + HTMLRenderer.sanitizeString(entries[i]) + ": " + ioe.getMessage());
            }
        }
        
        for (int i = 0; i < urls.size(); i++)
            _statusMessages.add("Scheduling blog post fetching for " + HTMLRenderer.sanitizeString(entries[i]));
        fetch(urls, tmpFiles, user, new BlogStatusListener());
    }
    
    private String buildEntryURL(BlogURI uri) {
        String loc = _remoteLocation.trim();
        int root = loc.lastIndexOf('/');
        return loc.substring(0, root + 1) + uri.getKeyHash().toBase64() + "/" + uri.getEntryId() + ".snd";
    }
    
    public void fetchAllEntries(User user, Map parameters) {
        ArchiveIndex localIndex = BlogManager.instance().getArchive().getIndex();
        List uris = new ArrayList();
        List entries = new ArrayList();
        for (Iterator iter = _remoteIndex.getUniqueBlogs().iterator(); iter.hasNext(); ) {
            Hash blog = (Hash)iter.next();
            _remoteIndex.selectMatchesOrderByEntryId(entries, blog, null);
            for (int i = 0; i < entries.size(); i++) {
                BlogURI uri = (BlogURI)entries.get(i);
                if (!localIndex.getEntryIsKnown(uri))
                    uris.add(uri);
            }
            entries.clear();
        }
        List urls = new ArrayList(uris.size());
        List tmpFiles = new ArrayList(uris.size());
        for (int i = 0; i < uris.size(); i++) {
            urls.add(buildEntryURL((BlogURI)uris.get(i)));
            try {
                tmpFiles.add(File.createTempFile("fetchBlog", ".txt", BlogManager.instance().getTempDir()));
            } catch (IOException ioe) {
                _statusMessages.add("Internal error creating temporary file to fetch " + HTMLRenderer.sanitizeString(uris.get(i).toString()) + ": " + ioe.getMessage());
            }
        }
        
        for (int i = 0; i < urls.size(); i++)
            _statusMessages.add("Fetch all entries: " + HTMLRenderer.sanitizeString((String)urls.get(i)));
        fetch(urls, tmpFiles, user, new BlogStatusListener());
    }
    
    private void fetch(List urls, List tmpFiles, User user, EepGet.StatusListener lsnr) {
        EepGetScheduler scheduler = new EepGetScheduler(I2PAppContext.getGlobalContext(), urls, tmpFiles, _proxyHost, _proxyPort, lsnr);
        scheduler.fetch();
    }
    
    public void fetchIndex(User user, String schema, String location, String proxyHost, String proxyPort) {
        _fetchIndexInProgress = true;
        _remoteIndex = null;
        _remoteLocation = location;
        _remoteSchema = schema;
        _proxyHost = null;
        _proxyPort = -1;
        
        if ( (schema == null) || (schema.trim().length() <= 0) ||
        (location == null) || (location.trim().length() <= 0) ) {
            _statusMessages.add("Location must be specified");
            _fetchIndexInProgress = false;
            return;
        }
        
        if ("web".equals(schema)) {
            if ( (proxyHost != null) && (proxyHost.trim().length() > 0) &&
            (proxyPort != null) && (proxyPort.trim().length() > 0) ) {
                _proxyHost = proxyHost;
                try {
                    _proxyPort = Integer.parseInt(proxyPort);
                } catch (NumberFormatException nfe) {
                    _statusMessages.add("Proxy port " + HTMLRenderer.sanitizeString(proxyPort) + " is invalid");
                    _fetchIndexInProgress = false;
                    return;
                }
            }
        } else {
            _statusMessages.add(new String("Remote schema " + HTMLRenderer.sanitizeString(schema) + " currently not supported"));
            _fetchIndexInProgress = false;
            return;
        }
        
        _statusMessages.add("Fetching index from " + HTMLRenderer.sanitizeString(_remoteLocation) +
        (_proxyHost != null ? " via " + HTMLRenderer.sanitizeString(_proxyHost) + ":" + _proxyPort : ""));
        File archiveFile = new File(BlogManager.instance().getTempDir(), user.getBlog().toBase64() + "_remoteArchive.txt");
        archiveFile.delete();
        EepGet eep = new EepGet(I2PAppContext.getGlobalContext(), ((_proxyHost != null) && (_proxyPort > 0)),
        _proxyHost, _proxyPort, 0, archiveFile.getAbsolutePath(), location);
        eep.addStatusListener(new IndexFetcherStatusListener(archiveFile));
        eep.fetch();
    }
    
    private class IndexFetcherStatusListener implements EepGet.StatusListener {
        private File _archiveFile;
        public IndexFetcherStatusListener(File file) {
            _archiveFile = file;
        }
        public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
            _statusMessages.add("Attempt " + currentAttempt + " failed after " + bytesTransferred + (cause != null ? cause.getMessage() : ""));
        }
        
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {}
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile) {
            _statusMessages.add("Fetch of " + HTMLRenderer.sanitizeString(url) + " successful");
            _fetchIndexInProgress = false;
            ArchiveIndex i = new ArchiveIndex(false);
            try {
                i.load(_archiveFile);
                _statusMessages.add("Archive fetched and loaded");
                _remoteIndex = i;
            } catch (IOException ioe) {
                _statusMessages.add("Archive is corrupt: " + ioe.getMessage());
            }
        }
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            _statusMessages.add("Fetch of " + HTMLRenderer.sanitizeString(url) + " failed after " + bytesTransferred);
            _fetchIndexInProgress = false;
        }
    }
    
    private class MetadataStatusListener implements EepGet.StatusListener {
        public MetadataStatusListener() {}
        public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
            _statusMessages.add("Attempt " + currentAttempt + " failed after " + bytesTransferred + (cause != null ? cause.getMessage() : ""));
        }
        
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {}
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile) {
            _statusMessages.add("Fetch of " + HTMLRenderer.sanitizeString(url) + " successful");
            File info = new File(outputFile);
            FileInputStream in = null;
            try {
                BlogInfo i = new BlogInfo();
                in = new FileInputStream(info);
                i.load(in);
                boolean ok = BlogManager.instance().getArchive().storeBlogInfo(i);
                if (ok) {
                    _statusMessages.add("Blog info for " + HTMLRenderer.sanitizeString(i.getProperty(BlogInfo.NAME)) + " imported");
                    BlogManager.instance().getArchive().reloadInfo();
                } else {
                    _statusMessages.add("Blog info at " + HTMLRenderer.sanitizeString(url) + " was corrupt / invalid / forged");
                }
            } catch (IOException ioe) {
                ioe.printStackTrace();
            } finally {
                if (in != null) try { in.close(); } catch (IOException ioe) {}
                info.delete();
            }
        }
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            _statusMessages.add("Fetch of " + HTMLRenderer.sanitizeString(url) + " failed after " + bytesTransferred);;
        }
    }
    
    private class BlogStatusListener implements EepGet.StatusListener {
        public BlogStatusListener() {}
        public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
            _statusMessages.add("Attempt " + currentAttempt + " failed after " + bytesTransferred + (cause != null ? cause.getMessage() : ""));
        }
        
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {}
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile) {
            _statusMessages.add("Fetch of " + HTMLRenderer.sanitizeString(url) + " successful");
            File file = new File(outputFile);
            FileInputStream in = null;
            try {
                EntryContainer c = new EntryContainer();
                in = new FileInputStream(file);
                c.load(in);
                BlogURI uri = c.getURI();
                if ( (uri == null) || (uri.getKeyHash() == null) ) {
                    _statusMessages.add("Blog post at " + HTMLRenderer.sanitizeString(url) + " was corrupt - no URI");
                    return;
                }
                Archive a = BlogManager.instance().getArchive();
                BlogInfo info = a.getBlogInfo(uri);
                if (info == null) {
                    _statusMessages.add("Blog post " + uri.toString() + " cannot be imported, as we don't have their blog metadata");
                    return;
                }
                boolean ok = a.storeEntry(c);
                if (!ok) {
                    _statusMessages.add("Blog post at " + url + ": " + uri.toString() + " has an invalid signature");
                    return;
                } else {
                    _statusMessages.add("Blog post " + uri.toString() + " imported");
                    BlogManager.instance().getArchive().regenerateIndex();
                }
            } catch (IOException ioe) {
                ioe.printStackTrace();
            } finally {
                if (in != null) try { in.close(); } catch (IOException ioe) {}
                file.delete();
            }
        }
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            _statusMessages.add("Fetch of " + HTMLRenderer.sanitizeString(url) + " failed after " + bytesTransferred);
        }
    }
    
    
    public void postSelectedEntries(User user, Map parameters) {
        String entries[] = ArchiveViewerBean.getStrings(parameters, "localentry");
        if ( (entries == null) || (entries.length <= 0) ) return;
        List uris = new ArrayList(entries.length);
        for (int i = 0; i < entries.length; i++)
            uris.add(new BlogURI(entries[i]));
        
        post(uris, user);
    }
    
    private void post(List blogURIs, User user) {
        List files = new ArrayList(blogURIs.size()+1);
        Set meta = new HashSet(4);
        Map uploads = new HashMap(files.size());
        String importURL = getImportURL();
        _statusMessages.add("Uploading through " + HTMLRenderer.sanitizeString(importURL));
        for (int i = 0; i < blogURIs.size(); i++) {
            BlogURI uri = (BlogURI)blogURIs.get(i);
            File blogDir = new File(BlogManager.instance().getArchive().getArchiveDir(), uri.getKeyHash().toBase64());
            BlogInfo info = BlogManager.instance().getArchive().getBlogInfo(uri);
            if (!meta.contains(uri.getKeyHash())) {
                uploads.put("blogmeta" + meta.size(), new File(blogDir, Archive.METADATA_FILE));
                meta.add(uri.getKeyHash());
                _statusMessages.add("Scheduling upload of the blog metadata for " + HTMLRenderer.sanitizeString(info.getProperty(BlogInfo.NAME)));
            }
            uploads.put("blogpost" + i, new File(blogDir, uri.getEntryId() + ".snd"));
            _statusMessages.add("Scheduling upload of " + HTMLRenderer.sanitizeString(info.getProperty(BlogInfo.NAME)) 
                                + ": " + getEntryDate(uri.getEntryId()));
        }
        EepPost post = new EepPost();
        post.postFiles(importURL, _proxyHost, _proxyPort, uploads, new Runnable() { public void run() { _statusMessages.add("Upload complete"); } });
    }
    
    private String getImportURL() {
        String loc = _remoteLocation.trim();
        int archiveRoot = loc.lastIndexOf('/');
        int syndieRoot = loc.lastIndexOf('/', archiveRoot-1);
        return loc.substring(0, syndieRoot + 1) + "import.jsp";
    }
    
    public void renderDeltaForm(User user, ArchiveIndex localIndex, Writer out) throws IOException {
        Archive archive = BlogManager.instance().getArchive();
        StringBuffer buf = new StringBuffer(512);
        buf.append("<b>New blogs:</b> <select name=\"blog\"><option value=\"ALL\">All</option>\n");
        Set localBlogs = archive.getIndex().getUniqueBlogs();
        Set remoteBlogs = _remoteIndex.getUniqueBlogs();
        int newBlogs = 0;
        for (Iterator iter = remoteBlogs.iterator(); iter.hasNext(); ) {
            Hash blog = (Hash)iter.next();
            if (!localBlogs.contains(blog)) {
                buf.append("<option value=\"" + blog.toBase64() + "\">" + blog.toBase64() + "</option>\n");
                newBlogs++;
            }
        }
        if (newBlogs > 0) {
            out.write(buf.toString());
            out.write("</select> <input type=\"submit\" name=\"action\" value=\"Fetch metadata\" /><br />\n");
        }
        
        int newEntries = 0;
        int localNew = 0;
        out.write("<table border=\"1\" width=\"100%\">\n");
        List entries = new ArrayList();
        for (Iterator iter = remoteBlogs.iterator(); iter.hasNext(); ) {
            Hash blog = (Hash)iter.next();
            buf = new StringBuffer(1024);
            int shownEntries = 0;
            buf.append("<tr><td colspan=\"5\" align=\"left\" valign=\"top\">\n");
            BlogInfo info = archive.getBlogInfo(blog);
            if (info != null) {
                buf.append("<a href=\"" + HTMLRenderer.getPageURL(blog, null, -1, -1, -1, user.getShowExpanded(), user.getShowImages()) + "\"><b>" + HTMLRenderer.sanitizeString(info.getProperty(BlogInfo.NAME)) + "</b></a>: " +
                HTMLRenderer.sanitizeString(info.getProperty(BlogInfo.DESCRIPTION)) + "\n");
            } else {
                buf.append("<b>" + blog.toBase64() + "</b>\n");
            }
            buf.append("</td></tr>\n");
            buf.append("<tr><td>&nbsp;</td><td nowrap=\"true\"><b>Posted on</b></td><td nowrap=\"true\"><b>#</b></td><td nowrap=\"true\"><b>Size</b></td><td width=\"90%\" nowrap=\"true\"><b>Tags</b></td></tr>\n");
            entries.clear();
            _remoteIndex.selectMatchesOrderByEntryId(entries, blog, null);
            for (int i = 0; i < entries.size(); i++) {
                BlogURI uri = (BlogURI)entries.get(i);
                buf.append("<tr>\n");
                if (!archive.getIndex().getEntryIsKnown(uri)) {
                    buf.append("<td><input type=\"checkbox\" name=\"entry\" value=\"" + uri.toString() + "\" /></td>\n");
                    newEntries++;
                    shownEntries++;
                } else {
                    String page = HTMLRenderer.getPageURL(blog, null, uri.getEntryId(), -1, -1,
                    user.getShowExpanded(), user.getShowImages());
                    buf.append("<td><a href=\"" + page + "\">(local)</a></td>\n");
                }
                buf.append("<td>" + getDate(uri.getEntryId()) + "</td>\n");
                buf.append("<td>" + getId(uri.getEntryId()) + "</td>\n");
                buf.append("<td>" + _remoteIndex.getBlogEntrySizeKB(uri) + "KB</td>\n");
                buf.append("<td>");
                for (Iterator titer = new TreeSet(_remoteIndex.getBlogEntryTags(uri)).iterator(); titer.hasNext(); ) {
                    String tag = (String)titer.next();
                    buf.append("<a href=\"" + HTMLRenderer.getPageURL(blog, tag, -1, -1, -1, user.getShowExpanded(), user.getShowImages()) + "\">" + tag + "</a> \n");
                }
                buf.append("</td>\n");
                buf.append("</tr>\n");
            }
            
            // now for posts in known blogs that we have and they don't
            entries.clear();
            localIndex.selectMatchesOrderByEntryId(entries, blog, null);
            buf.append("<tr><td colspan=\"5\">Entries we have, but the remote Syndie doesn't:</td></tr>\n");
            for (int i = 0; i < entries.size(); i++) {
                BlogURI uri = (BlogURI)entries.get(i);
                if (!_remoteIndex.getEntryIsKnown(uri)) {
                    buf.append("<tr>\n");
                    buf.append("<td><input type=\"checkbox\" name=\"localentry\" value=\"" + uri.toString() + "\" /></td>\n");
                    shownEntries++;
                    newEntries++;
                    localNew++;
                    buf.append("<td>" + getDate(uri.getEntryId()) + "</td>\n");
                    buf.append("<td>" + getId(uri.getEntryId()) + "</td>\n");
                    buf.append("<td>" + localIndex.getBlogEntrySizeKB(uri) + "KB</td>\n");
                    buf.append("<td>");
                    for (Iterator titer = new TreeSet(localIndex.getBlogEntryTags(uri)).iterator(); titer.hasNext(); ) {
                        String tag = (String)titer.next();
                        buf.append("<a href=\"" + HTMLRenderer.getPageURL(blog, tag, -1, -1, -1, user.getShowExpanded(), user.getShowImages()) + "\">" + tag + "</a> \n");
                    }
                    buf.append("</td>\n");
                    buf.append("</tr>\n");
                }
            }
            
            if (shownEntries > 0) // skip blogs we have already syndicated
                out.write(buf.toString());
        }

        // now for posts in blogs we have and they don't
        int newBefore = localNew;
        buf.setLength(0);
        buf.append("<tr><td colspan=\"5\">Blogs the remote Syndie doesn't have</td></tr>\n");
        for (Iterator iter = localBlogs.iterator(); iter.hasNext(); ) {
            Hash blog = (Hash)iter.next();
            if (remoteBlogs.contains(blog)) {
                System.err.println("Remote index has " + blog.toBase64());
                continue;
            }
            
            entries.clear();
            localIndex.selectMatchesOrderByEntryId(entries, blog, null);
            
            for (int i = 0; i < entries.size(); i++) {
                BlogURI uri = (BlogURI)entries.get(i);
                buf.append("<tr>\n");
                buf.append("<td><input type=\"checkbox\" name=\"localentry\" value=\"" + uri.toString() + "\" /></td>\n");
                buf.append("<td>" + getDate(uri.getEntryId()) + "</td>\n");
                buf.append("<td>" + getId(uri.getEntryId()) + "</td>\n");
                buf.append("<td>" + localIndex.getBlogEntrySizeKB(uri) + "KB</td>\n");
                buf.append("<td>");
                for (Iterator titer = new TreeSet(localIndex.getBlogEntryTags(uri)).iterator(); titer.hasNext(); ) {
                    String tag = (String)titer.next();
                    buf.append("<a href=\"" + HTMLRenderer.getPageURL(blog, tag, -1, -1, -1, user.getShowExpanded(), user.getShowImages()) + "\">" + tag + "</a> \n");
                }
                buf.append("</td>\n");
                buf.append("</tr>\n");
                localNew++;
            }
        }
        if (localNew > newBefore)
            out.write(buf.toString());
        
        out.write("</table>\n");
        if (newEntries > 0) {
            out.write("<input type=\"submit\" name=\"action\" value=\"Fetch selected entries\" /> \n");
            out.write("<input type=\"submit\" name=\"action\" value=\"Fetch all new entries\" /> \n");
        } else {
            out.write(HTMLRenderer.sanitizeString(_remoteLocation) + " has no new posts to offer us\n");
        }
        if (localNew > 0) {
            out.write("<input type=\"submit\" name=\"action\" value=\"Post selected entries\" /> \n");
        }
    }
    private final SimpleDateFormat _dateFormat = new SimpleDateFormat("yyyy/MM/dd", Locale.UK);
    private String getDate(long when) {
        synchronized (_dateFormat) {
            return _dateFormat.format(new Date(when));
        }
    }
    private final String getEntryDate(long when) {
        synchronized (_dateFormat) {
            try {
                String str = _dateFormat.format(new Date(when));
                long dayBegin = _dateFormat.parse(str).getTime();
                return str + "." + (when - dayBegin);
            } catch (ParseException pe) {
                pe.printStackTrace();
                // wtf
                return "unknown";
            }
        }
    }
    
    private long getId(long id) {
        synchronized (_dateFormat) {
            try {
                String str = _dateFormat.format(new Date(id));
                long dayBegin = _dateFormat.parse(str).getTime();
                return (id - dayBegin);
            } catch (ParseException pe) {
                pe.printStackTrace();
                // wtf
                return id;
            }
        }
    }
}
