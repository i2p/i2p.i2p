<%@page import="net.i2p.data.Base64, net.i2p.syndie.web.*, net.i2p.syndie.sml.*, net.i2p.syndie.data.*, net.i2p.syndie.*, org.mortbay.servlet.MultiPartRequest, java.util.*, java.io.*" %>
<jsp:useBean scope="session" class="net.i2p.syndie.data.TransparentArchiveIndex" id="archive" />
<html>
<head>
<title>SyndieMedia import</title>
<link href="style.jsp" rel="stylesheet" type="text/css" />
</head>
<body>
<table border="1" cellpadding="0" cellspacing="0" width="100%">
<tr><td colspan="5" valign="top" align="left"><jsp:include page="_toplogo.jsp" /></td></tr>
<tr><td valign="top" align="left" rowspan="2"><jsp:include page="_leftnav.jsp" /></td>
    <jsp:include page="_topnav.jsp" />
    <td valign="top" align="left" rowspan="2"><jsp:include page="_rightnav.jsp" /></td></tr>
<tr><td valign="top" align="left" colspan="3"><%

String contentType = request.getContentType();
if ((contentType != null) && (contentType.indexOf("boundary=") != -1) ) {
    MultiPartRequest req = new MultiPartRequest(request);
    int metaId = 0;
    while (true) {
      InputStream meta = req.getInputStream("blogmeta" + metaId);
      if (meta == null)
        break;
      if (!BlogManager.instance().importBlogMetadata(meta)) {
        System.err.println("blog meta " + metaId + " failed to be imported");
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
        System.err.println("blog entry " + entryId + " failed to be imported");
        break;
      }
      entryId++;
    }

    if ( (entryId > 0) || (metaId > 0) ) {
      BlogManager.instance().getArchive().regenerateIndex();
      session.setAttribute("index", BlogManager.instance().getArchive().getIndex());
    }
%>Imported <%=entryId%> posts and <%=metaId%> blog metadata files.
<% 
} else { %><form action="import.jsp" method="POST" enctype="multipart/form-data"> 
Blog metadata 0: <input type="file" name="blogmeta0" /><br />
Blog metadata 1: <input type="file" name="blogmeta1" /><br />
Post 0: <input type="file" name="blogpost0" /><br />
Post 1: <input type="file" name="blogpost1" /><br />
Post 2: <input type="file" name="blogpost2" /><br />
Post 3: <input type="file" name="blogpost3" /><br />
Post 4: <input type="file" name="blogpost4" /><br />
Post 5: <input type="file" name="blogpost5" /><br />
Post 6: <input type="file" name="blogpost6" /><br />
Post 7: <input type="file" name="blogpost7" /><br />
Post 8: <input type="file" name="blogpost8" /><br />
Post 9: <input type="file" name="blogpost9" /><br />
<hr />
<input type="submit" name="Post" value="Post entry" /> <input type="reset" value="Cancel" />
<% } %>
</td></tr>
</table>
</body>
