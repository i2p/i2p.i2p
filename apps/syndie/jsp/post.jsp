<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" import="net.i2p.data.Base64, net.i2p.client.naming.PetName, net.i2p.client.naming.PetNameDB, net.i2p.syndie.web.*, net.i2p.syndie.sml.*, net.i2p.syndie.data.*, net.i2p.syndie.*, org.mortbay.servlet.MultiPartRequest, java.util.*" %><% 
request.setCharacterEncoding("UTF-8"); 
%><jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" 
/><jsp:useBean scope="session" class="net.i2p.syndie.web.PostBean" id="post" 
/><!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 TRANSITIONAL//EN" "http://www.w3c.org/TR/1999/REC-html401-19991224/loose.dtd">
<html>
<head>
<title>SyndieMedia post</title>
<link href="style.jsp" rel="stylesheet" type="text/css" >
</head>
<body>
<table border="1" cellpadding="0" cellspacing="0" width="100%">
<tr class="b_toplogo"><td colspan="5" valign="top" align="left" class="b_toplogo"><jsp:include page="_toplogo.jsp" /></td></tr>
<tr><td valign="top" align="left" rowspan="2" class="b_leftnav"><jsp:include page="_leftnav.jsp" /></td>
    <jsp:include page="_topnav.jsp" />
    <td valign="top" align="left" rowspan="2" class="b_rightnav"><jsp:include page="_rightnav.jsp" /></td></tr>
<tr class="b_content"><td valign="top" align="left" colspan="3" class="b_content"><%

