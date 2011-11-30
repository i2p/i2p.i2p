<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config keyring")%>
</head><body>

<%@include file="summary.jsi" %>
<h1><%=intl._("I2P Keyring Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigKeyringHandler" id="formhandler" scope="request" />
 <% formhandler.storeMethod(request.getMethod()); %>
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
 <jsp:useBean class="net.i2p.router.web.ConfigKeyringHelper" id="keyringhelper" scope="request" />
 <jsp:setProperty name="keyringhelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<div class="configure"><h2><%=intl._("Keyring")%></h2><p>
 <%=intl._("The router keyring is used to decrypt encrypted leaseSets.")%>
 <%=intl._("The keyring may contain keys for local or remote encrypted destinations.")%></p>
 <div class="wideload"><p>
 <jsp:getProperty name="keyringhelper" property="summary" />
</p></div>

 <form action="" method="POST">
 <input type="hidden" name="nonce" value="<jsp:getProperty name="formhandler" property="newNonce" />" >
 <h3><%=intl._("Manual Keyring Addition")%></h3><p>
 <%=intl._("Enter keys for encrypted remote destinations here.")%>
 <%=intl._("Keys for local destinations must be entered on the")%> <a href="i2ptunnel/"><%=intl._("I2PTunnel page")%></a>.
</p>
  <div class="wideload">
      <p><table><tr>
          <td class="mediumtags" align="right"><%=intl._("Dest. name, hash, or full key")%>:</td>
          <td><textarea name="peer" cols="44" rows="1" style="height: 3em;" wrap="off" spellcheck="false"></textarea></td>
        </tr><tr>
          <td class="mediumtags" align="right"><%=intl._("Encryption Key")%>:</td>
          <td><input type="text" size="55" name="key" ></td>
        </tr><tr>
          <td align="right" colspan="2">
<input type="reset" class="cancel" value="<%=intl._("Cancel")%>" >
<input type="submit" name="action" class="delete" value="<%=intl._("Delete key")%>" >
<input type="submit" name="action" class="add" value="<%=intl._("Add key")%>" >
</td></tr></table></p></div></form></div></div></body></html>
