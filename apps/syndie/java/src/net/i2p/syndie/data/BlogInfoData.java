package net.i2p.syndie.data;

import java.io.*;
import java.util.*;
import net.i2p.client.naming.PetName;
import net.i2p.data.DataHelper;

/**
 * Contain the current supplementary data for rendering a blog, as opposed to
 * just verifying and rendering a post.
 */
public class BlogInfoData {
    private BlogURI _dataEntryId;
    /** list of List of PetName instances that the blog refers to */
    private List _referenceGroups;
    /** customized style config */
    private Properties _styleOverrides;
    /** the blog's logo */
    private Attachment _logo;
    private List _otherAttachments;
    
    public static final String ATTACHMENT_LOGO = "logo.png";
    public static final String ATTACHMENT_REFERENCE_GROUPS = "groups.txt";
    public static final String ATTACHMENT_STYLE_OVERRIDE = "style.cfg";
    /** identifies a post as being a blog info data, not a content bearing post */
    public static final String TAG = "BlogInfoData";

    public BlogInfoData() {}
    
    public BlogURI getEntryId() { return _dataEntryId; }
    public boolean isLogoSpecified() { return _logo != null; }
    public Attachment getLogo() { return _logo; }
    public boolean isStyleSpecified() { return _styleOverrides != null; }
    public Properties getStyleOverrides() { return _styleOverrides; }
    public int getReferenceGroupCount() { return _referenceGroups != null ? _referenceGroups.size() : 0; }
    /** list of PetName elements to be included in the list */
    public List getReferenceGroup(int groupNum) { return (List)_referenceGroups.get(groupNum); }
    public int getOtherAttachmentCount() { return _otherAttachments != null ? _otherAttachments.size() : 0; }
    public Attachment getOtherAttachment(int num) { return (Attachment)_otherAttachments.get(num); }
    public Attachment getOtherAttachment(String name) {
        for (int i = 0; i < _otherAttachments.size(); i++) {
            Attachment a = (Attachment)_otherAttachments.get(i);
            if (a.getName().equals(name))
                return a;
        }
        return null;
    }
    
    public void writeLogo(OutputStream out) throws IOException {
        InputStream in = null;
        try {
            in = _logo.getDataStream();
            byte buf[] = new byte[4096];
            int read = 0;
            while ( (read = in.read(buf)) != -1)
                out.write(buf, 0, read);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }

    
    public void load(EntryContainer entry) throws IOException {
        _dataEntryId = entry.getURI();
        Attachment attachments[] = entry.getAttachments();
        for (int i = 0; i < attachments.length; i++) {
            if (ATTACHMENT_LOGO.equals(attachments[i].getName())) {
                _logo = attachments[i];
            } else if (ATTACHMENT_REFERENCE_GROUPS.equals(attachments[i].getName())) {
                readReferenceGroups(attachments[i]);
            } else if (ATTACHMENT_STYLE_OVERRIDE.equals(attachments[i].getName())) {
                readStyleOverride(attachments[i]);
            } else {
                if (_otherAttachments == null)
                    _otherAttachments = new ArrayList();
                _otherAttachments.add(attachments[i]);
            }
        }
    }
    
    private void readReferenceGroups(Attachment att) throws IOException {
        InputStream in = null;
        try {
            in = att.getDataStream();
            StringBuffer line = new StringBuffer(128);
            List groups = new ArrayList();
            String prevGroup = null;
            List defaultGroup = new ArrayList();
            while (true) {
                boolean ok = DataHelper.readLine(in, line);
                if (line.length() > 0) {
                    PetName pn = new PetName(line.toString().trim());
                    if (pn.getGroupCount() <= 0) {
                        defaultGroup.add(pn);
                    } else if (pn.getGroup(0).equals(prevGroup)) {
                        List curGroup = (List)groups.get(groups.size()-1);
                        curGroup.add(pn);
                    } else {
                        List curGroup = new ArrayList();
                        curGroup.add(pn);
                        groups.add(curGroup);
                        prevGroup = pn.getGroup(0);
                    }
                }
                if (!ok)
                    break;
            }
            if (defaultGroup.size() > 0)
                groups.add(defaultGroup);
            _referenceGroups = groups;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }
    
    private void readStyleOverride(Attachment att) throws IOException {
        InputStream in = null;
        try {
            in = att.getDataStream();
            Properties props = new Properties();
            DataHelper.loadProps(props, in);
            _styleOverrides = props;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }
}
