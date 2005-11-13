package net.i2p.syndie.web;

import java.io.*;
import java.util.*;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletException;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.*;
import net.i2p.data.*;
import net.i2p.syndie.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.sml.*;

/**
 * Render the requested profile
 *
 */
public class ProfileServlet extends BaseServlet {
    protected void renderServletDetails(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index, 
                                        int threadOffset, BlogURI visibleEntry, Archive archive) throws IOException {
        Hash author = null;
        String str = req.getParameter(ThreadedHTMLRenderer.PARAM_AUTHOR);
        if (str != null) {
            try {
                author = new Hash();
                author.fromBase64(str);
            } catch (DataFormatException dfe) {
                author = null;
            }
        } else {
            author = user.getBlog();
        }
        
        String uri = req.getRequestURI();
        
        if (author == null) {
            renderInvalidProfile(out);
        } else if ( (user.getBlog() != null) && (user.getBlog().equals(author)) ) {
            renderMyProfile(user, uri, out, archive);
        } else {
            renderProfile(user, uri, out, author, archive);
        }
    }   
    
    private void renderInvalidProfile(PrintWriter out) throws IOException {
        out.write(INVALID_PROFILE);
    }
    
    private void renderMyProfile(User user, String baseURI, PrintWriter out, Archive archive) throws IOException {
        BlogInfo info = archive.getBlogInfo(user.getBlog());
        if (info == null)
            return;
        
        out.write("<!-- " + info.toString() + "-->\n");
        out.write("<form action=\"" + baseURI + "\" method=\"POST\">\n");
        // now add the form to update
        out.write("<tr><td colspan=\"3\">Your profile</td></tr>\n");
        out.write("<tr><td colspan=\"3\">Name: <input type=\"text\" name=\"" 
                  + ThreadedHTMLRenderer.PARAM_PROFILE_NAME + "\" value=\"" 
                  + HTMLRenderer.sanitizeTagParam(info.getProperty(BlogInfo.NAME)) + "\"></td></tr>\n");
        out.write("<tr><td colspan=\"3\">Account description: <input type=\"text\" name=\"" 
                  + ThreadedHTMLRenderer.PARAM_PROFILE_DESC + "\" value=\"" 
                  + HTMLRenderer.sanitizeTagParam(info.getProperty(BlogInfo.DESCRIPTION)) + "\"></td></tr>\n");
        out.write("<tr><td colspan=\"3\">Contact information: <input type=\"text\" name=\"" 
                  + ThreadedHTMLRenderer.PARAM_PROFILE_URL + "\" value=\"" 
                  + HTMLRenderer.sanitizeTagParam(info.getProperty(BlogInfo.CONTACT_URL)) + "\"></td></tr>\n");
        out.write("<tr><td colspan=\"3\">Other attributes:<br /><textarea rows=\"3\" name=\"" 
                  + ThreadedHTMLRenderer.PARAM_PROFILE_OTHER + "\" cols=\"60\">");
        String props[] = info.getProperties();
        if (props != null) {
            for (int i = 0; i < props.length; i++) {
                if (!BlogInfo.NAME.equals(props[i]) && 
                    !BlogInfo.DESCRIPTION.equals(props[i]) && 
                    !BlogInfo.EDITION.equals(props[i]) && 
                    !BlogInfo.OWNER_KEY.equals(props[i]) && 
                    !BlogInfo.POSTERS.equals(props[i]) && 
                    !BlogInfo.SIGNATURE.equals(props[i]) &&
                    !BlogInfo.CONTACT_URL.equals(props[i])) {
                    out.write(HTMLRenderer.sanitizeString(props[i], false) + ": " 
                              + HTMLRenderer.sanitizeString(info.getProperty(props[i]), false) + "\n");
                }
            }
        }
        out.write("</textarea></td></tr>\n");

        if (user.getAuthenticated()) {
            if ( (user.getUsername() == null) || (user.getUsername().equals(BlogManager.instance().getDefaultLogin())) ) {
                // this is the default user, don't let them change the password
            } else {
                out.write("<tr><td colspan=\"3\">Old Password: <input type=\"password\" name=\"oldPassword\" /></td></tr>\n");
                out.write("<tr><td colspan=\"3\">Password: <input type=\"password\" name=\"password\" /></td></tr>\n");
                out.write("<tr><td colspan=\"3\">Password again: <input type=\"password\" name=\"passwordConfirm\" /></td></tr>\n");
            }
        }
        
        out.write("<tr><td colspan=\"3\"><input type=\"submit\" name=\"action\" value=\"Update profile\" /></td></tr>\n");
        out.write("</form>\n");
    }
    
