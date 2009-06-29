<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 */

String uri = request.getRequestURI();
if (uri.endsWith(".css")) {
  response.setContentType("text/css");
} else if (uri.endsWith(".png")) {
  response.setContentType("image/png");
} else if (uri.endsWith(".gif")) {
  response.setContentType("image/gif");
} else if (uri.endsWith(".jpg")) {
  response.setContentType("image/jpeg");
} else if (uri.endsWith(".ico")) {
  response.setContentType("image/x-icon");
}
response.setHeader("Cache-Control", "max-age=86400");  // cache for a day
String base = net.i2p.I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath() +
              java.io.File.separatorChar + "docs";
net.i2p.util.FileUtil.readFile(uri, base, response.getOutputStream());
%>