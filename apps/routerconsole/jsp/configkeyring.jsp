<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config keyring")%>
<%@include file="summaryajax.jsi" %>
</head><body>
<%@include file="summary.jsi" %>
<h1><%=intl._t("Configuration")%></h1>
<div class="main" id="config_keyring">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.helpers.ConfigKeyringHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <jsp:useBean class="net.i2p.router.web.helpers.ConfigKeyringHelper" id="keyringhelper" scope="request" />
 <jsp:setProperty name="keyringhelper" property="contextId" value="<%=i2pcontextId%>" />
<p id="keyringhelp" class="infohelp">
 <%=intl._t("The router keyring is used to decrypt encrypted leaseSets.")%>
 <%=intl._t("The keyring may contain keys for local or remote encrypted destinations.")%></p>
 <form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <jsp:getProperty name="keyringhelper" property="summary" />
 <h3 class="tabletitle"><%=intl._t("Manual Keyring Addition")%></h3>
 <table id="addkeyring">
        <tr>
          <td class="infohelp" colspan="2">
 <%=intl._t("Enter keys for encrypted remote destinations here.")%>
<%
    net.i2p.util.PortMapper pm = net.i2p.I2PAppContext.getGlobalContext().portMapper();
    if (pm.isRegistered(net.i2p.util.PortMapper.SVC_I2PTUNNEL)) {
%>
 <%=intl._t("Keys for local destinations must be entered on the")%> <a href="i2ptunnel/"><%=intl._t("I2PTunnel page")%></a>.
<%  }  %>
          </td>
        </tr><tr>
          <td align="right"><b><%=intl._t("Full destination, name, Base32, or hash")%>:</b></td>
          <td><input type="text" name="peer" size="55"></td>
        </tr><tr>
          <td align="right"><b><%=intl._t("Type")%>:</b></td>
          <td><select id="encryptMode" name="encryptMode" class="selectbox">
          <option title="<%=intl._t("Enter key provided by server operator.")%>" value="1">
              <%=intl._t("Encrypted")%> (AES)</option>
          <option title="<%=intl._t("Prevents server discovery by floodfills")%>" value="2">
              <%=intl._t("Blinded")%></option>
          <option title="<%=intl._t("Enter password provided by server operator.")%>" value="3">
              <%=intl._t("Blinded with lookup password")%></option>
          <option title="<%=intl._t("Enter key provided by server operator.")%>" value="4" selected="selected">
              <%=intl._t("Encrypted")%> (PSK)</option>
          <option title="<%=intl._t("Enter key and password provided by server operator.")%>" value="5">
              <%=intl._t("Encrypted with lookup password")%> (PSK)</option>
          <option title="<%=intl._t("Key will be generated.")%> <%=intl._t("Send key to server operator.")%>" value="6">
              <%=intl._t("Encrypted")%> (DH)</option>
          <option title="<%=intl._t("Enter password provided by server operator.")%> <%=intl._t("Key will be generated.")%> <%=intl._t("Send key to server operator.")%>" value="7">
              <%=intl._t("Encrypted with lookup password")%> (DH)</option>
          </select></td>
        </tr><tr>
          <td align="right"><b><%=intl._t("Encryption Key")%>:</b></td>
          <td><input type="text" size="55" name="key" title="<%=intl._t("Enter key provided by server operator.")%> <%=intl._t("Leave blank for DH option.")%> <%=intl._t("Key will be generated.")%>"></td>
        </tr><tr>
           <td align="right"><b><%=intl._t("Optional lookup password")%>:</b></td>
           <td><input type="password" name="nofilter_blindedPassword" title="<%=intl._t("Enter password provided by server operator.")%>" class="freetext password" /></td>
        </tr><tr>
          <td align="right" colspan="2">
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" name="action" class="add" value="<%=intl._t("Add key")%>" >
</td></tr></table></form></div></body></html>
