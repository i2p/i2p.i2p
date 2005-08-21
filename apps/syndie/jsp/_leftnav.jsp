<%@page import="net.i2p.syndie.web.ArchiveViewerBean, net.i2p.syndie.*, net.i2p.data.Base64" %>
<jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" />
<jsp:useBean scope="session" class="net.i2p.syndie.data.ArchiveIndex" id="archive" />
<%if (request.getRequestURI().indexOf("register.jsp") == -1) {%>
<jsp:include page="_leftnavprotected.jsp" />
<hr />
<% } %>
<u>Local archive:</u><br />
<b>Posts:</b> <jsp:getProperty name="archive" property="newEntries" />/<jsp:getProperty name="archive" property="allEntries" /><br />
<b>Blogs:</b> <jsp:getProperty name="archive" property="newBlogs" />/<jsp:getProperty name="archive" property="allBlogs" /><br />
<b>Size:</b> <jsp:getProperty name="archive" property="newSizeStr" />/<jsp:getProperty name="archive" property="totalSizeStr" /><br />
<i>(new/total)</i>
<hr />
<u>Latest blogs:</u><br />
<%!int i = 0; %>
<%for (i = 0; i < archive.getNewestBlogCount(); i++) { 
    String keyHash = Base64.encode(archive.getNewestBlog(i).getData());
%><a href="viewmetadata.jsp?<%=ArchiveViewerBean.PARAM_BLOG%>=<%=keyHash%>">
<%=ArchiveViewerBean.getBlogName(keyHash)%></a><br />
<% } %>
<hr />
<u>Latest posts:</u><br />
<%for (i = 0; i < archive.getNewestBlogEntryCount(); i++) { 
    String keyHash = Base64.encode(archive.getNewestBlogEntry(i).getKeyHash().getData());
    long entryId = archive.getNewestBlogEntry(i).getEntryId(); 
%><a href="index.jsp?<%=ArchiveViewerBean.PARAM_BLOG%>=<%=keyHash%>&<%=ArchiveViewerBean.PARAM_ENTRY%>=<%=entryId%>&<%=ArchiveViewerBean.PARAM_EXPAND_ENTRIES%>=true">
<%=ArchiveViewerBean.getEntryTitle(keyHash, entryId)%></a><br />
<% } %>
