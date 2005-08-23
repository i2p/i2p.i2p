<%@page import="net.i2p.syndie.web.ArchiveViewerBean, net.i2p.syndie.*" %>
<jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" />
<form action="index.jsp">
<b>Blogs:</b> <%ArchiveViewerBean.renderBlogSelector(user, request.getParameterMap(), out);%>
<input type="submit" value="Refresh" />
<input type="submit" name="action" value="<%=ArchiveViewerBean.SEL_ACTION_SET_AS_DEFAULT%>" /></form>
<hr />

<%ArchiveViewerBean.renderBlogs(user, request.getParameterMap(), out); out.flush(); %>