<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" import="net.i2p.data.Base64, net.i2p.syndie.web.*, net.i2p.syndie.sml.*, net.i2p.syndie.data.*, net.i2p.syndie.*, org.mortbay.servlet.MultiPartRequest, java.util.*, java.io.*" %><%
request.setCharacterEncoding("UTF-8");  %><jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" 
/><!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 TRANSITIONAL//EN" "http://www.w3c.org/TR/1999/REC-html401-19991224/loose.dtd">
<html>
<head>
<title>SyndieMedia rss import configuration</title>
<link href="style.jsp" rel="stylesheet" type="text/css" >
</head>
<body>
<table border="1" cellpadding="0" cellspacing="0" width="100%">
<tr class="b_toplogo"><td colspan="5" valign="top" align="left" class="b_toplogo"><jsp:include page="_toplogo.jsp" /></td></tr>
<tr><td valign="top" align="left" rowspan="2" class="b_leftnav"><jsp:include page="_leftnav.jsp" /></td>
    <jsp:include page="_topnav.jsp" />
    <td valign="top" align="left" rowspan="2" class="b_rightnav"><jsp:include page="_rightnav.jsp" /></td></tr>
<tr class="b_content"><td valign="top" align="left" colspan="3" class="b_content"><%

BlogManager bm = BlogManager.instance();
if (!user.getAuthenticated()) {
    %><span class="b_rssMsgErr">Please log in.</span><% 
}
else if(!bm.authorizeRemote(user)) { 
    %><span class="b_rssMsgErr">You are not authorized for remote access.</span><% 
} else {
    String url=request.getParameter("url");
    if(url!=null) url=url.trim();
    String blog=request.getParameter("blog");
    if(blog!=null) blog=blog.trim();
    String tagPrefix=request.getParameter("tagprefix");
    if(tagPrefix!=null) tagPrefix=tagPrefix.trim();
    String action = request.getParameter("action");
    if ( (action != null) && ("Add".equals(action)) ) {
        if(url==null || blog==null || tagPrefix==null) {
            %><span class="b_rssImportMsgErr">Please fill in all fields</span><br /><%
        } else {
            boolean ret=bm.addRssFeed(url,blog,tagPrefix);
	    if(!ret) {
                %><span class="b_rssImportMsgErr">addRssFeed failure.</span><% 
            } else {
                %><span class="b_rssImportMsgOk">RSS feed added.</span><% 
            }
        }
    } else if ( (action != null) && ("Change".equals(action)) ) {
        String lastUrl=request.getParameter("lasturl");
        String lastBlog=request.getParameter("lastblog");
        String lastTagPrefix=request.getParameter("lasttagprefix");
        if(url==null || blog==null || tagPrefix==null || lastUrl==null || lastBlog==null || lastTagPrefix==null) {
            %><span class="b_rssImportMsgErr">error, some fields were empty.</span><br /><%
        } else {
            boolean ret=bm.deleteRssFeed(lastUrl,lastBlog,lastTagPrefix);
            if(!ret) {
                %><span class="b_rssImportMsgErr">Could not delete while attempting to change.</span><% 
            } else {
                ret=bm.addRssFeed(url,blog,tagPrefix);
                if(!ret) {
                    %><span class="b_rssImportMsgErr">Could not add while attempting to change.</span><% 
                } else {
                    %><span class="b_rssImportMsgOk">Ok, changed successfully.</span><% 
                }
            }
        }       
    } else if ( (action != null) && ("Delete".equals(action)) ) {
        %><span class="b_rssImportMsgErr">Delete some thing</span><br /><%
        if(url==null || blog==null || tagPrefix==null) {
            %><span class="b_rssImportMsgErr">error, some fields were empty.</span><br /><%
        } else {
            boolean ret=bm.instance().deleteRssFeed(url,blog,tagPrefix);
            if(!ret) {
                %><span class="b_rssImportMsgErr">error, could not delete.</span><% 
            } else {
                %><span class="b_rssImportMsgOk">ok, deleted successfully.</span><% 
            }
        }       
    }
    String blogStr=user.getBlogStr();
    if(blogStr==null)
        blogStr="";
    
    ///////////////////////////////////////////////
%>
<p>Here you can add RSS feeds that will be periodically polled and added to your syndie. </p>
<form action="rssimport.jsp" method="POST"> 
RSS URL. (e.g. http://tracker.postman.i2p/rss.php)<br />
<em><span class="b_rssImportField">url:</span></em> <input class="b_rssImportField" type="text" size="50" name="url" /><br />
Blog hash to which the RSS entries will get posted, defaults to the one you're logged in to.<br />
<em><span class="b_rssImportField">blog:</span></em> <input class="b_rssImportField" type="text" <%="value=\""+blogStr+"\""%> size="20" name="blog" /><br />
This will be prepended to any tags that the RSS feed contains. (e.g. feed.tracker)<br />
<em><span class="b_rssImportField">tagprefix:</span></em> <input class="b_rssImportField" type="text" value="feed" size="20" name="tagprefix" /><br />
<input class="b_rssImportSubmit" type="submit" name="action" value="Add" /> <input class="b_rssImportCancel" type="reset" value="Cancel" /></form>
<%
///////////////////////////////////////////////
    List feedList = bm.getRssFeeds();
    if(feedList.size()>0) {
%>
<hr /><h3>Subscriptions:</h3><br />
<table border="0" width="100%" class="b_rss">
<tr class="b_rssHeader">
<td class="b_rssHeader"><em class="b_rssHeader">Url</em></td>
<td class="b_rssHeader"><em class="b_rssHeader">Blog</em></td>
<td class="b_rssHeader"><em class="b_rssHeader">TagPrefix</em></td>
<td class="b_rssHeader">&nbsp;</td></tr>
<%
        Iterator iter = feedList.iterator();
        while(iter.hasNext()) {
            String fields[]=(String[])iter.next();
            url=fields[0];
            blog=fields[1];
            tagPrefix=fields[2];
            StringBuffer buf = new StringBuffer(128);
            
            buf.append("<tr class=\"b_rssDetail\"><form action=\"rssimport.jsp\" method=\"POST\">");
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
    }
/*
<p><h3>todo:</h3>
<p>caching (eepget should do it)
<p>enclosures support (requires cvs rome)
<p>syndie.sucker.minHistory/maxHistory used to roll over the history file?
<p>configurable update period
*/
%>
</table>
<hr />
<% 
} 
%>
</td></tr>
</table>
</body>
