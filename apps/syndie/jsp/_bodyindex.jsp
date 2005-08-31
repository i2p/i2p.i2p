<%@page contentType="text/html; charset=UTF-8" import="net.i2p.syndie.web.ArchiveViewerBean, net.i2p.syndie.*" %>
<% request.setCharacterEncoding("UTF-8"); %>
<jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" /><table border="0" width="100%">
<tr><form action="index.jsp"><td nowrap="true">
<b>Blogs:</b> <%ArchiveViewerBean.renderBlogSelector(user, request.getParameterMap(), out);%>
<input type="submit" value="Refresh" />
<input type="submit" name="action" value="<%=ArchiveViewerBean.SEL_ACTION_SET_AS_DEFAULT%>" />
<!-- char encoding: [<%=response.getCharacterEncoding()%>] content type [<%=response.getContentType()%>] Locale [<%=response.getLocale()%>] -->
<%ArchiveViewerBean.renderBlogs(user, request.getParameterMap(), out, "</td></form></tr><tr><td align=\"left\" valign=\"top\">");%></td></tr></table>