<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" import="net.i2p.data.Base64, net.i2p.syndie.web.*, net.i2p.syndie.sml.*, net.i2p.syndie.data.*, net.i2p.syndie.*, org.mortbay.servlet.MultiPartRequest, java.util.*, java.io.*" %><% 
request.setCharacterEncoding("UTF-8"); 
%><jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" 
/><!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 TRANSITIONAL//EN" "http://www.w3c.org/TR/1999/REC-html401-19991224/loose.dtd"><html>
<head>
<title>SyndieMedia admin</title>
<link href="style.jsp" rel="stylesheet" type="text/css" >
</head>
<body>
<table border="1" cellpadding="0" cellspacing="0" width="100%">
<tr class="b_toplogo"><td colspan="5" valign="top" align="left" class="b_toplogo"><jsp:include page="_toplogo.jsp" /></td></tr>
<tr><td valign="top" align="left" rowspan="2" class="b_leftnav"><jsp:include page="_leftnav.jsp" /></td>
    <jsp:include page="_topnav.jsp" />
    <td valign="top" align="left" rowspan="2" class="b_rightnav"><jsp:include page="_rightnav.jsp" /></td></tr>
<tr class="b_content"><td valign="top" align="left" colspan="3" class="b_content"><%
if (!user.getAuthenticated()) { 
  %><span class="b_adminMsgErr">You must be logged in to configure your Syndie instance!</span><% 
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
        %><span class="b_adminMsgOk">Configuration updated</span><%
      } else {
        %><span class="b_adminMsgErr">Invalid admin password.  If you lost it, please update your syndie.config.</span><%
      }
    } else {
      int port = -1;
      try { port = Integer.parseInt(proxyPort); } catch (NumberFormatException nfe) { port = 4444; }
      BlogManager.instance().configure(regPass, remotePass, adminPass, selector, proxyHost, port, null);
      %><span class="b_adminMsgOk">Configuration saved</span><%
    }
  } else {
%><form action="admin.jsp" method="POST">
<em class="b_adminField">Registration password:</em> <input class="b_adminField" type="text" name="regpass" size="10" /><br />
<span class="b_adminDescr">Users must specify this password on the registration form to proceed.  If this is
blank, anyone can register.</span><br />
<em class="b_adminField">Remote password:</em> <input class="b_adminField" type="text" name="remotepass" size="10" /><br />
<span class="b_adminDescr">To access remote archives, users must first provide this password on their 
metadata page.  Remote access is 'dangerous', as it allows the user to instruct
this Syndie instance to establish HTTP connections with arbitrary locations.  If
this field is not specified, no one can use remote archives.</span><br />
<em class="b_adminField">Default remote proxy host:</em> <input class="b_adminField" type="text" name="proxyhost" size="20" value="localhost" /><br />
<em class="b_adminField">Default remote proxy port:</em> <input class="b_adminField" type="text" name="proxyport" size="5" value="4444" /><br />
<span class="b_adminDescr">This is the default HTTP proxy shown on the remote archive page.</span><br />
<em class="b_adminField">Default blog selector:</em> <input class="b_adminField" type="text" name="selector" size="40" value="ALL" /><br />
<span class="b_adminDescr">The selector lets you choose what blog (or blogs) are shown on the front page for
new, unregistered users.  Valid values include:<ul class="b_adminDescr">
 <li class="b_adminDescr"><code class="b_adminDescr">ALL</code>: all blogs</li>
 <li class="b_adminDescr"><code class="b_adminDescr">blog://$blogHash</code>: all posts in the blog identified by $blogHash</li>
 <li class="b_adminDescr"><code class="b_adminDescr">blogtag://$blogHash/$tagBase64</code>: all posts in the blog identified by $blogHash 
           tagged by the tag whose modified base64 encoding is $tagBase64</li>
 <li class="b_adminDescr"><code class="b_adminDescr">tag://$tagBase64</code>: all posts in any blog tagged by the tag whose 
           modified base64 encoding is $tagBase64</li>
</ul>
</span>
<hr />
<% if (!BlogManager.instance().isConfigured()) { 
long passNum = new Random().nextLong(); %>
<em class="b_adminField">Administrative password:</em> <input class="b_adminField" type="password" name="adminpass" size="10" value="<%=passNum%>" /> <br />
<span class="b_adminDescr b_adminDescrFirstRun">Since this Syndie instance is not already configured, you can specify a new 
administrative password which must be presented whenever you update this configuration.
The default value filled in there is <code class="b_adminDescr b_adminDescrFirstRun"><%=passNum%></code></span><br />
<% } else { %>
<em class="b_adminField">Administrative password:</em> <input class="b_adminField" type="password" name="adminpass" size="10" value="" /> <br />
<% } %>
<input class="b_adminSave" type="submit" name="action" value="Save" />
<% } 
} %>
</td></tr>
</table>
</body>
