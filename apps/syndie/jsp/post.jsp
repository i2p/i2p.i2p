<%@page import="net.i2p.data.Base64, net.i2p.syndie.web.*, net.i2p.syndie.sml.*, net.i2p.syndie.data.*, net.i2p.syndie.*, org.mortbay.servlet.MultiPartRequest, java.util.*" %>
<jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" />
<jsp:useBean scope="session" class="net.i2p.syndie.web.PostBean" id="post" />
<html>
<head>
<title>SyndieMedia</title>
<link href="style.jsp" rel="stylesheet" type="text/css" />
</head>
<body>
<table border="1" cellpadding="0" cellspacing="0" width="100%">
<tr><td colspan="5" valign="top" align="left"><jsp:include page="_toplogo.jsp" /></td></tr>
<tr><td valign="top" align="left" rowspan="2"><jsp:include page="_leftnav.jsp" /></td>
    <jsp:include page="_topnav.jsp" />
    <td valign="top" align="left" rowspan="2"><jsp:include page="_rightnav.jsp" /></td></tr>
<tr><td valign="top" align="left" colspan="3"><%

if (!user.getAuthenticated()) { 
  %>You must be logged in to post<%
} else {
  String confirm = request.getParameter("confirm");
  if ( (confirm != null) && (confirm.equalsIgnoreCase("true")) ) {
    BlogURI uri = post.postEntry(); 
    if (uri != null) {
      %>Blog entry <a href="<%=HTMLRenderer.getPageURL(user.getBlog(), null, uri.getEntryId(), -1, -1, 
                                                        user.getShowExpanded(), user.getShowImages())%>">posted</a>!<%
    } else {
      %>There was an unknown error posting the entry...<%
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
        String replyTo = req.getString(ArchiveViewerBean.PARAM_IN_REPLY_TO);
        if ( (replyTo != null) && (replyTo.trim().length() > 0) ) {
          byte r[] = Base64.decode(replyTo);
          if (r != null) {
            if (entryHeaders == null) entryHeaders = HTMLRenderer.HEADER_IN_REPLY_TO + ": " + new String(r);
            else entryHeaders = entryHeaders + '\n' + HTMLRenderer.HEADER_IN_REPLY_TO + ": " + new String(r);
          } else {
            replyTo = null;
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
        %><hr />Please <a href="post.jsp?confirm=true">confirm</a> that this is ok.  Otherwise, just go back and make changes.<%
    } else {
      // logged in and not confirmed because they didn't send us anything!  
      // give 'em a new form
%><form action="post.jsp" method="POST" enctype="multipart/form-data"> 
Post subject: <input type="text" size="80" name="entrysubject" value="<%=post.getSubject()%>" /><br />
Post tags: <input type="text" size="20" name="entrytags" value="<%=post.getTags()%>" /><br />
Post content (in raw SML, no headers):<br />
<textarea rows="6" cols="80" name="entrytext"><%=post.getText()%></textarea><br />
<b>SML cheatsheet:</b><br /><textarea rows="6" cols="80" readonly="true">
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

SML headers are newline delimited key=value pairs.  Example keys are:
* bgcolor = background color of the post (e.g. bgcolor=#ffccaa or bgcolor=red)
* bgimage = attachment number to place as the background image for the post (only shown if images are enabled) (e.g. bgimage=1)
* textfont = font to put most text into
</textarea><br />
SML post headers:<br />
<textarea rows="3" cols="80" name="entryheaders"><%=post.getHeaders()%></textarea><br /><%
String s = request.getParameter(ArchiveViewerBean.PARAM_IN_REPLY_TO);
if ( (s != null) && (s.trim().length() > 0) ) {%>
<input type="hidden" name="<%=ArchiveViewerBean.PARAM_IN_REPLY_TO%>" value="<%=request.getParameter(ArchiveViewerBean.PARAM_IN_REPLY_TO)%>" />
<% } %>

Attachment 0: <input type="file" name="entryfile0" /><br />
Attachment 1: <input type="file" name="entryfile1" /><br />
Attachment 2: <input type="file" name="entryfile2" /><br />
Attachment 3: <input type="file" name="entryfile3" /><br />
Attachment 4: <input type="file" name="entryfile4" /><br />
Attachment 5: <input type="file" name="entryfile5" /><br />
Attachment 6: <input type="file" name="entryfile6" /><br />
Attachment 7: <input type="file" name="entryfile7" /><br />
Attachment 8: <input type="file" name="entryfile8" /><br />
Attachment 9: <input type="file" name="entryfile9" /><br />
<hr />
<input type="submit" name="Post" value="Preview..." /> <input type="reset" value="Cancel" />
<%
    } // end of the 'logged in, not confirmed, nothing posted' section
  } // end of the 'logged in, not confirmed' section
} // end of the 'logged in' section
%></td></tr>
</table>
</body>
