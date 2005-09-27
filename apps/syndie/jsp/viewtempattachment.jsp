<%@page  import="net.i2p.syndie.web.ArchiveViewerBean" %><jsp:useBean 
scope="session" class="net.i2p.syndie.web.PostBean" id="post" /><%
request.setCharacterEncoding("UTF-8");
java.util.Map params = request.getParameterMap();
String id = request.getParameter(ArchiveViewerBean.PARAM_ATTACHMENT);
if (id != null) {
  try {
    int attachmentId = Integer.parseInt(id);
    if ( (attachmentId < 0) || (attachmentId >= post.getAttachmentCount()) ) {
      %>Attachment <%=attachmentId%> does not exist<%
    } else {
      response.setContentType(post.getContentType(attachmentId));
      boolean inline = ArchiveViewerBean.getAttachmentShouldShowInline(params);
      String filename = ArchiveViewerBean.getAttachmentFilename(params);
      if (inline)
        response.setHeader("Content-Disposition", "inline; filename=" + filename);
      else
        response.setHeader("Content-Disposition", "attachment; filename=" + filename);
      post.writeAttachmentData(attachmentId, response.getOutputStream());
    }
  } catch (NumberFormatException nfe) {}
}
%>