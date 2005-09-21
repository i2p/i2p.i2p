<%@page autoFlush="false" %><% 

request.setCharacterEncoding("UTF-8"); 
java.util.Map params = request.getParameterMap();
response.setContentType(net.i2p.syndie.web.ArchiveViewerBean.getAttachmentContentType(params));
int len = net.i2p.syndie.web.ArchiveViewerBean.getAttachmentContentLength(params);
if (len >= 0)
  response.setContentLength(len);
net.i2p.syndie.web.ArchiveViewerBean.renderAttachment(params, response.getOutputStream());
%>