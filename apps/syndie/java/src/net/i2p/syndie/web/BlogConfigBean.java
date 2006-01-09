package net.i2p.syndie.web;

import java.io.*;
import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.client.naming.PetName;
import net.i2p.data.DataHelper;
import net.i2p.syndie.*;
import net.i2p.syndie.data.*;
import net.i2p.util.Log;

/**
 *
 */
public class BlogConfigBean {
    private I2PAppContext _context;
    private Log _log;
    private User _user;
    private String _title;
    private String _description;
    private String _contactInfo;
    /** list of list of PetNames */
    private List _groups;
    private Properties _styleOverrides;
    private File _logo;
    private boolean _loaded;
    private boolean _updated;
    
    public BlogConfigBean() { 
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(BlogConfigBean.class);
        _groups = new ArrayList();
        _styleOverrides = new Properties();
    }
    
    public boolean isUpdated() { return _updated; }
    
    public User getUser() { return _user; }
    public void setUser(User user) { 
        _user = user;
        _title = null;
        _description = null;
        _contactInfo = null;
        _groups.clear();
        _styleOverrides.clear();
        if (_logo != null)
            _logo.delete();
        _logo = null;
        _loaded = false;
        _updated = false;
        load();
    }
    public String getTitle() { return _title; }
    public void setTitle(String title) { 
        _title = title; 
        _updated = true;
    }
    public String getDescription() { return _description; }
    public void setDescription(String desc) { 
        _description = desc; 
        _updated = true;
    }
    public String getContactInfo() { return _contactInfo; }
    public void setContactInfo(String info) { 
        _contactInfo = info; 
        _updated = true;
    }
    public int getGroupCount() { return _groups.size(); }
    /** gets the actual modifiable list of PetName instances */
    public List getGroup(int i) { return (List)_groups.get(i); }
    /** gets the actual modifiable list of PetName instances */
    public List getGroup(String name) {
        for (int i = 0; i < _groups.size(); i++) {
            List grp = (List)_groups.get(i);
            if (grp.size() > 0) {
                PetName pn = (PetName)grp.get(0);
                if ( (pn.getGroupCount() == 0) && ( (name == null) || (name.length() <= 0) ) )
                    return grp;
                if (pn.getGroupCount() == 0)
                    continue;
                String curGroup = pn.getGroup(0);
                if (curGroup.equals(name))
                    return grp;
            }
        }
        return null;
    }
    /** adds the given element to the appropriate group (creating a new one if necessary) */
    public void add(PetName pn) {
        String groupName = null;
        if (pn.getGroupCount() > 0)
            groupName = pn.getGroup(0);
        List group = getGroup(groupName);
        if (group == null) {
            group = new ArrayList(4);
            group.add(pn);
            _groups.add(group);
        } else {
            group.add(pn);
        }
        _updated = true;
    }
    public void remove(PetName pn) {
        String groupName = null;
        if (pn.getGroupCount() > 0)
            groupName = pn.getGroup(0);
        List group = getGroup(groupName);
        if (group != null) {
            group.remove(pn);
            if (group.size() <= 0)
                _groups.remove(group);
        }
        _updated = true;
    }
    public void remove(String name) {
        for (int i = 0; i < getGroupCount(); i++) {
            List group = getGroup(i);
            for (int j = 0; j < group.size(); j++) {
                PetName pn = (PetName)group.get(j);
                if (pn.getName().equals(name)) {
                    group.remove(j);
                    if (group.size() <= 0)
                        _groups.remove(group);
                    _updated = true;
                    return;
                }
            }
        }
    }
    /** take note that the groups have been updated in some way (reordered, etc) */
    public void groupsUpdated() { _updated = true; }
    public String getStyleOverride(String prop) { return _styleOverrides.getProperty(prop); }
    public void setStyleOverride(String prop, String val) { 
        _styleOverrides.setProperty(prop, val); 
        _updated = true;
    }
    public void unsetStyleOverride(String prop) { 
        _styleOverrides.remove(prop); 
        _updated = true;
    }
    public File getLogo() { return _logo; }
    public void setLogo(File logo) { 
        if ( (logo != null) && (logo.length() > BlogInfoData.MAX_LOGO_SIZE) ) {
            _log.error("Refusing a logo of size " + logo.length());
            logo.delete();
            return;
        }
        if (_logo != null)
            _logo.delete();
        _logo = logo; 
        _updated = true; 
    }
    public boolean hasPendingChanges() { return _loaded && _updated; }
    
