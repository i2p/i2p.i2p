<%@page contentType="text/html; charset=UTF-8" import="net.i2p.syndie.web.ArchiveViewerBean, net.i2p.syndie.*, net.i2p.client.naming.PetName" %>
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

  PetName pn = user.getPetNameDB().getByLocation(blog);
  String name = null;
  if (pn != null) name = pn.getName();
  if (name == null)
    name = request.getParameter("name");
  if (name == null)
    name = blog;
  if ( (name != null) && (blog != null) && ( (group != null) || (unignore) ) ) {
    if (pn != null) {
      if (unignore)
        pn.removeGroup("Ignore");
      else
        pn.addGroup(group);
    } else {
      pn = new PetName(name, "syndie", "syndieblog", blog);
      pn.addGroup(group);
      user.getPetNameDB().add(pn);
    }
    BlogManager.instance().saveUser(user);
  }
}
%><table border="0" width="100%" class="b_content">
<tr class="b_content"><form action="index.jsp"><td nowrap="nowrap">
<em class="b_selectorTitle">Blogs:</em> <span class="b_selector"><%ArchiveViewerBean.renderBlogSelector(user, request.getParameterMap(), out);%></span>
<input type="submit" value="Refresh" class="b_selectorRefresh" />
<input type="submit" name="action" value="<%=ArchiveViewerBean.SEL_ACTION_SET_AS_DEFAULT%>" class="b_selectorDefault" />
<%ArchiveViewerBean.renderBlogs(user, request.getParameterMap(), out, "</td></form></tr><tr><td align=\"left\" valign=\"top\">");%></td></tr></table>