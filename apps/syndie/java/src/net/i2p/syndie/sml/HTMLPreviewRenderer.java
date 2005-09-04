package net.i2p.syndie.sml;

import java.io.*;
import java.text.*;
import java.util.*;
import net.i2p.data.*;
import net.i2p.syndie.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.web.*;

/**
 *
 */
public class HTMLPreviewRenderer extends HTMLRenderer {
    private List _filenames;
    private List _fileTypes;
    private List _files;
    
    public HTMLPreviewRenderer(List filenames, List fileTypes, List files) {
        super();
        _filenames = filenames;
        _fileTypes = fileTypes;
        _files = files;
    }
    
    protected String getAttachmentURLBase() { return "viewtempattachment.jsp"; }
    protected String getAttachmentURL(int id) {
        return getAttachmentURLBase() + "?" + 
               ArchiveViewerBean.PARAM_ATTACHMENT + "=" + id;
    }    
    
    public void receiveAttachment(int id, String anchorText) {
        if (!continueBody()) { return; }
        if ( (id < 0) || (_files == null) || (id >= _files.size()) ) {
            _bodyBuffer.append(sanitizeString(anchorText));
        } else {
            File f = (File)_files.get(id);
            String name = (String)_filenames.get(id);
            String type = (String)_fileTypes.get(id);
            _bodyBuffer.append("<a href=\"").append(getAttachmentURL(id)).append("\">");
            _bodyBuffer.append(sanitizeString(anchorText)).append("</a>");
            _bodyBuffer.append(" (").append(f.length()/1024).append("KB, ");
            _bodyBuffer.append(" \"").append(sanitizeString(name)).append("\", ");
            _bodyBuffer.append(sanitizeString(type)).append(")");
        }
    }
    
    public void receiveEnd() { 
        _postBodyBuffer.append("</td></tr>\n");
        _postBodyBuffer.append("<tr>\n");
        _postBodyBuffer.append("<form action=\"").append(getAttachmentURLBase()).append("\">\n");
        _postBodyBuffer.append("<td colspan=\"2\" valign=\"top\" align=\"left\" class=\"syndieEntryAttachmentsCell\"\n");

        if (_files.size() > 0) {
            _postBodyBuffer.append("<b>Attachments:</b> ");
            _postBodyBuffer.append("<select name=\"").append(ArchiveViewerBean.PARAM_ATTACHMENT).append("\">\n");
            for (int i = 0; i < _files.size(); i++) {
                _postBodyBuffer.append("<option value=\"").append(i).append("\">");
                File f = (File)_files.get(i);
                String name = (String)_filenames.get(i);
                String type = (String)_fileTypes.get(i);
                _postBodyBuffer.append(sanitizeString(name));
                _postBodyBuffer.append(" (").append(f.length()/1024).append("KB");
                _postBodyBuffer.append(", type ").append(sanitizeString(type)).append(")</option>\n");
            }
            _postBodyBuffer.append("</select>\n");
            _postBodyBuffer.append("<input type=\"submit\" value=\"Download\" name=\"Download\" /><br />\n");
        }

        if (_blogs.size() > 0) {
            _postBodyBuffer.append("<b>Blog references:</b> ");
            for (int i = 0; i < _blogs.size(); i++) {
                Blog b = (Blog)_blogs.get(i);
                _postBodyBuffer.append("<a href=\"").append(getPageURL(new Hash(Base64.decode(b.hash)), b.tag, b.entryId, -1, -1, (_user != null ? _user.getShowExpanded() : false), (_user != null ? _user.getShowImages() : false)));
                _postBodyBuffer.append("\">").append(sanitizeString(b.name)).append("</a> ");
            }
            _postBodyBuffer.append("<br />\n");
        }

        if (_links.size() > 0) {
            _postBodyBuffer.append("<b>External links:</b> ");
            for (int i = 0; i < _links.size(); i++) {
                Link l = (Link)_links.get(i);
                _postBodyBuffer.append("<a href=\"externallink.jsp?schema=");
                _postBodyBuffer.append(sanitizeURL(l.schema)).append("&location=");
                _postBodyBuffer.append(sanitizeURL(l.location));
                _postBodyBuffer.append("\">").append(sanitizeString(l.location));
                _postBodyBuffer.append(" (").append(sanitizeString(l.schema)).append(")</a> ");
            }
            _postBodyBuffer.append("<br />\n");
        }

        if (_addresses.size() > 0) {
            _postBodyBuffer.append("<b>Addresses:</b> ");
            for (int i = 0; i < _addresses.size(); i++) {
                Address a = (Address)_addresses.get(i);
                
                String knownName = null;
                if (_user != null)
                    knownName = _user.getPetNameDB().getNameByLocation(a.location);
                if (knownName != null) {
                    _postBodyBuffer.append(' ').append(sanitizeString(knownName));
                } else {
                    _postBodyBuffer.append(" <a href=\"addaddress.jsp?schema=");
                    _postBodyBuffer.append(sanitizeURL(a.schema)).append("&location=");
                    _postBodyBuffer.append(sanitizeURL(a.location)).append("&name=");
                    _postBodyBuffer.append(sanitizeURL(a.protocol)).append("&protocol=");
                    _postBodyBuffer.append(sanitizeURL(a.name));
                    _postBodyBuffer.append("\">").append(sanitizeString(a.name));
                }
            }
            _postBodyBuffer.append("<br />\n");
        }

        if (_archives.size() > 0) {
            _postBodyBuffer.append("<b>Archives:</b>");
            for (int i = 0; i < _archives.size(); i++) {
                ArchiveRef a = (ArchiveRef)_archives.get(i);
                _postBodyBuffer.append(" <a href=\"").append(getArchiveURL(null, new SafeURL(a.locationSchema + "://" + a.location)));
                _postBodyBuffer.append("\">").append(sanitizeString(a.name)).append("</a>");
                if (a.description != null)
                    _postBodyBuffer.append(": ").append(sanitizeString(a.description));
            }
            _postBodyBuffer.append("<br />\n");
        }

        _postBodyBuffer.append("</td>\n</form>\n</tr>\n");
        _postBodyBuffer.append("</table>\n");
    }
}
