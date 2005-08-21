<%@page import="net.i2p.syndie.web.ArchiveViewerBean, net.i2p.syndie.*" %>
<jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" />
<form action="index.jsp">
<b>Blogs:</b> <%ArchiveViewerBean.renderBlogSelector(user, request.getParameterMap(), out);%>
<input type="submit" value="Refresh" /></form>
<hr />

<%ArchiveViewerBean.renderBlogs(user, request.getParameterMap(), out); out.flush(); %>