if (!user.getAuthenticated()) { 
  %><span class="b_postMsgErr">You must be logged in to post</span><%
} else {
  String confirm = request.getParameter("action");
  if ( (confirm != null) && (confirm.equalsIgnoreCase("confirm")) ) {
    String archive = request.getParameter("archive");
    post.setArchive(archive);
    BlogURI uri = post.postEntry(); 
    if (uri != null) {
      %><span class="b_postMsgOk">Blog entry <a class="b_postOkLink" href="<%=HTMLRenderer.getPageURL(user.getBlog(), null, uri.getEntryId(), -1, -1, 
                                                        user.getShowExpanded(), user.getShowImages())%>">posted</a>!</span><%
    } else {
      %><span class="b_postMsgErro">There was an unknown error posting the entry...</span><%
    }
    post.reinitialize();
    post.setUser(user);
  } else {
    // logged in but not confirmed...
    String contentType = request.getContentType();
    if ((contentType != null) && (contentType.indexOf("boundary=") != -1) ) {
        // not confirmed but they posted stuff... gobble up what they give
        // and display it as a preview (then we show the confirm form)
        post.reinitialize();
        post.setUser(user);
        
        MultiPartRequest req = new MultiPartRequest(request);
        String entrySubject = req.getString("entrysubject");
        String entryTags = req.getString("entrytags");
        String entryText = req.getString("entrytext");
        String entryHeaders = req.getString("entryheaders");
        String style = req.getString("style");
        if ( (style != null) && (style.trim().length() > 0) ) {
          if (entryHeaders == null) entryHeaders = HTMLRenderer.HEADER_STYLE + ": " + style;
          else entryHeaders = entryHeaders + '\n' + HTMLRenderer.HEADER_STYLE + ": " + style;
        }
        String replyTo = req.getString(ArchiveViewerBean.PARAM_IN_REPLY_TO);
        if ( (replyTo != null) && (replyTo.trim().length() > 0) ) {
          byte r[] = Base64.decode(replyTo);
          if (r != null) {
            if (entryHeaders == null) entryHeaders = HTMLRenderer.HEADER_IN_REPLY_TO + ": " + new String(r, "UTF-8");
            else entryHeaders = entryHeaders + '\n' + HTMLRenderer.HEADER_IN_REPLY_TO + ": " + new String(r, "UTF-8");
          } else {
            replyTo = null;
          }
        }
        String includeNames = req.getString("includenames");
        if ( (includeNames != null) && (includeNames.trim().length() > 0) ) {
          PetNameDB db = user.getPetNameDB();
          if (entryHeaders == null) entryHeaders = "";
          for (Iterator iter = db.getNames().iterator(); iter.hasNext(); ) {
            PetName pn = db.get((String)iter.next());
            if ( (pn != null) && (pn.getIsPublic()) ) {
              entryHeaders = entryHeaders + '\n' + HTMLRenderer.HEADER_PETNAME + ": " + 
                             pn.getName() + "\t" + pn.getNetwork() + "\t" + pn.getProtocol() + "\t" + pn.getLocation();
            }
          }
        }
        
        post.setSubject(entrySubject);
        post.setTags(entryTags);
        post.setText(entryText);
        post.setHeaders(entryHeaders);

        for (int i = 0; i < 32; i++) {
          String filename = req.getFilename("entryfile" + i);
          if ( (filename != null) && (filename.trim().length() > 0) ) {
            Hashtable params = req.getParams("entryfile" + i);
            String type = "application/octet-stream";
            for (Iterator iter = params.keySet().iterator(); iter.hasNext(); ) {
              String cur = (String)iter.next();
              if ("content-type".equalsIgnoreCase(cur)) {
                type = (String)params.get(cur);
                break;
              }
            }
            post.addAttachment(filename.trim(), req.getInputStream("entryfile" + i), type);
          }
        }

        post.renderPreview(out);
        %><hr /><span class="b_postConfirm"><form action="post.jsp" method="POST">
Please confirm that the above is ok<% if (BlogManager.instance().authorizeRemote(user)) { %>, and select what additional archives you 
want the post transmitted to.  Otherwise, just hit your browser's back arrow and
make changes. 
<select class="b_postConfirm" name="archive">
<option name="">-None-</option>
<% 
PetNameDB db = user.getPetNameDB();
TreeSet names = new TreeSet();
for (Iterator iter = db.getNames().iterator(); iter.hasNext(); ) {
  String name = (String)iter.next();
  PetName pn = db.get(name);
  if ("syndiearchive".equals(pn.getProtocol()))
    names.add(pn.getName());
}
for (Iterator iter = names.iterator(); iter.hasNext(); ) {
  String name = (String)iter.next();
  out.write("<option value=\"" + HTMLRenderer.sanitizeTagParam(name) + "\">" + HTMLRenderer.sanitizeString(name) + "</option>\n");
}
%>
</select><br /><% } %></span>
<input class="b_postConfirm" type="submit" name="action" value="Confirm" /><%
    } else {
      // logged in and not confirmed because they didn't send us anything!  
      // give 'em a new form
%><form action="post.jsp" method="POST" enctype="multipart/form-data"> 
<span class="b_postField">Post subject:</span> <input class="b_postSubject" type="text" size="80" name="entrysubject" value="<%=post.getSubject()%>" /><br />
<span class="b_postField">Post tags:</span> <input class="b_postTags" type="text" size="20" name="entrytags" value="<%=post.getTags()%>" /><br />
<span class="b_postField">Post style:</span> <select class="b_postStyle" name="style">
 <option value="default" selected="true">Default</option>
 <option value="meta">Meta (hide everything but the metadata)</option>
</select><br />
<span class="b_postField">Include public names?</span> <input class="b_postNames" type="checkbox" name="includenames" value="true" /><br />
<span class="b_postField">Post content (in raw SML, no headers):</span><br />
<textarea class="b_postText" rows="6" cols="80" name="entrytext"><%=post.getText()%></textarea><br />
<span class="b_postField">SML cheatsheet:</span><br /><textarea class="b_postCheatsheet" rows="6" cols="80" readonly="true">
* newlines are newlines are newlines. 
* all &lt; and &gt; are replaced with their &amp;symbol;
* [b][/b] = <b>bold</b>
* [i][/i] = <i>italics</i>
* [u][/u] = <i>underline</i>
* [cut]more inside[/cut] = [<a href="#">more inside...</a>]
* [img attachment="1"]alt[/img] = use attachment 1 as an image with 'alt' as the alt text
* [blog name="name" bloghash="base64hash"]description[/blog] = link to all posts in the blog
* [blog name="name" bloghash="base64hash" blogentry="1234"]description[/blog] = link to the specified post in the blog
* [blog name="name" bloghash="base64hash" blogtag="tag"]description[/blog] = link to all posts in the blog with the specified tag
* [blog name="name" blogtag="tag"]description[/blog] = link to all posts in all blogs with the specified tag
* [link schema="eep" location="http://forum.i2p"]text[/link] = offer a link to an external resource (accessible with the given schema)
* [archive name="name" description="they have good stuff" schema="eep" location="http://syndiemedia.i2p/archive/archive.txt"]foo![/archive] = offer an easy way to sync up with a new Syndie archive

SML headers are newline delimited key:value pairs.  Example keys are:
* bgcolor = background color of the post (e.g. bgcolor:#ffccaa or bgcolor=red)
* bgimage = attachment number to place as the background image for the post (only shown if images are enabled) (e.g. bgimage=1)
* textfont = font to put most text into
</textarea><br />
<span class="b_postField">SML post headers:</span><br />
<textarea class="b_postHeaders" rows="3" cols="80" name="entryheaders"><%=post.getHeaders()%></textarea><br /><%
String s = request.getParameter(ArchiveViewerBean.PARAM_IN_REPLY_TO);
if ( (s != null) && (s.trim().length() > 0) ) {%>
<input type="hidden" name="<%=ArchiveViewerBean.PARAM_IN_REPLY_TO%>" value="<%=request.getParameter(ArchiveViewerBean.PARAM_IN_REPLY_TO)%>" />
<% } %>
<span class="b_postField">Attachment 0:</span> <input class="b_postField" type="file" name="entryfile0" /><br />
<span class="b_postField">Attachment 1:</span> <input class="b_postField" type="file" name="entryfile1" /><br />
<span class="b_postField">Attachment 2:</span> <input class="b_postField" type="file" name="entryfile2" /><br />
<span class="b_postField">Attachment 3:</span> <input class="b_postField" type="file" name="entryfile3" /><br />
<hr />
<input class="b_postPreview" type="submit" name="Post" value="Preview..." /> <input class="b_postReset" type="reset" value="Cancel" />
<%
    } // end of the 'logged in, not confirmed, nothing posted' section
  } // end of the 'logged in, not confirmed' section
} // end of the 'logged in' section
%></td></tr>
</table>
</body>