    private void renderProfile(User user, String baseURI, PrintWriter out, Hash author, Archive archive) throws IOException {
        out.write("<tr><td colspan=\"3\">Profile for <a href=\"" + getControlTarget() + "?" 
                  + ThreadedHTMLRenderer.PARAM_AUTHOR + '=' + author.toBase64() 
                  + "\" title=\"View threads by the profiled author\">");
        PetName pn = user.getPetNameDB().getByLocation(author.toBase64());
        BlogInfo info = archive.getBlogInfo(author);
        if (pn != null) {
            out.write(pn.getName());
            String name = null;
            if (info != null)
                name = info.getProperty(BlogInfo.NAME);
            
            if ( (name == null) || (name.trim().length() <= 0) )
                name = author.toBase64().substring(0, 6);
            
            out.write(" (" + name + ")");
        } else {
            String name = null;
            if (info != null)
                name = info.getProperty(BlogInfo.NAME);
            
            if ( (name == null) || (name.trim().length() <= 0) )
                name = author.toBase64().substring(0, 6);
            out.write(name);
        }
        out.write("</a>");
        if (info != null)
            out.write(" [edition " + info.getEdition() + "]");
        out.write("</td></tr>\n");
        
        out.write("<tr><td colspan=\"3\"><hr /></td></tr>\n");
        if (pn == null) {
            out.write("<tr><td colspan=\"3\">Not currently bookmarked.  Add them to your ");
            String addFav = getAddToGroupLink(user, author, FilteredThreadIndex.GROUP_FAVORITE, 
                                              baseURI, "", "", "", "", "", author.toBase64());
            String addIgnore = getAddToGroupLink(user, author, FilteredThreadIndex.GROUP_IGNORE, 
                                                 baseURI, "", "", "", "", "", author.toBase64());
            out.write("<a href=\"" + addFav + "\" title=\"Threads by favorite authors are shown specially\">favorites</a> or ");
            out.write("<a href=\"" + addIgnore + "\" title=\"Threads by ignored authors are hidden from view\">ignored</a> ");
            out.write("</td></tr>\n");
        } else if (pn.isMember(FilteredThreadIndex.GROUP_IGNORE)) {
            out.write("<tr><td colspan=\"3\">Currently ignored - threads they create are hidden.</td></tr>\n");
            String remIgnore = getRemoveFromGroupLink(user, pn.getName(), FilteredThreadIndex.GROUP_IGNORE, 
                                                      baseURI, "", "", "", "", "", author.toBase64());
            out.write("<tr><td colspan=\"3\"><a href=\"" + remIgnore + "\">Unignore " + pn.getName() + "</a></td></tr>\n");
            String remCompletely = getRemoveFromGroupLink(user, pn.getName(), "", 
                                                          baseURI, "", "", "", "", "", author.toBase64());
            out.write("<tr><td colspan=\"3\"><a href=\"" + remCompletely + "\">Forget about " + pn.getName() + " entirely</a></td></tr>\n");
        } else if (pn.isMember(FilteredThreadIndex.GROUP_FAVORITE)) {
            out.write("<tr><td colspan=\"3\">Currently marked as a favorite author - threads they participate in " +
                       "are highlighted.</td></tr>\n");
            String remIgnore = getRemoveFromGroupLink(user, pn.getName(), FilteredThreadIndex.GROUP_FAVORITE, 
                                                      baseURI, "", "", "", "", "", author.toBase64());
            out.write("<tr><td colspan=\"3\"><a href=\"" + remIgnore + "\">Remove " + pn.getName() + " from the list of favorite authors</a></td></tr>\n");
            String addIgnore = getAddToGroupLink(user, author, FilteredThreadIndex.GROUP_IGNORE, 
                                                 baseURI, "", "", "", "", "", author.toBase64());
            out.write("<tr><td colspan=\"3\"><a href=\"" + addIgnore + "\" title=\"Threads by ignored authors are hidden from view\">Ignore the author</a></td></tr>");
            String remCompletely = getRemoveFromGroupLink(user, pn.getName(), "", 
                                                          baseURI, "", "", "", "", "", author.toBase64());
            out.write("<tr><td colspan=\"3\"><a href=\"" + remCompletely + "\">Forget about " + pn.getName() + " entirely</a></td></tr>\n");
        } else {
            out.write("<tr><td colspan=\"3\">Currently bookmarked.  Add them to your ");
            String addFav = getAddToGroupLink(user, author, FilteredThreadIndex.GROUP_FAVORITE, 
                                              baseURI, "", "", "", "", "", author.toBase64());
            String addIgnore = getAddToGroupLink(user, author, FilteredThreadIndex.GROUP_IGNORE, 
                                                 baseURI, "", "", "", "", "", author.toBase64());
            out.write("<a href=\"" + addFav + "\" title=\"Threads by favorite authors are shown specially\">favorites</a> or ");
            out.write("<a href=\"" + addIgnore + "\" title=\"Threads by ignored authors are hidden from view\">ignored</a> list</td></tr>");
            String remCompletely = getRemoveFromGroupLink(user, pn.getName(), "", 
                                                          baseURI, "", "", "", "", "", author.toBase64());
            out.write("<tr><td colspan=\"3\"><a href=\"" + remCompletely + "\">Forget about " + pn.getName() + " entirely</a></td></tr>\n");
        }
        
        if (info != null) {
            String descr = info.getProperty(BlogInfo.DESCRIPTION);
            if ( (descr != null) && (descr.trim().length() > 0) )
                out.write("<tr><td colspan=\"3\">Account description: " + HTMLRenderer.sanitizeString(descr) + "</td></tr>\n");
            
            String contactURL = info.getProperty(BlogInfo.CONTACT_URL);
            if ( (contactURL != null) && (contactURL.trim().length() > 0) )
                out.write("<tr><td colspan=\"3\">Contact information: "
                          + HTMLRenderer.sanitizeString(contactURL) + "</td></tr>\n");
            
            String props[] = info.getProperties();
            int altCount = 0;
            if (props != null)
                for (int i = 0; i < props.length; i++)
                    if (!BlogInfo.NAME.equals(props[i]) && 
                        !BlogInfo.DESCRIPTION.equals(props[i]) && 
                        !BlogInfo.EDITION.equals(props[i]) && 
                        !BlogInfo.OWNER_KEY.equals(props[i]) && 
                        !BlogInfo.POSTERS.equals(props[i]) && 
                        !BlogInfo.SIGNATURE.equals(props[i]) &&
                        !BlogInfo.CONTACT_URL.equals(props[i]))
                        altCount++;
            if (altCount > 0) {
                for (int i = 0; i < props.length; i++) {
                    if (!BlogInfo.NAME.equals(props[i]) && 
                        !BlogInfo.DESCRIPTION.equals(props[i]) && 
                        !BlogInfo.EDITION.equals(props[i]) && 
                        !BlogInfo.OWNER_KEY.equals(props[i]) && 
                        !BlogInfo.POSTERS.equals(props[i]) && 
                        !BlogInfo.SIGNATURE.equals(props[i]) &&
                        !BlogInfo.CONTACT_URL.equals(props[i])) {
                        out.write("<tr><td colspan=\"3\">");
                        out.write(HTMLRenderer.sanitizeString(props[i]) + ": " 
                                  + HTMLRenderer.sanitizeString(info.getProperty(props[i])));
                        out.write("</td></tr>\n");
                    }
                }
            }
        }
    }
    
    private static final String INVALID_PROFILE = "<tr><td colspan=\"3\">The profile requested is invalid</td></tr>\n";
}
