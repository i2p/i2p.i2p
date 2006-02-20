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
 * Schedule the import of atom/rss feeds
 */
public class ImportFeedServlet extends BaseServlet { 
    protected String getTitle() { return "Syndie :: Import feed"; }
    
    protected void renderServletDetails(User user, HttpServletRequest req, PrintWriter out, ThreadIndex index, 
                                        int threadOffset, BlogURI visibleEntry, Archive archive) throws IOException {
 
        if (!BlogManager.instance().authorizeRemote(user)) {
            out.write("<tr><td colspan=\"3\"><span class=\"b_rssMsgErr\">You are not authorized for remote access.</span></td></tr>\n");
            return;
        } else {
            out.write("<tr><td colspan=\"3\">");
          
            String url=req.getParameter("url");
            if (url != null) 
                url = url.trim();
            String blog=req.getParameter("blog");
            if (blog != null)
                blog=blog.trim();
            String tagPrefix = req.getParameter("tagprefix");
            if (tagPrefix != null)
                tagPrefix=tagPrefix.trim();
            String action = req.getParameter("action");
            if ( (action != null) && ("Add".equals(action)) ) {
                if(url==null || blog==null || tagPrefix==null) {
                    out.write("<span class=\"b_rssImportMsgErr\">Please fill in all fields</span><br />\n");
                } else {
                    boolean ret = BlogManager.instance().addRssFeed(url, blog, tagPrefix);
                    if (!ret) {
                        out.write("<span class=\"b_rssImportMsgErr\">addRssFeed failure.</span>");
                    } else {
                        out.write("<span class=\"b_rssImportMsgOk\">RSS feed added.</span>");
                    }
                }
            } else if ( (action != null) && ("Change".equals(action)) ) {
                String lastUrl=req.getParameter("lasturl");
                String lastBlog=req.getParameter("lastblog");
                String lastTagPrefix=req.getParameter("lasttagprefix");
              
                if (url == null || blog == null || tagPrefix == null || 
                    lastUrl == null || lastBlog == null || lastTagPrefix == null) {
                    out.write("<span class=\"b_rssImportMsgErr\">error, some fields were empty.</span><br />");
                } else {
                    boolean ret = BlogManager.instance().deleteRssFeed(lastUrl,lastBlog,lastTagPrefix);
                    if (!ret) {
                        out.write("<span class=\"b_rssImportMsgErr\">Could not delete while attempting to change.</span>");
                    } else {
                        ret = BlogManager.instance().addRssFeed(url,blog,tagPrefix);
                        if (!ret) {
                            out.write("<span class=\"b_rssImportMsgErr\">Could not add while attempting to change.</span>");
                        } else {
                            out.write("<span class=\"b_rssImportMsgOk\">Ok, changed successfully.</span>");
                        }
                    }
                }
            } else if ( (action != null) && ("Delete".equals(action)) ) {
                if (url == null || blog == null || tagPrefix == null) {
                    out.write("<span class=\"b_rssImportMsgErr\">error, some fields were empty.</span><br />");
                } else {
                    boolean ret = BlogManager.instance().deleteRssFeed(url,blog,tagPrefix);
                    if (!ret) {
                        out.write("<span class=\"b_rssImportMsgErr\">error, could not delete.</span>");
                    } else {
                        out.write("<span class=\"b_rssImportMsgOk\">ok, deleted successfully.</span>");
                    }
                }
            }
          
            String blogStr = user.getBlogStr();
            if (blogStr == null)
                blogStr="";
    
            out.write("<p>Here you can add RSS feeds that will be periodically polled and added to your syndie. </p>");
            out.write("<form action=\"" + req.getRequestURI() + "\" method=\"POST\">");
            writeAuthActionFields(out);
            out.write("RSS URL. (e.g. http://tracker.postman.i2p/rss.php)<br />\n");
            out.write("<em><span class=\"b_rssImportField\">url:</span></em> <input class=\"b_rssImportField\" type=\"text\" size=\"50\" name=\"url\" /><br />\n");
            out.write("Blog hash to which the RSS entries will get posted, defaults to the one you're logged in to.<br />\n");
            out.write("<em><span class=\"b_rssImportField\">blog:</span></em> <input class=\"b_rssImportField\" type=\"text\" value=\"");
            out.write(blogStr);
            out.write("\" size=\"20\" name=\"blog\" /><br />\n");
            out.write("This will be prepended to any tags that the RSS feed contains. (e.g. feed.tracker)<br />\n");
            out.write("<em><span class=\"b_rssImportField\">tagprefix:</span></em>\n");
            out.write("<input class=\"b_rssImportField\" type=\"text\" value=\"feed\" size=\"20\" name=\"tagprefix\" /><br />\n");
            out.write("<input class=\"b_rssImportSubmit\" type=\"submit\" name=\"action\" value=\"Add\" />\n");
            out.write("<input class=\"b_rssImportCancel\" type=\"reset\" value=\"Cancel\" />\n");
            out.write("</form>\n");

            List feedList = BlogManager.instance().getRssFeeds();
            if (feedList.size()>0) {
                out.write("<hr /><h3>Subscriptions:</h3><br />\n");
                out.write("<table border=\"0\" width=\"100%\" class=\"b_rss\">\n");
                out.write("<tr class=\"b_rssHeader\">\n");
                out.write("<td class=\"b_rssHeader\"><em class=\"b_rssHeader\">Url</em></td>\n");
                out.write("<td class=\"b_rssHeader\"><em class=\"b_rssHeader\">Blog</em></td>\n");
                out.write("<td class=\"b_rssHeader\"><em class=\"b_rssHeader\">TagPrefix</em></td>\n");
                out.write("<td class=\"b_rssHeader\">&nbsp;</td></tr>\n");

                Iterator iter = feedList.iterator();
                while (iter.hasNext()) {
                    String fields[] = (String[])iter.next();
                    url = fields[0];
                    blog = fields[1];
                    tagPrefix = fields[2];
                    StringBuffer buf = new StringBuffer(128);
            
                    buf.append("<tr class=\"b_rssDetail\"><form action=\"" + req.getRequestURI() + "\" method=\"POST\">");
                    writeAuthActionFields(out);
                    buf.append("<input type=\"hidden\" name=\"lasturl\" value=\"").append(url).append("\" />");
                    buf.append("<input type=\"hidden\" name=\"lastblog\" value=\"").append(blog).append("\" />");
                    buf.append("<input type=\"hidden\" name=\"lasttagprefix\" value=\"").append(tagPrefix).append("\" />");

                    buf.append("<td class=\"b_rssUrl\"><input class=\"b_rssUrl\" type=\"text\" size=\"50\" name=\"url\" value=\"").append(url).append("\" /></td>");
                    buf.append("<td class=\"b_rssBlog\"><input class=\"b_rssBlog\" type=\"text\" size=\"20\" name=\"blog\" value=\"").append(blog).append("\" /></td>");
                    buf.append("<td class=\"b_rssPrefix\"><input class=\"b_rssPrefix\" type=\"text\" size=\"20\" name=\"tagprefix\" value=\"").append(tagPrefix).append("\" /></td>");
                    buf.append("<td class=\"b_rssDetail\" nowrap=\"nowrap\">");

                    buf.append("<input class=\"b_rssChange\" type=\"submit\" name=\"action\" value=\"Change\" />");
                    buf.append("<input class=\"b_rssDelete\" type=\"submit\" name=\"action\" value=\"Delete\" />");
                  
                    buf.append("</td></form></tr>");
                    out.write(buf.toString());
                    buf.setLength(0);
                }
              
                out.write("</table>\n");
            } // end iterating over feeds
          
            out.write("</td></tr>\n");
        }
    }
}
