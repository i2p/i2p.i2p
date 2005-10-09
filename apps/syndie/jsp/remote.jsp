<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" import="net.i2p.client.naming.PetName, net.i2p.syndie.web.*, net.i2p.syndie.*, net.i2p.syndie.sml.*, java.util.*" %><% 
request.setCharacterEncoding("UTF-8"); 
%><jsp:useBean scope="session" class="net.i2p.syndie.web.RemoteArchiveBean" id="remote" 
/><jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" 
/><jsp:useBean scope="session" class="net.i2p.syndie.data.TransparentArchiveIndex" id="archive" 
/><!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 TRANSITIONAL//EN" "http://www.w3c.org/TR/1999/REC-html401-19991224/loose.dtd">
<html>
<head>
<title>SyndieMedia remote</title>
<link href="style.jsp" rel="stylesheet" type="text/css" >
</head>
<body>
<table border="1" cellpadding="0" cellspacing="0" width="100%">
<tr class="b_toplogo"><td colspan="5" valign="top" align="left" class="b_toplogo"><jsp:include page="_toplogo.jsp" /></td></tr>
<tr><td valign="top" align="left" rowspan="2" class="b_leftnav"><jsp:include page="_leftnav.jsp" /></td>
    <jsp:include page="_topnav.jsp" />
    <td valign="top" align="left" rowspan="2" class="b_rightnav"><jsp:include page="_rightnav.jsp" /></td></tr>
<tr class="b_content"><td valign="top" align="left" colspan="3" class="b_content"><%
if (!BlogManager.instance().authorizeRemote(user)) { 
%><span class="b_remoteMsgErr">Sorry, you are not allowed to access remote archives from here.  Perhaps you should install Syndie yourself?</span><%
} else { %><form action="remote.jsp" method="POST"><span class="b_remoteChooser"><span class="b_remoteChooserField">Import from:</span>
<select class="b_remoteChooserNet" name="schema">
 <option value="web" <%=("web".equals(request.getParameter("schema")) ? "selected=\"true\"" : "")%>>I2P/TOR/Freenet</option>
 <option value="mnet" <%=("mnet".equals(request.getParameter("schema")) ? "selected=\"true\"" : "")%>>MNet</option>
 <option value="feedspace" <%=("feedspace".equals(request.getParameter("schema")) ? "selected=\"true\"" : "")%>>Feedspace</option>
 <option value="usenet" <%=("usenet".equals(request.getParameter("schema")) ? "selected=\"true\"" : "")%>>Usenet</option>
</select> 
<span class="b_remoteChooserField">Proxy</span> 
  <input class="b_remoteChooserHost" type="text" size="10" name="proxyhost" value="<%=BlogManager.instance().getDefaultProxyHost()%>" />
  <input class="b_remoteChooserPort" type="text" size="4" name="proxyport" value="<%=BlogManager.instance().getDefaultProxyPort()%>" /><br />
<span class="b_remoteChooserField">Bookmarked archives:</span> <select class="b_remoteChooserPN" name="archivepetname"><option value="">Custom location</option><%
for (Iterator iter = user.getPetNameDB().iterator(); iter.hasNext(); ) {
  PetName pn = (PetName)iter.next();
  if ("syndiearchive".equals(pn.getProtocol())) {
    %><option value="<%=HTMLRenderer.sanitizeTagParam(pn.getName())%>"><%=HTMLRenderer.sanitizeString(pn.getName())%></option><%
  }
}
%></select> or 
<input class="b_remoteChooserLocation" name="location" size="30" value="<%=(request.getParameter("location") != null ? request.getParameter("location") : "")%>" /> 
<input class="b_remoteChooserContinue" type="submit" name="action" value="Continue..." /><br />
</span>
<%
  String action = request.getParameter("action");
  if ("Continue...".equals(action)) {
    String location = request.getParameter("location");
    String pn = request.getParameter("archivepetname");
    if ( (pn != null) && (pn.trim().length() > 0) ) {
      PetName pnval = user.getPetNameDB().getByName(pn);
      if (pnval != null) location = pnval.getLocation();
    }
    remote.fetchIndex(user, request.getParameter("schema"), location, request.getParameter("proxyhost"), request.getParameter("proxyport"));
  } else if ("Fetch metadata".equals(action)) {
    remote.fetchMetadata(user, request.getParameterMap());
  } else if ("Fetch selected entries".equals(action)) {
    //remote.fetchSelectedEntries(user, request.getParameterMap());
    remote.fetchSelectedBulk(user, request.getParameterMap());
  } else if ("Fetch all new entries".equals(action)) {
    //remote.fetchAllEntries(user, request.getParameterMap());
    remote.fetchSelectedBulk(user, request.getParameterMap());
  } else if ("Post selected entries".equals(action)) {
    remote.postSelectedEntries(user, request.getParameterMap());
  }
  String msgs = remote.getStatus();
  if ( (msgs != null) && (msgs.length() > 0) ) { %><pre class="b_remoteProgress"><%=msgs%>
<a class="b_remoteProgress" href="remote.jsp">Refresh</a></pre><br /><% 
  }
  if (remote.getFetchIndexInProgress()) { %><span class="b_remoteProgress">Please wait while the index is being fetched 
from <%=remote.getRemoteLocation()%>.</span><%
  } else if (remote.getRemoteIndex() != null) {
    // remote index is NOT null!
   %><span class="b_remoteLocation"><%=remote.getRemoteLocation()%></span>
<a class="b_remoteRefetch" href="remote.jsp?schema=<%=remote.getRemoteSchema()%>&location=<%=remote.getRemoteLocation()%><%
if (remote.getProxyHost() != null && remote.getProxyPort() > 0) { 
  %>&proxyhost=<%=remote.getProxyHost()%>&proxyport=<%=remote.getProxyPort()%><%
} %>&action=Continue...">(refetch)</a>:<br />
<%remote.renderDeltaForm(user, archive, out);%>
<textarea class="b_remoteIndex" rows="5" cols="120"><%=remote.getRemoteIndex()%></textarea><%
  }
}
%>
</td></form></tr>
</table>
</body>