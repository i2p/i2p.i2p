<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" import="net.i2p.data.Base64, net.i2p.syndie.web.*, net.i2p.syndie.sml.*, net.i2p.syndie.data.*, net.i2p.syndie.*, org.mortbay.servlet.MultiPartRequest, java.util.*, java.io.*" %><%
request.setCharacterEncoding("UTF-8"); 
%><jsp:useBean scope="session" class="net.i2p.syndie.data.TransparentArchiveIndex" id="archive" 
/><!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 TRANSITIONAL//EN" "http://www.w3c.org/TR/1999/REC-html401-19991224/loose.dtd">
<html>
<head>
<title>SyndieMedia import</title>
<link href="style.jsp" rel="stylesheet" type="text/css" >
</head>
<body>
<table border="1" cellpadding="0" cellspacing="0" width="100%">
<tr class="b_toplogo"><td colspan="5" valign="top" align="left" class="b_toplogo"><jsp:include page="_toplogo.jsp" /></td></tr>
<tr><td valign="top" align="left" rowspan="2" class="b_leftnav"><jsp:include page="_leftnav.jsp" /></td>
    <jsp:include page="_topnav.jsp" />
    <td valign="top" align="left" rowspan="2" class="b_rightnav"><jsp:include page="_rightnav.jsp" /></td></tr>
<tr class="b_content"><td valign="top" align="left" colspan="3" class="b_content"><%

String contentType = request.getContentType();
if ((contentType != null) && (contentType.indexOf("boundary=") != -1) ) {
    MultiPartRequest req = new MultiPartRequest(request);
    int metaId = 0;
    while (true) {
      InputStream meta = req.getInputStream("blogmeta" + metaId);
      if (meta == null)
        break;
      if (!BlogManager.instance().importBlogMetadata(meta)) {
        %><span class="b_importMsgErr">Metadata <%=metaId%> failed to be imported</span><br /><%
        break;
       }
      metaId++;
    }
    int entryId = 0;
    while (true) {
      InputStream entry = req.getInputStream("blogpost" + entryId);
      if (entry == null)
        break;
      if (!BlogManager.instance().importBlogEntry(entry)) {
        %><span class="b_importMsgErr">Entry <%=entryId%> failed to be imported</span><br /><%
        break;
      }
      entryId++;
    }

    if ( (entryId > 0) || (metaId > 0) ) {
      BlogManager.instance().getArchive().regenerateIndex();
      session.setAttribute("index", BlogManager.instance().getArchive().getIndex());
    }
%><span class="b_importMsgOk">Imported <%=entryId%> posts and <%=metaId%> blog metadata files.</span>
<% 
} else { %><form action="import.jsp" method="POST" enctype="multipart/form-data"> 
<span class="b_importField">Blog metadata 0:</span> <input class="b_importField" type="file" name="blogmeta0" /><br />
<span class="b_importField">Blog metadata 1:</span> <input class="b_importField" type="file" name="blogmeta1" /><br />
<span class="b_importField">Post 0:</span> <input class="b_importField" type="file" name="blogpost0" /><br />
<span class="b_importField">Post 1:</span> <input class="b_importField" type="file" name="blogpost1" /><br />
<span class="b_importField">Post 2:</span> <input class="b_importField" type="file" name="blogpost2" /><br />
<span class="b_importField">Post 3:</span> <input class="b_importField" type="file" name="blogpost3" /><br />
<span class="b_importField">Post 4:</span> <input class="b_importField" type="file" name="blogpost4" /><br />
<span class="b_importField">Post 5:</span> <input class="b_importField" type="file" name="blogpost5" /><br />
<span class="b_importField">Post 6:</span> <input class="b_importField" type="file" name="blogpost6" /><br />
<span class="b_importField">Post 7:</span> <input class="b_importField" type="file" name="blogpost7" /><br />
<span class="b_importField">Post 8:</span> <input class="b_importField" type="file" name="blogpost8" /><br />
<span class="b_importField">Post 9:</span> <input class="b_importField" type="file" name="blogpost9" /><br />
<hr />
<input class="b_importSubmit" type="submit" name="Post" value="Post entry" /> <input class="b_importCancel" type="reset" value="Cancel" />
<% } %>
</td></tr>
</table>
</body>
