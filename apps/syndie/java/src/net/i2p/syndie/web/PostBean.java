package net.i2p.syndie.web;

import java.io.*;
import java.util.*;
import net.i2p.syndie.*;
import net.i2p.syndie.data.BlogURI;
import net.i2p.syndie.sml.HTMLPreviewRenderer;

/**
 *
 */
public class PostBean {
    private User _user;
    private String _subject;
    private String _tags;
    private String _headers;
    private String _text;
    private List _filenames;
    private List _fileStreams;
    private List _localFiles;
    private List _fileTypes;
    private boolean _previewed;
    
    public PostBean() { reinitialize(); }
    
    public void reinitialize() {
        System.out.println("Reinitializing " + (_text != null ? "(with " + _text.length() + " bytes of sml!)" : ""));
        _user = null;
        _subject = null;
        _tags = null;
        _text = null;
        _headers = null;
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
    
    public String getContentType(int id) { 
        if ( (id >= 0) && (id < _fileTypes.size()) )
            return (String)_fileTypes.get(id);
        return "application/octet-stream";
    }
    
    public void writeAttachmentData(int id, OutputStream out) throws IOException {
        FileInputStream in = new FileInputStream((File)_localFiles.get(id));
        byte buf[] = new byte[1024];
        int read = 0;
        while ( (read = in.read(buf)) != -1) 
            out.write(buf, 0, read);
        out.close();
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
        return BlogManager.instance().createBlogEntry(_user, _subject, _tags, _headers, _text, 
                                                      _filenames, localStreams, _fileTypes);
    }
    
    public void renderPreview(Writer out) throws IOException {
        System.out.println("Subject: " + _subject);
        System.out.println("Text: " + _text);
        System.out.println("Headers: " + _headers);
        // cache all the _fileStreams into temporary files, storing those files in _localFiles
        // then render the page accordingly with an HTMLRenderer, altered to use a different 
        // 'view attachment'
        cacheAttachments();
        String smlContent = renderSMLContent();
        HTMLPreviewRenderer r = new HTMLPreviewRenderer(_filenames, _fileTypes, _localFiles);
        r.render(_user, BlogManager.instance().getArchive(), null, smlContent, out, false, true);
        _previewed = true;
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
    
    private void cacheAttachments() throws IOException {
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
            _localFiles.add(f);
            System.out.println("Caching attachment " + i + " temporarily in " 
                               + f.getAbsolutePath() + " w/ " + f.length() + "bytes");
        }
        _fileStreams.clear();
    }
}
