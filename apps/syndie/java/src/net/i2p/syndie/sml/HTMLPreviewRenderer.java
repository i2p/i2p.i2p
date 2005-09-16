package net.i2p.syndie.sml;

import java.io.*;
import java.text.*;
import java.util.*;
import net.i2p.I2PAppContext;
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
    
    public HTMLPreviewRenderer(I2PAppContext ctx, List filenames, List fileTypes, List files) {
        super(ctx);
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
            _bodyBuffer.append("<a ").append(getClass("attachmentView")).append(" href=\"").append(getAttachmentURL(id)).append("\">");
            _bodyBuffer.append(sanitizeString(anchorText)).append("</a>");
            _bodyBuffer.append(getSpan("attachmentSummary")).append(" (");
            _bodyBuffer.append(getSpan("attachmentSummarySize")).append(f.length()/1024).append("KB</span>, ");
            _bodyBuffer.append(getSpan("attachmentSummaryName")).append(" \"").append(sanitizeString(name)).append("\"</span>, ");
            _bodyBuffer.append(getSpan("attachmentSummaryType")).append(sanitizeString(type)).append("</span>)</span>");
        }
    }
    
    public void receiveEnd() { 
        _postBodyBuffer.append("</td></tr>\n");
        _postBodyBuffer.append("<tr ").append(getClass("summDetail")).append(" >\n");
        _postBodyBuffer.append("<form action=\"").append(getAttachmentURLBase()).append("\">\n");
        _postBodyBuffer.append("<td colspan=\"2\" valign=\"top\" align=\"left\" ").append(getClass("summDetail")).append("> \n");

        if (_files.size() > 0) {
            _postBodyBuffer.append(getSpan("summDetailAttachment")).append("Attachments:</span> ");
            _postBodyBuffer.append("<select ").append(getClass("summDetailAttachmentId")).append(" name=\"").append(ArchiveViewerBean.PARAM_ATTACHMENT).append("\">\n");
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
            _postBodyBuffer.append("<input ").append(getClass("summDetailAttachmentDl")).append(" type=\"submit\" value=\"Download\" name=\"Download\" /><br />\n");
        }

        if (_blogs.size() > 0) {
            _postBodyBuffer.append(getSpan("summDetailBlog")).append("Blog references:</span> ");
            for (int i = 0; i < _blogs.size(); i++) {
                Blog b = (Blog)_blogs.get(i);
                boolean expanded = (_user != null ? _user.getShowExpanded() : false);
                boolean images = (_user != null ? _user.getShowImages() : false);
                _postBodyBuffer.append("<a ").append(getClass("summDetailBlogLink")).append(" href=\"");
                _postBodyBuffer.append(getPageURL(new Hash(Base64.decode(b.hash)), b.tag, b.entryId, -1, -1, expanded, images));
                _postBodyBuffer.append("\">").append(sanitizeString(b.name)).append("</a> ");
            }
            _postBodyBuffer.append("<br />\n");
        }

        if (_links.size() > 0) {
            _postBodyBuffer.append(getSpan("summDetailExternal")).append("External links:</span> ");
            for (int i = 0; i < _links.size(); i++) {
                Link l = (Link)_links.get(i);
                _postBodyBuffer.append("<a ").append(getClass("summDetailExternalLink")).append(" href=\"externallink.jsp?");
                if (l.schema != null)
                    _postBodyBuffer.append("schema=").append(sanitizeURL(l.schema)).append('&');
                if (l.location != null)
                    _postBodyBuffer.append("location=").append(sanitizeURL(l.location)).append('&');
                _postBodyBuffer.append("\">").append(sanitizeString(l.location));
                _postBodyBuffer.append(getSpan("summDetailExternalNet")).append(" (").append(sanitizeString(l.schema)).append(")</span></a> ");
            }
            _postBodyBuffer.append("<br />\n");
        }

        if (_addresses.size() > 0) {
            _postBodyBuffer.append(getSpan("summDetailAddr")).append("Addresses:</span>");
            for (int i = 0; i < _addresses.size(); i++) {
                Address a = (Address)_addresses.get(i);

                String knownName = null;
                if (_user != null)
                    knownName = _user.getPetNameDB().getNameByLocation(a.location);
                if (knownName != null) {
                    _postBodyBuffer.append(' ').append(getSpan("summDetailAddrKnown"));
                    _postBodyBuffer.append(sanitizeString(knownName)).append("</span>");
                } else {
                    _postBodyBuffer.append(" <a ").append(getClass("summDetailAddrLink")).append(" href=\"addresses.jsp?");
                    if (a.schema != null)
                        _postBodyBuffer.append("network=").append(sanitizeTagParam(a.schema)).append('&');
                    if (a.location != null)
                        _postBodyBuffer.append("location=").append(sanitizeTagParam(a.location)).append('&');
                    if (a.name != null)
                        _postBodyBuffer.append("name=").append(sanitizeTagParam(a.name)).append('&');
                    if (a.protocol != null)
                        _postBodyBuffer.append("protocol=").append(sanitizeTagParam(a.protocol)).append('&');
                    _postBodyBuffer.append("\">").append(sanitizeString(a.name)).append("</a>");
                }
            }
            _postBodyBuffer.append("<br />\n");
        }

        if (_archives.size() > 0) {
            _postBodyBuffer.append(getSpan("summDetailArchive")).append("Archives:</span>");
            for (int i = 0; i < _archives.size(); i++) {
                ArchiveRef a = (ArchiveRef)_archives.get(i);
                _postBodyBuffer.append(" <a ").append(getClass("summDetailArchiveLink")).append(" href=\"").append(getArchiveURL(null, new SafeURL(a.locationSchema + "://" + a.location)));
                _postBodyBuffer.append("\">").append(sanitizeString(a.name)).append("</a>");
                if (a.description != null)
                    _postBodyBuffer.append(": ").append(getSpan("summDetailArchiveDesc")).append(sanitizeString(a.description)).append("</span>");
                if (null == _user.getPetNameDB().getNameByLocation(a.location)) {
                    _postBodyBuffer.append(" <a ").append(getClass("summDetailArchiveBookmark")).append(" href=\"");
                    _postBodyBuffer.append(getBookmarkURL(a.name, a.location, a.locationSchema, "syndiearchive"));
                    _postBodyBuffer.append("\">bookmark</a>");
                }
            }
            _postBodyBuffer.append("<br />\n");
        }

        _postBodyBuffer.append("</td>\n</form>\n</tr>\n");
        _postBodyBuffer.append("</table>\n");
    }
}
