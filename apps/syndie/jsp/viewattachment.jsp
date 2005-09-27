<%@page autoFlush="false" import="net.i2p.syndie.web.*" %><% 

request.setCharacterEncoding("UTF-8"); 
java.util.Map params = request.getParameterMap();
response.setContentType(ArchiveViewerBean.getAttachmentContentType(params));
boolean inline = ArchiveViewerBean.getAttachmentShouldShowInline(params);
String filename = ArchiveViewerBean.getAttachmentFilename(params);
if (inline)
  response.setHeader("Content-Disposition", "inline; filename=" + filename);
else
  response.setHeader("Content-Disposition", "attachment; filename=" + filename);
int len = ArchiveViewerBean.getAttachmentContentLength(params);
if (len >= 0)
  response.setContentLength(len);
ArchiveViewerBean.renderAttachment(params, response.getOutputStream());
%>