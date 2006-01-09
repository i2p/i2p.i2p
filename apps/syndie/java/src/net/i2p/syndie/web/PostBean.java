package net.i2p.syndie.web;

import java.io.*;
import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.client.naming.PetName;
import net.i2p.syndie.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.sml.HTMLPreviewRenderer;
import net.i2p.syndie.sml.HTMLRenderer;
import net.i2p.util.Log;

/**
 *
 */
public class PostBean {
    private I2PAppContext _context;
    private Log _log;
    private User _user;
    private String _subject;
    private String _tags;
    private String _headers;
    private String _text;
    private String _archive;
    private List _filenames;
    private List _fileStreams;
    private List _localFiles;
    private List _fileTypes;
    private boolean _previewed;
    
    public PostBean() { 
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(PostBean.class);
        reinitialize(); 
    }
    
    public void reinitialize() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Reinitializing " + (_text != null ? "(with " + _text.length() + " bytes of sml!)" : ""));
        _user = null;
        _subject = null;
        _tags = null;
        _text = null;
        _headers = null;
        _archive = null;
        _filenames = new ArrayList();
        _fileStreams = new ArrayList();
        _fileTypes = new ArrayList();
        if (_localFiles != null)
            for (int i = 0; i < _localFiles.size(); i++)
                ((File)_localFiles.get(i)).delete();
        
        _localFiles = new ArrayList();
        _previewed = false;
    }

    public User getUser() { return _user; }
    public String getSubject() { return (_subject != null ? _subject : ""); }
    public String getTags() { return (_tags != null ? _tags : ""); }
    public String getText() { return (_text != null ? _text : ""); }
    public String getHeaders() { return (_headers != null ? _headers : ""); }
    public void setUser(User user) { _user = user; }
    public void setSubject(String subject) { _subject = subject; }
    public void setTags(String tags) { _tags = tags; }
    public void setText(String text) { _text = text; }
    public void setHeaders(String headers) { _headers = headers; }
    public void setArchive(String archive) { _archive = archive; }
    
    public String getContentType(int id) { 
        if ( (id >= 0) && (id < _fileTypes.size()) )
            return (String)_fileTypes.get(id);
        return "application/octet-stream";
    }
    
    public void writeAttachmentData(int id, OutputStream out) throws IOException {
        FileInputStream in = null;
        try {
            in = new FileInputStream((File)_localFiles.get(id));
            byte buf[] = new byte[1024];
            int read = 0;
            while ( (read = in.read(buf)) != -1) 
                out.write(buf, 0, read);
            out.close();
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }
    
    public void addAttachment(String filename, InputStream fileStream, String mimeType) { 
        _filenames.add(filename);
        _fileStreams.add(fileStream);
        _fileTypes.add(mimeType);
    }
    public int getAttachmentCount() { return (_filenames != null ? _filenames.size() : 0); }
    
    public BlogURI postEntry() throws IOException {
        if (!_previewed) return null;
        List localStreams = new ArrayList(_localFiles.size());
        for (int i = 0; i < _localFiles.size(); i++) {
            File f = (File)_localFiles.get(i);
            localStreams.add(new FileInputStream(f));
        }
        BlogURI uri = BlogManager.instance().createBlogEntry(_user, _subject, _tags, _headers, _text, 
                                                             _filenames, localStreams, _fileTypes);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Posted the entry " + uri.toString() + " (archive = " + _archive + ")");
        if ( (uri != null) && BlogManager.instance().authorizeRemote(_user) ) {
            PetName pn = _user.getPetNameDB().getByName(_archive);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Archive to petname? " + pn + " (protocol: " + (pn != null ? pn.getProtocol() : "") + ")");
            if ( (pn != null) && ("syndiearchive".equals(pn.getProtocol())) ) {
                RemoteArchiveBean r = new RemoteArchiveBean();
                Map params = new HashMap();
                
                String entries[] = null;
                BlogInfo info = BlogManager.instance().getArchive().getBlogInfo(uri);
                if (info != null) {
                    String str = info.getProperty(BlogInfo.SUMMARY_ENTRY_ID);
                    if (str != null) {
                        entries = new String[] { uri.toString(), str };
                    }
                }
                if (entries == null)
                    entries = new String[] { uri.toString() };
                
                params.put("localentry", entries);
                String proxyHost = BlogManager.instance().getDefaultProxyHost();
                String port = BlogManager.instance().getDefaultProxyPort();
                int proxyPort = 4444;
                try { proxyPort = Integer.parseInt(port); } catch (NumberFormatException nfe) {}
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Posting the entry " + uri.toString() + " to " + pn.getLocation());
                r.postSelectedEntries(_user, params, proxyHost, proxyPort, pn.getLocation());
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Post status: " + r.getStatus());
            }
        }
        return uri;
    }
    
    public void renderPreview(Writer out) throws IOException {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Subject: " + _subject);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Text: " + _text);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Headers: " + _headers);
        // cache all the _fileStreams into temporary files, storing those files in _localFiles
        // then render the page accordingly with an HTMLRenderer, altered to use a different 
        // 'view attachment'
        cacheAttachments();
        String smlContent = renderSMLContent();
        HTMLPreviewRenderer r = new HTMLPreviewRenderer(_context, _filenames, _fileTypes, _localFiles);
        r.render(_user, BlogManager.instance().getArchive(), null, smlContent, out, false, true);
        _previewed = true;
    }
    
    public void renderReplyPreview(Writer out, String parentURI) throws IOException {
        HTMLRenderer r = new HTMLRenderer(_context);
        Archive a = BlogManager.instance().getArchive();
        BlogURI uri = new BlogURI(parentURI);
        if (uri.getEntryId() > 0) {
            EntryContainer entry = a.getEntry(uri);
            r.render(_user, a, entry, out, false, true);
        }
    }
    
    private String renderSMLContent() {
        StringBuffer raw = new StringBuffer();
        raw.append("Subject: ").append(_subject).append('\n');
        raw.append("Tags: ");
        StringTokenizer tok = new StringTokenizer(_tags, " \t\n");
        while (tok.hasMoreTokens())
            raw.append(tok.nextToken()).append('\t');
        raw.append('\n');
        raw.append(_headers.trim());
        raw.append("\n\n");
        raw.append(_text.trim());
        return raw.toString();
    }
    
    /** until we have a good filtering/preferences system, lets try to keep the content small */
    private static final int MAX_SIZE = 256*1024;
    
    private void cacheAttachments() throws IOException {
        if (_user == null) throw new IOException("User not specified");
        File postCacheDir = new File(BlogManager.instance().getTempDir(), _user.getBlog().toBase64());
        if (!postCacheDir.exists())
            postCacheDir.mkdirs();
        for (int i = 0; i < _fileStreams.size(); i++) {
            InputStream in = (InputStream)_fileStreams.get(i);
            File f = File.createTempFile("attachment", ".dat", postCacheDir);
            FileOutputStream o = new FileOutputStream(f);
            byte buf[] = new byte[1024];
            int read = 0;
            while ( (read = in.read(buf)) != -1) 
                o.write(buf, 0, read);
            o.close();
            in.close();
            if (f.length() > MAX_SIZE) {
                _log.error("Refusing to post the attachment, because it is too big: " + f.length());
            } else {
                _localFiles.add(f);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Caching attachment " + i + " temporarily in " 
                                   + f.getAbsolutePath() + " w/ " + f.length() + "bytes");
            }
        }
        _fileStreams.clear();
    }
}
