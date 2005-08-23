<%@page  import="net.i2p.syndie.web.ArchiveViewerBean" %><jsp:useBean 
scope="session" class="net.i2p.syndie.web.PostBean" id="post" /><%
String id = request.getParameter(ArchiveViewerBean.PARAM_ATTACHMENT);
if (id != null) {
  try {
    int attachmentId = Integer.parseInt(id);
    if ( (attachmentId < 0) || (attachmentId >= post.getAttachmentCount()) ) {
      %>Attachment <%=attachmentId%> does not exist<%
    } else {
      response.setContentType(post.getContentType(attachmentId));
      post.writeAttachmentData(attachmentId, response.getOutputStream());
    }
  } catch (NumberFormatException nfe) {}
}
%>