    private void load() {
        Archive archive = BlogManager.instance().getArchive();
        BlogInfo info = archive.getBlogInfo(_user.getBlog());
        if (info != null) {
            _title = info.getProperty(BlogInfo.NAME);
            _description = info.getProperty(BlogInfo.DESCRIPTION);
            _contactInfo = info.getProperty(BlogInfo.CONTACT_URL);
            String id = info.getProperty(BlogInfo.SUMMARY_ENTRY_ID);
            if (id != null) {
                BlogURI uri = new BlogURI(id);
                EntryContainer entry = archive.getEntry(uri);
                if (entry != null) {
                    BlogInfoData data = new BlogInfoData();
                    try {
                        data.load(entry);
                        if (data.isLogoSpecified()) {
                            File logo = File.createTempFile("logo", ".png", BlogManager.instance().getTempDir());
                            FileOutputStream os = null;
                            try {
                                os = new FileOutputStream(logo);
                                data.writeLogo(os);
                                _logo = logo;
                            } finally {
                                if (os != null) try { os.close(); } catch (IOException ioe) {}
                            }
                        }
                        for (int i = 0; i < data.getReferenceGroupCount(); i++) {
                            List group = (List)data.getReferenceGroup(i);
                            for (int j = 0; j < group.size(); j++) {
                                PetName pn = (PetName)group.get(j);
                                add(pn);
                            }
                        }
                        Properties overrides = data.getStyleOverrides();
                        if (overrides != null)
                            _styleOverrides.putAll(overrides);
                    } catch (IOException ioe) {
                        _log.warn("Unable to load the blog info data from " + uri, ioe);
                    }
                }
            }
        }
        _loaded = true;
        _updated = false;
    }
    
    public boolean publishChanges() {
        FileInputStream logo = null;
        try {
            if (_logo != null) {
                logo = new FileInputStream(_logo);
                _log.debug("Logo file is: " + _logo.length() + "bytes @ " + _logo.getAbsolutePath());
            }
            InputStream styleStream = createStyleStream();
            InputStream groupStream = createGroupStream();
            
            String tags = BlogInfoData.TAG;
            String subject = "n/a";
            String headers = "";
            String sml = "";
            List filenames = new ArrayList();
            List filestreams = new ArrayList();
            List filetypes = new ArrayList();
            if (logo != null) {
                filenames.add(BlogInfoData.ATTACHMENT_LOGO);
                filestreams.add(logo);
                filetypes.add("image/png");
            }
            filenames.add(BlogInfoData.ATTACHMENT_STYLE_OVERRIDE);
            filestreams.add(styleStream);
            filetypes.add("text/plain");
            filenames.add(BlogInfoData.ATTACHMENT_REFERENCE_GROUPS);
            filestreams.add(groupStream);
            filetypes.add("text/plain");
            
            BlogURI uri = BlogManager.instance().createBlogEntry(_user, subject, tags, headers, sml, 
                                                                 filenames, filestreams, filetypes);
            if (uri != null) {
                Archive archive = BlogManager.instance().getArchive();
                BlogInfo info = archive.getBlogInfo(_user.getBlog());
                if (info != null) {
                    String props[] = info.getProperties();
                    Properties opts = new Properties();
                    for (int i = 0; i < props.length; i++) {
                        if (!props[i].equals(BlogInfo.SUMMARY_ENTRY_ID))
                            opts.setProperty(props[i], info.getProperty(props[i]));
                    }
                    opts.setProperty(BlogInfo.SUMMARY_ENTRY_ID, uri.toString());
                    boolean updated = BlogManager.instance().updateMetadata(_user, _user.getBlog(), opts);
                    if (updated) {
                        // ok great, published locally, though should we push it to others?
                        _log.info("Blog summary updated for " + _user + " in " + uri.toString());
                        setUser(_user);
                        _log.debug("Updated? " + _updated);
                        return true;
                    }
                } else {
                    _log.error("Info is not known for " + _user.getBlog().toBase64());
                    return false;
                }
            } else {
                _log.error("Error creating the summary entry");
                return false;
            }
        } catch (IOException ioe) {
            _log.error("Error publishing", ioe);
        } finally {
            if (logo != null) try { logo.close(); } catch (IOException ioe) {}
            // the other streams are in-memory, drop with the scope
            if (_logo != null) _logo.delete();
        }
        return false;
    }
    private InputStream createStyleStream() throws IOException {
        StringBuffer buf = new StringBuffer(1024);
        if (_styleOverrides != null) {
            for (Iterator iter = _styleOverrides.keySet().iterator(); iter.hasNext(); ) {
                String key = (String)iter.next();
                String val = _styleOverrides.getProperty(key);
                buf.append(key).append('=').append(val).append('\n');
            }
        }
        return new ByteArrayInputStream(DataHelper.getUTF8(buf));
    }
    private InputStream createGroupStream() throws IOException {
        StringBuffer buf = new StringBuffer(1024);
        for (int i = 0; i < _groups.size(); i++) {
            List group = (List)_groups.get(i);
            for (int j = 0; j < group.size(); j++) {
                PetName pn = (PetName)group.get(j);
                buf.append(pn.toString()).append('\n');
            }
        }
        return new ByteArrayInputStream(DataHelper.getUTF8(buf));
    }
    
    protected void finalize() {
        if (_logo != null) _logo.delete();
    }
}
