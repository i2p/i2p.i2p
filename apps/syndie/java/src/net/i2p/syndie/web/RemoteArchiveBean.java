package net.i2p.syndie.web;

import java.io.*;
import java.text.*;
import java.util.*;
import java.util.zip.*;
import net.i2p.I2PAppContext;
import net.i2p.client.naming.PetNameDB;
import net.i2p.client.naming.PetName;
import net.i2p.data.*;
import net.i2p.util.EepGet;
import net.i2p.util.EepGetScheduler;
import net.i2p.util.EepPost;
import net.i2p.syndie.data.*;
import net.i2p.syndie.sml.*;
import net.i2p.syndie.*;
import net.i2p.util.Log;

/**
 *
 */
public class RemoteArchiveBean {
    private I2PAppContext _context;
    private Log _log;
    private String _remoteSchema;
    private String _remoteLocation;
    private String _proxyHost;
    private int _proxyPort;
    private ArchiveIndex _remoteIndex;
    private List _statusMessages;
    private boolean _fetchIndexInProgress;
    private boolean _exportCapable;
    
    public RemoteArchiveBean() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(RemoteArchiveBean.class);
        reinitialize();
    }
    public void reinitialize() {
        _remoteSchema = null;
        _remoteLocation = null;
        _remoteIndex = null;
        _fetchIndexInProgress = false;
        _proxyHost = null;
        _proxyPort = -1;
        _exportCapable = false;
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
    
    private boolean ignoreBlog(User user, Hash blog) {
        PetNameDB db = user.getPetNameDB();
        PetName pn = db.getByLocation(blog.toBase64());
        return ( (pn!= null) && (pn.isMember("Ignore")) );
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
                    if (!ignoreBlog(user, blog))
                        blogs.add(blog);
                }
            }
        } else {
            byte h[] = Base64.decode(meta.trim());
            if (h != null) {
                Hash blog = new Hash(h);
                if (!ignoreBlog(user, blog))
                    blogs.add(blog);
            }
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
            BlogURI uri = new BlogURI(entries[i]);
            if (ignoreBlog(user, uri.getKeyHash()))
                continue;
            urls.add(buildEntryURL(uri));
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
    
    public void fetchSelectedBulk(User user, Map parameters) {
        String entries[] = ArchiveViewerBean.getStrings(parameters, "entry");
        String action = ArchiveViewerBean.getString(parameters, "action");
        if ("Fetch all new entries".equals(action)) {
            ArchiveIndex localIndex = BlogManager.instance().getArchive().getIndex();
            List uris = new ArrayList();
            List matches = new ArrayList();
            for (Iterator iter = _remoteIndex.getUniqueBlogs().iterator(); iter.hasNext(); ) {
                Hash blog = (Hash)iter.next();
                if (ignoreBlog(user, blog))
                    continue;
                
                _remoteIndex.selectMatchesOrderByEntryId(matches, blog, null);
                for (int i = 0; i < matches.size(); i++) {
                    BlogURI uri = (BlogURI)matches.get(i);
                    if (!localIndex.getEntryIsKnown(uri))
                        uris.add(uri);
                }
                matches.clear();
            }
            entries = new String[uris.size()];
            for (int i = 0; i < uris.size(); i++)
                entries[i] = ((BlogURI)uris.get(i)).toString();
        }
        if ( (entries == null) || (entries.length <= 0) ) return;
        if (_exportCapable) {
            StringBuffer url = new StringBuffer(512);
            url.append(buildExportURL());
            StringBuffer postData = new StringBuffer(512);
            Set meta = new HashSet();
            for (int i = 0; i < entries.length; i++) {
                BlogURI uri = new BlogURI(entries[i]);
                if (uri.getEntryId() >= 0) {
                    postData.append("entry=").append(uri.toString()).append('&');
                    meta.add(uri.getKeyHash());
                    _statusMessages.add("Scheduling bulk blog post fetch of " + HTMLRenderer.sanitizeString(entries[i]));
                }
            }
            for (Iterator iter = meta.iterator(); iter.hasNext(); ) {
                Hash blog = (Hash)iter.next();
                postData.append("meta=").append(blog.toBase64()).append('&');
                _statusMessages.add("Scheduling bulk blog metadata fetch of " + blog.toBase64());
            }
            try {
                File tmp = File.createTempFile("fetchBulk", ".zip", BlogManager.instance().getTempDir());
                
                boolean shouldProxy = (_proxyHost != null) && (_proxyPort > 0);
                EepGet get = new EepGet(_context, shouldProxy, _proxyHost, _proxyPort, 0, tmp.getAbsolutePath(), url.toString(), postData.toString());
                get.addStatusListener(new BulkFetchListener(tmp));
                get.fetch();
            } catch (IOException ioe) {
                _statusMessages.add("Internal error creating temporary file to fetch " + HTMLRenderer.sanitizeString(url.toString()) + ": " + ioe.getMessage());
            }
        } else {
            List urls = new ArrayList(entries.length+8);
            for (int i = 0; i < entries.length; i++) {
                BlogURI uri = new BlogURI(entries[i]);
                if (uri.getEntryId() >= 0) {
                    String metaURL = buildMetaURL(uri.getKeyHash());
                    if (!urls.contains(metaURL)) {
                        urls.add(metaURL);
                        _statusMessages.add("Scheduling blog metadata fetch of " + HTMLRenderer.sanitizeString(entries[i]));
                    }
                    urls.add(buildEntryURL(uri));
                    _statusMessages.add("Scheduling blog post fetch of " + HTMLRenderer.sanitizeString(entries[i]));
                }
            }
            List tmpFiles = new ArrayList(1);
            try {
                for (int i = 0; i < urls.size(); i++) {
                    File t = File.createTempFile("fetchBulk", ".dat", BlogManager.instance().getTempDir());
                    tmpFiles.add(t);
                }
                fetch(urls, tmpFiles, user, new BlogStatusListener());
            } catch (IOException ioe) {
                _statusMessages.add("Internal error creating temporary file to fetch posts: " + HTMLRenderer.sanitizeString(urls.toString()));
            }
        }
    }
    
    private String buildExportURL() {
        String loc = _remoteLocation.trim();
        int root = loc.lastIndexOf('/');
        return loc.substring(0, root + 1) + "export.zip?";
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
            if (ignoreBlog(user, blog))
                continue;
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
        _exportCapable = false;
        if (user == null) user = new User();
        
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
        
        File archiveFile;
        if (user.getBlog() != null) {
            archiveFile = new File(BlogManager.instance().getTempDir(), user.getBlog().toBase64() + "_remoteArchive.txt");
        } else {
            archiveFile = new File(BlogManager.instance().getTempDir(), "remoteArchive.txt");
        }
        archiveFile.delete();
        
        Properties etags = new Properties();
        try {
            DataHelper.loadProps(etags, new File(BlogManager.instance().getRootDir(), "etags"));
        } catch (IOException ioe) {
            //ignore
        }
        
        EepGet eep = new EepGet(I2PAppContext.getGlobalContext(), ((_proxyHost != null) && (_proxyPort > 0)),
                                _proxyHost, _proxyPort, 0, archiveFile.getAbsolutePath(), location, true, etags.getProperty(location));
        eep.addStatusListener(new IndexFetcherStatusListener(archiveFile));
        eep.fetch();
        
        if (eep.getETag() != null) { 
            etags.setProperty(location, eep.getETag());
        }
        try {
            DataHelper.storeProps(etags, new File(BlogManager.instance().getRootDir(), "etags"));
        } catch (IOException ioe) {
            //ignore
        }
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
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
            _statusMessages.add("Fetch of " + HTMLRenderer.sanitizeString(url) + " successful");
            _fetchIndexInProgress = false;
            ArchiveIndex i = new ArchiveIndex(I2PAppContext.getGlobalContext(), false);
            if (notModified) {
                _statusMessages.add("Archive unchanged since last fetch.");
            } else {
                try {
                    i.load(_archiveFile);
                    _statusMessages.add("Archive fetched and loaded");
                    _remoteIndex = i;
                } catch (IOException ioe) {
                    _statusMessages.add("Archive is corrupt: " + ioe.getMessage());
                }
            }
        }
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            _statusMessages.add("Fetch of " + HTMLRenderer.sanitizeString(url) + " failed after " + bytesTransferred);
            _fetchIndexInProgress = false;
        }
        public void headerReceived(String url, int currentAttempt, String key, String val) {
            if (ArchiveServlet.HEADER_EXPORT_CAPABLE.equals(key) && ("true".equals(val))) {
                _statusMessages.add("Remote archive is bulk export capable");
                _exportCapable = true;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Header received: [" + key + "] = [" + val + "]");
            }
        }
    }
    
    private class MetadataStatusListener implements EepGet.StatusListener {
        public MetadataStatusListener() {}
        public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
            _statusMessages.add("Attempt " + currentAttempt + " failed after " + bytesTransferred + (cause != null ? cause.getMessage() : ""));
        }
        
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {}
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
            handleMetadata(url, outputFile);
        }
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            _statusMessages.add("Fetch of " + HTMLRenderer.sanitizeString(url) + " failed after " + bytesTransferred);;
        }
        public void headerReceived(String url, int currentAttempt, String key, String val) {}
    }
    
    private void handleMetadata(String url, String outputFile) {
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
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error handling metadata", ioe);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            info.delete();
        }
    }
    
    private class BlogStatusListener implements EepGet.StatusListener {
        public BlogStatusListener() {}
        public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
            _statusMessages.add("Attempt " + currentAttempt + " failed after " + bytesTransferred + (cause != null ? cause.getMessage() : ""));
        }
        
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {}
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
            if (url.endsWith(".snm")) {
                handleMetadata(url, outputFile);
                return;
            }
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
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error importing", ioe);
            } finally {
                if (in != null) try { in.close(); } catch (IOException ioe) {}
                file.delete();
            }
        }
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            _statusMessages.add("Fetch of " + HTMLRenderer.sanitizeString(url) + " failed after " + bytesTransferred);
        }
        public void headerReceived(String url, int currentAttempt, String key, String val) {}
    }
    
    /**
     * Receive the status of a fetch for the zip containing blogs and metadata (as generated by
     * the ExportServlet)
     */
    private class BulkFetchListener implements EepGet.StatusListener {
        private File _tmp;
        public BulkFetchListener(File tmp) {
            _tmp = tmp;
        }
        public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
            _statusMessages.add("Attempt " + currentAttempt + " failed after " + bytesTransferred + (cause != null ? cause.getMessage() : ""));
        }
        
        public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {}
        public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
            _statusMessages.add("Fetch of " + HTMLRenderer.sanitizeString(url.substring(0, url.indexOf('?'))) + " successful, importing the data");
            File file = new File(outputFile);
            ZipInputStream zi = null;
            try {
                zi = new ZipInputStream(new FileInputStream(file));
                
                while (true) {
                    ZipEntry entry = zi.getNextEntry();
                    if (entry == null)
                        break;

                    ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
                    byte buf[] = new byte[1024];
                    int read = -1;
                    while ( (read = zi.read(buf)) != -1) 
                        out.write(buf, 0, read);

                    if (entry.getName().startsWith("meta")) {
                        BlogInfo i = new BlogInfo();
                        i.load(new ByteArrayInputStream(out.toByteArray()));
                        boolean ok = BlogManager.instance().getArchive().storeBlogInfo(i);
                        if (ok) {
                            _statusMessages.add("Blog info for " + HTMLRenderer.sanitizeString(i.getProperty(BlogInfo.NAME)) + " imported");
                        } else {
                            _statusMessages.add("Blog info at " + HTMLRenderer.sanitizeString(url) + " was corrupt / invalid / forged");
                        }
                    } else if (entry.getName().startsWith("entry")) {
                        EntryContainer c = new EntryContainer();
                        c.load(new ByteArrayInputStream(out.toByteArray()));
                        BlogURI uri = c.getURI();
                        if ( (uri == null) || (uri.getKeyHash() == null) ) {
                            _statusMessages.add("Blog post " + HTMLRenderer.sanitizeString(entry.getName()) + " was corrupt - no URI");
                            continue;
                        }
                        Archive a = BlogManager.instance().getArchive();
                        BlogInfo info = a.getBlogInfo(uri);
                        if (info == null) {
                            _statusMessages.add("Blog post " + HTMLRenderer.sanitizeString(entry.getName()) + " cannot be imported, as we don't have their blog metadata");
                            continue;
                        }
                        boolean ok = a.storeEntry(c);
                        if (!ok) {
                            _statusMessages.add("Blog post " + uri.toString() + " has an invalid signature");
                            continue;
                        } else {
                            _statusMessages.add("Blog post " + uri.toString() + " imported");
                        }
                    }
                }       
                
                BlogManager.instance().getArchive().regenerateIndex();
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.WARN))
                    _log.debug("Error importing", ioe);
                _statusMessages.add("Error importing from " + HTMLRenderer.sanitizeString(url) + ": " + ioe.getMessage());
            } finally {
                if (zi != null) try { zi.close(); } catch (IOException ioe) {}
                file.delete();
            }
        }
        public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
            _statusMessages.add("Fetch of " + HTMLRenderer.sanitizeString(url) + " failed after " + bytesTransferred);
            _tmp.delete();
        }
        public void headerReceived(String url, int currentAttempt, String key, String val) {}
    }
    
    public void postSelectedEntries(User user, Map parameters) {
        postSelectedEntries(user, parameters, _proxyHost, _proxyPort, _remoteLocation);
    }
    public void postSelectedEntries(User user, Map parameters, String proxyHost, int proxyPort, String location) {
        String entries[] = ArchiveViewerBean.getStrings(parameters, "localentry");
        if ( (entries == null) || (entries.length <= 0) ) return;
        List uris = new ArrayList(entries.length);
        for (int i = 0; i < entries.length; i++)
            uris.add(new BlogURI(entries[i]));
        if ( (proxyPort > 0) && (proxyHost != null) && (proxyHost.trim().length() > 0) ) {
            _proxyPort = proxyPort;
            _proxyHost = proxyHost;
        } else {
            _proxyPort = -1;
            _proxyHost = null;
        }
        _remoteLocation = location;
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
        buf.append("<em class=\"b_remMeta\">New blogs:</em> <select class=\"b_remMeta\"name=\"blog\"><option value=\"ALL\">All</option>\n");
        Set localBlogs = archive.getIndex().getUniqueBlogs();
        Set remoteBlogs = _remoteIndex.getUniqueBlogs();
        int newBlogs = 0;
        for (Iterator iter = remoteBlogs.iterator(); iter.hasNext(); ) {
            Hash blog = (Hash)iter.next();
            if (ignoreBlog(user, blog))
                continue;
            if (!localBlogs.contains(blog)) {
                buf.append("<option value=\"" + blog.toBase64() + "\">" + blog.toBase64() + "</option>\n");
                newBlogs++;
            }
        }
        if (newBlogs > 0) {
            out.write(buf.toString());
            out.write("</select> <input class=\"b_remMetaFetch\" type=\"submit\" name=\"action\" value=\"Fetch metadata\" /><br />\n");
        }
        
        int newEntries = 0;
        int localNew = 0;
        out.write("<table class=\"b_remDelta\" border=\"1\" width=\"100%\">\n");
        List entries = new ArrayList();
        for (Iterator iter = remoteBlogs.iterator(); iter.hasNext(); ) {
            Hash blog = (Hash)iter.next();
            if (ignoreBlog(user, blog))
                continue;
            buf.setLength(0);
            int shownEntries = 0;
            buf.append("<tr class=\"b_remBlog\"><td class=\"b_remBlog\" colspan=\"5\" align=\"left\" valign=\"top\">\n");
            BlogInfo info = archive.getBlogInfo(blog);
            if (info != null) {
                buf.append("<a class=\"b_remBlog\" href=\"");
                buf.append(HTMLRenderer.getPageURL(blog, null, -1, -1, -1, user.getShowExpanded(), user.getShowImages()));
                buf.append("\">").append(HTMLRenderer.sanitizeString(info.getProperty(BlogInfo.NAME))).append("</a>: ");
                buf.append("<span class=\"b_remBlogDesc\">").append(HTMLRenderer.sanitizeString(info.getProperty(BlogInfo.DESCRIPTION)));
                buf.append("</span>\n");
            } else {
                buf.append("<span class=\"b_remBlog\">" + blog.toBase64() + "</span>\n");
            }
            buf.append("</td></tr>\n");
            buf.append("<tr class=\"b_remHeader\"><td class=\"b_remHeader\">&nbsp;</td><td class=\"b_remHeader\" nowrap=\"nowrap\">");
            buf.append("<em class=\"b_remHeader\">Posted on</em></td>");
            buf.append("<td class=\"b_remHeader\" nowrap=\"nowrap\"><em class=\"b_remHeader\">#</em></td>");
            buf.append("<td class=\"b_remHeader\" nowrap=\"nowrap\"><em class=\"b_remHeader\">Size</em></td>");
            buf.append("<td class=\"b_remHeader\" width=\"90%\" nowrap=\"true\"><em class=\"b_remHeader\">Tags</em></td></tr>\n");
            entries.clear();
            _remoteIndex.selectMatchesOrderByEntryId(entries, blog, null);
            for (int i = 0; i < entries.size(); i++) {
                BlogURI uri = (BlogURI)entries.get(i);
                buf.append("<tr class=\"b_remDetail\">\n");
                if (!archive.getIndex().getEntryIsKnown(uri)) {
                    buf.append("<td class=\"b_remDetail\"><input class=\"b_remSelect\" type=\"checkbox\" name=\"entry\" value=\"" + uri.toString() + "\" /></td>\n");
                    newEntries++;
                    shownEntries++;
                } else {
                    String page = HTMLRenderer.getPageURL(blog, null, uri.getEntryId(), -1, -1,
                    user.getShowExpanded(), user.getShowImages());
                    buf.append("<td class=\"b_remDetail\"><a class=\"b_remLocal\" href=\"" + page + "\">(local)</a></td>\n");
                }
                buf.append("<td class=\"b_remDetail\"><span class=\"b_remDate\">" + getDate(uri.getEntryId()) + "</span></td>\n");
                buf.append("<td class=\"b_remDetail\"><span class=\"b_remNum\">" + getId(uri.getEntryId()) + "</span></td>\n");
                buf.append("<td class=\"b_remDetail\"><span class=\"b_remSize\">" + _remoteIndex.getBlogEntrySizeKB(uri) + "KB</span></td>\n");
                buf.append("<td class=\"b_remDetail\">");
                for (Iterator titer = new TreeSet(_remoteIndex.getBlogEntryTags(uri)).iterator(); titer.hasNext(); ) {
                    String tag = (String)titer.next();
                    buf.append("<a class=\"b_remTag\" href=\"" + HTMLRenderer.getPageURL(blog, tag, -1, -1, -1, user.getShowExpanded(), user.getShowImages()) + "\">" + tag + "</a> \n");
                }
                buf.append("</td>\n");
                buf.append("</tr>\n");
            }
            if (shownEntries > 0) {
                out.write(buf.toString());
                buf.setLength(0);
            }
            int remote = shownEntries;
            
            // now for posts in known blogs that we have and they don't
            entries.clear();
            localIndex.selectMatchesOrderByEntryId(entries, blog, null);
            buf.append("<tr class=\"b_remLocalHeader\"><td class=\"b_remLocalHeader\" colspan=\"5\"><span class=\"b_remLocalHeader\">Entries we have, but the remote Syndie doesn't:</span></td></tr>\n");
            for (int i = 0; i < entries.size(); i++) {
                BlogURI uri = (BlogURI)entries.get(i);
                if (!_remoteIndex.getEntryIsKnown(uri)) {
                    buf.append("<tr class=\"b_remLocalDetail\">\n");
                    buf.append("<td class=\"b_remLocalDetail\"><input class=\"b_remLocalSend\" type=\"checkbox\" name=\"localentry\" value=\"" + uri.toString() + "\" /></td>\n");
                    shownEntries++;
                    newEntries++;
                    localNew++;
                    buf.append("<td class=\"b_remLocalDate\"><span class=\"b_remLocalDate\">" + getDate(uri.getEntryId()) + "</span></td>\n");
                    buf.append("<td class=\"b_remLocalNum\"><span class=\"b_remLocalNum\">" + getId(uri.getEntryId()) + "</span></td>\n");
                    buf.append("<td class=\"b_remLocalSize\"><span class=\"b_remLocalSize\">" + localIndex.getBlogEntrySizeKB(uri) + "KB</span></td>\n");
                    buf.append("<td class=\"b_remLocalTags\">");
                    for (Iterator titer = new TreeSet(localIndex.getBlogEntryTags(uri)).iterator(); titer.hasNext(); ) {
                        String tag = (String)titer.next();
                        buf.append("<a class=\"b_remLocalTag\" href=\"" + HTMLRenderer.getPageURL(blog, tag, -1, -1, -1, user.getShowExpanded(), user.getShowImages()) + "\">" + tag + "</a> \n");
                    }
                    buf.append("</td>\n");
                    buf.append("</tr>\n");
                }
            }
            
            if (shownEntries > remote) // skip blogs we have already syndicated
                out.write(buf.toString());
        }

        // now for posts in blogs we have and they don't
        int newBefore = localNew;
        buf.setLength(0);
        buf.append("<tr class=\"b_remLocalHeader\"><td class=\"b_remLocalHeader\" colspan=\"5\"><span class=\"b_remLocalHeader\">Blogs the remote Syndie doesn't have</span></td></tr>\n");
        for (Iterator iter = localBlogs.iterator(); iter.hasNext(); ) {
            Hash blog = (Hash)iter.next();
            if (remoteBlogs.contains(blog)) {
                //System.err.println("Remote index has " + blog.toBase64());
                continue;
            } else if (ignoreBlog(user, blog)) {
                continue;
            }
            
            entries.clear();
            localIndex.selectMatchesOrderByEntryId(entries, blog, null);
            
            for (int i = 0; i < entries.size(); i++) {
                BlogURI uri = (BlogURI)entries.get(i);
                buf.append("<tr class=\"b_remLocalDetail\">\n");
                buf.append("<td class=\"b_remLocalDetail\"><input class=\"b_remLocalSend\" type=\"checkbox\" name=\"localentry\" value=\"" + uri.toString() + "\" /></td>\n");
                buf.append("<td class=\"b_remLocalDate\"><span class=\"b_remLocalDate\">" + getDate(uri.getEntryId()) + "</span></td>\n");
                buf.append("<td class=\"b_remLocalNum\"><span class=\"b_remLocalNum\">" + getId(uri.getEntryId()) + "</span></td>\n");
                buf.append("<td class=\"b_remLocalSize\"><span class=\"b_remLocalSize\">" + localIndex.getBlogEntrySizeKB(uri) + "KB</span></td>\n");
                buf.append("<td class=\"b_remLocalTags\">");
                for (Iterator titer = new TreeSet(localIndex.getBlogEntryTags(uri)).iterator(); titer.hasNext(); ) {
                    String tag = (String)titer.next();
                    buf.append("<a class=\"b_remLocalTag\" href=\"" + HTMLRenderer.getPageURL(blog, tag, -1, -1, -1, user.getShowExpanded(), user.getShowImages()) + "\">" + tag + "</a> \n");
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
            out.write("<input class=\"b_remFetchSelected\" type=\"submit\" name=\"action\" value=\"Fetch selected entries\" /> \n");
            out.write("<input class=\"b_remFetchAll\" type=\"submit\" name=\"action\" value=\"Fetch all new entries\" /> \n");
        } else {
            out.write("<span class=\"b_remNoRemotePosts\">" + HTMLRenderer.sanitizeString(_remoteLocation) + " has no new posts to offer us</span>\n");
        }
        if (localNew > 0) {
            out.write("<input class=\"b_remPostSelected\" type=\"submit\" name=\"action\" value=\"Post selected entries\" /> \n");
        }
        out.write("<hr />\n");
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
