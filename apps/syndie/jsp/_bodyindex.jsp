<%@page contentType="text/html; charset=UTF-8" import="net.i2p.syndie.web.ArchiveViewerBean, net.i2p.syndie.*" %>
<% request.setCharacterEncoding("UTF-8"); %>
<jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" /><%
if (user.getAuthenticated() && (null != request.getParameter("action")) ) {
  %><!-- <%=request.getParameterMap()%> --><%
  String blog = request.getParameter("blog");
  String group = null;
  if (request.getParameter("action").equals("Bookmark blog"))
    group = "Favorites";
  else if (request.getParameter("action").equals("Ignore blog"))
    group = "Ignore";
  boolean unignore = ("Unignore blog".equals(request.getParameter("action")));

  String name = user.getPetNameDB().getNameByLocation(blog);
  if (name == null)
    name = request.getParameter("name");
  if (name == null)
    name = blog;
  if ( (name != null) && (blog != null) && ( (group != null) || (unignore) ) ) {
    PetName pn = user.getPetNameDB().get(name);
    if (pn != null) {
      if (unignore)
        pn.removeGroup("Ignore");
      else
        pn.addGroup(group);
    } else {
      pn = new PetName(name, "syndie", "syndieblog", blog);
      pn.addGroup(group);
      user.getPetNameDB().set(name, pn);
    }
    BlogManager.instance().saveUser(user);
  }
}
%><table border="0" width="100%">
<tr><form action="index.jsp"><td nowrap="true">
<b>Blogs:</b> <%ArchiveViewerBean.renderBlogSelector(user, request.getParameterMap(), out);%>
<input type="submit" value="Refresh" />
<input type="submit" name="action" value="<%=ArchiveViewerBean.SEL_ACTION_SET_AS_DEFAULT%>" />
<!-- char encoding: [<%=response.getCharacterEncoding()%>] content type [<%=response.getContentType()%>] Locale [<%=response.getLocale()%>] -->
<%ArchiveViewerBean.renderBlogs(user, request.getParameterMap(), out, "</td></form></tr><tr><td align=\"left\" valign=\"top\">");%></td></tr></table>