<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" import="net.i2p.data.Base64, net.i2p.syndie.web.*, net.i2p.syndie.sml.*, net.i2p.syndie.data.*, net.i2p.syndie.*, org.mortbay.servlet.MultiPartRequest, java.util.*, java.io.*" %>
<% request.setCharacterEncoding("UTF-8"); %>
<jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" />
<html>
<head>
<title>SyndieMedia admin</title>
<link href="style.jsp" rel="stylesheet" type="text/css" />
</head>
<body>
<table border="1" cellpadding="0" cellspacing="0" width="100%">
<tr><td colspan="5" valign="top" align="left"><jsp:include page="_toplogo.jsp" /></td></tr>
<tr><td valign="top" align="left" rowspan="2"><jsp:include page="_leftnav.jsp" /></td>
    <jsp:include page="_topnav.jsp" />
    <td valign="top" align="left" rowspan="2"><jsp:include page="_rightnav.jsp" /></td></tr>
<tr><td valign="top" align="left" colspan="3"><%
if (!user.getAuthenticated()) { 
  %>You must be logged in to configure your Syndie instance!<% 
} else {
  String action = request.getParameter("action");
  if ( (action != null) && ("Save".equals(action)) ) {
    boolean configured = BlogManager.instance().isConfigured();
    String adminPass = request.getParameter("adminpass");
    String regPass = request.getParameter("regpass");
    String remotePass = request.getParameter("remotepass");
    String proxyHost = request.getParameter("proxyhost");
    String proxyPort = request.getParameter("proxyport");
    String selector = request.getParameter("selector");
    if (configured) {
      if ( (adminPass != null) && (BlogManager.instance().authorizeAdmin(adminPass)) ) {
        int port = -1;
        try { port = Integer.parseInt(proxyPort); } catch (NumberFormatException nfe) { port = 4444; }
        BlogManager.instance().configure(regPass, remotePass, adminPass, selector, proxyHost, port, null);
        %>Configuration updated<%
      } else {
        %>Invalid admin password.  If you lost it, please update your syndie.config.<%
      }
    } else {
      int port = -1;
      try { port = Integer.parseInt(proxyPort); } catch (NumberFormatException nfe) { port = 4444; }
      BlogManager.instance().configure(regPass, remotePass, adminPass, selector, proxyHost, port, null);
      %>Configuration saved<%
    }
  } else {
%><form action="admin.jsp" method="POST">
<b>Registration password:</b> <input type="text" name="regpass" size="10" /><br />
Users must specify this password on the registration form to proceed.  If this is
blank, anyone can register.<br />
<b>Remote password:</b> <input type="text" name="remotepass" size="10" /><br />
To access remote archives, users must first provide this password on their 
metadata page.  Remote access is 'dangerous', as it allows the user to instruct
this Syndie instance to establish HTTP connections with arbitrary locations.  If
this field is not specified, no one can use remote archives.<br />
<b>Default remote proxy host:</b> <input type="text" name="proxyhost" size="20" value="localhost" /><br />
<b>Default remote proxy port:</b> <input type="text" name="proxyport" size="5" value="4444" /><br />
This is the default HTTP proxy shown on the remote archive page.<br />
<b>Default blog selector:</b> <input type="text" name="selector" size="40" value="ALL" /><br />
The selector lets you choose what blog (or blogs) are shown on the front page for
new, unregistered users.  Valid values include:<ul>
 <li><code>ALL<code>: all blogs</li>
 <li><code>blog://$blogHash</code>: all posts in the blog identified by $blogHash</li>
 <li><code>blogtag://$blogHash/$tagBase64</code>: all posts in the blog identified by $blogHash 
           tagged by the tag whose modified base64 encoding is $tagBase64</li>
 <li><code>tag://$tagBase64</code>: all posts in any blog tagged by the tag whose 
           modified base64 encoding is $tagBase64</li>
</ul>
<hr />
<% if (!BlogManager.instance().isConfigured()) { 
long passNum = new Random().nextLong(); %>
<b>Administrative password:</b> <input type="password" name="adminpass" size="10" value="<%=passNum%>" /> <br />
Since this Syndie instance is not already configured, you can specify a new 
administrative password which must be presented whenever you update this configuration.
The default value filled in there is <code><%=passNum%></code><br />
<% } else { %>
<b>Administrative password:</b> <input type="password" name="adminpass" size="10" value="" /> <br />
<% } %>
<input type="submit" name="action" value="Save" />
<% } 
} %>
</td></tr>
</table>
</body>
