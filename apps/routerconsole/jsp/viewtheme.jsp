<% 
String uri = request.getRequestURI();
if (uri.endsWith(".css")) {
  response.setContentType("text/css");
} else if (uri.endsWith(".png")) {
  response.setContentType("image/png");
} else if (uri.endsWith(".gif")) {
  response.setContentType("image/gif");
} else if (uri.endsWith(".jpg")) {
  response.setContentType("image/jpeg");
}

net.i2p.util.FileUtil.readFile(uri, "./docs", response.getOutputStream());
%>