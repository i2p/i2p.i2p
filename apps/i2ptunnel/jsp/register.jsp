<%@page contentType="text/html" import="net.i2p.i2ptunnel.web.EditBean,net.i2p.client.naming.HostTxtEntry,net.i2p.data.SigningPrivateKey,net.i2p.util.OrderedProperties"
%><%@page trimDirectiveWhitespaces="true"
%><?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<%
  /* right now using EditBean instead of IndexBean for getSpoofedHost() */
  /* but might want to POST to it anyway ??? */
%>
<jsp:useBean class="net.i2p.i2ptunnel.web.EditBean" id="editBean" scope="request" />
<jsp:useBean class="net.i2p.i2ptunnel.web.Messages" id="intl" scope="request" />
<% String tun = request.getParameter("tunnel");
   int curTunnel = -1;
   if (tun != null) {
     try {
       curTunnel = Integer.parseInt(tun);
     } catch (NumberFormatException nfe) {
       curTunnel = -1;
     }
   }
%>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
<head>
    <title><%=intl._t("Hidden Services Manager")%> - <%=intl._t("Registration Helper")%></title>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=UTF-8" />
    <link href="/themes/console/images/favicon.ico" type="image/x-icon" rel="shortcut icon" />

    <% if (editBean.allowCSS()) {
  %><link rel="icon" href="<%=editBean.getTheme()%>images/favicon.ico" />
    <link href="<%=editBean.getTheme()%>default.css" rel="stylesheet" type="text/css" /> 
    <link href="<%=editBean.getTheme()%>i2ptunnel.css" rel="stylesheet" type="text/css" />
    <% }
  %>
<style type='text/css'>
input.default { width: 1px; height: 1px; visibility: hidden; }
</style>
</head>
<body id="tunnelEditPage">
    <div id="pageHeader">
    </div>
<%

  if (editBean.isInitialized()) {

%>
    <form method="post" action="authenticate">

        <div id="tunnelEditPanel" class="panel">
            <div class="header">
<%
    String tunnelTypeName;
    String tunnelType;
    boolean valid = false;
    if (curTunnel >= 0) {
        tunnelTypeName = editBean.getTunnelType(curTunnel);
        tunnelType = editBean.getInternalType(curTunnel);
      %><h4><%=intl._t("Registration Helper")%></h4><% 
    } else {
        tunnelTypeName = "new";
        tunnelType = "new";
      %><h4>Fail</h4><p>Tunnel not found</p><% 
    }
    String b64 = editBean.getDestinationBase64(curTunnel);
    String name = editBean.getSpoofedHost(curTunnel);
    if (name == null || name.equals(""))
        name = editBean.getTunnelName(curTunnel);
%>
                <input type="hidden" name="tunnel" value="<%=curTunnel%>" />
                <input type="hidden" name="nonce" value="<%=net.i2p.i2ptunnel.web.IndexBean.getNextNonce()%>" />
                <input type="hidden" name="type" value="<%=tunnelType%>" />
                <input type="submit" class="default" name="action" value="Save changes" />
            </div>
<%
    if (!"new".equals(tunnelType)) {      
%>
            <div class="separator">
                <hr />
            </div>

            <div id="nameField" class="rowItem">
                <label for="name" accesskey="N">
                    <%=intl._t("Name")%>(<span class="accessKey">N</span>):
                </label>
                <span class="text"><%=editBean.getTunnelName(curTunnel)%></span>
            </div>
            <div id="typeField" class="rowItem">
                <label><%=intl._t("Type")%>:</label>
                <span class="text"><%=tunnelTypeName%></span>
            </div>
                 
<%            
      if (("httpserver".equals(tunnelType)) || ("httpbidirserver".equals(tunnelType))) {
          %><div id="websiteField" class="rowItem">
                <label for="spoofedHost" accesskey="W">
                    <%=intl._t("Website name")%>(<span class="accessKey">W</span>):
                </label>
                <span class="text"><%=editBean.getSpoofedHost(curTunnel)%></span>    
            </div>
<%
       }
%>
            <div id="destinationField" class="rowItem">
                <label for="localDestination" accesskey="L">
                    <%=intl._t("Local destination")%>(<span class="accessKey">L</span>):
                </label>
                <textarea rows="1" style="height: 3em;" cols="60" readonly="readonly" id="localDestination" title="Read Only: Local Destination (if known)" wrap="off" spellcheck="false"><%=editBean.getDestinationBase64(curTunnel)%></textarea>               
            </div>
            <div class="subdivider">
                <hr />
            </div>
<%
       if (b64 == null || b64.length() < 516) {
           %><%=intl._t("Local destination is not available. Start the tunnel.")%><%
       } else if (name == null || name.equals("") || name.contains(" ") || !name.endsWith(".i2p")) {
           if (("httpserver".equals(tunnelType)) || ("httpbidirserver".equals(tunnelType))) {
               %><%=intl._t("To enable registration verification, edit tunnel and set name (or website name) to a valid host name ending in '.i2p'")%><%
           } else {
               %><%=intl._t("To enable registration verification, edit tunnel and set name to a valid host name ending in '.i2p'")%><%
           }
       } else {
           SigningPrivateKey spk = editBean.getSigningPrivateKey(curTunnel);
           if (spk == null) {
               %><%=intl._t("Destination signing key is not available. Start the tunnel.")%><%
           } else {
               valid = true;
               OrderedProperties props = new OrderedProperties();
               HostTxtEntry he = new HostTxtEntry(name, b64, props);
               he.sign(spk);
          %><div id="destinationField" class="rowItem">
                <label><%=intl._t("Authentication Strings")%>:</label>
                <span class="text"><%=intl._t("Select and copy the entire contents of the appropriate box")%></span>
            </div>
            <div id="sigField" class="rowItem">
                <label for="signature">
                    <%=intl._t("Authentication for adding host")%>
                </label>
                <textarea rows="1" style="height: 3em;" cols="60" readonly="readonly" id="localDestination" title="Copy and paste this to the registration site" wrap="off" spellcheck="false"><% he.writeProps(out); %></textarea>               
            </div>
<%
               props.remove(HostTxtEntry.PROP_SIG);
               props.setProperty(HostTxtEntry.PROP_ACTION, HostTxtEntry.ACTION_REMOVE);
               he.signRemove(spk);
          %><div id="sigField" class="rowItem">
                <label for="signature">
                    <%=intl._t("Authentication for removing host")%>
                </label>
                <textarea rows="1" style="height: 3em;" cols="60" readonly="readonly" id="localDestination" title="Copy and paste this to the registration site" wrap="off" spellcheck="false"><% he.writeRemove(out); %></textarea>               
            </div>


            <div class="footer">
            </div>
<%
          }  // spk != null
       }  // valid b64 and name
    }  // !"new".equals(tunnelType)
    if (!valid) {
        %><a href="edit?tunnel=<%=curTunnel%>"><%=intl._t("Go back and edit the tunnel")%></a><%
    }
%>
        </div>


<%
    if (false && valid) {
%>
        <div id="globalOperationsPanel" class="panel">
            <div class="header"></div>
            <div class="footer">
                <div class="toolbox">
                    <input type="hidden" value="true" name="removeConfirm" />
                    <button id="controlCancel" class="control" type="submit" name="action" value="" title="Cancel"><%=intl._t("Cancel")%></button>
                    <button id="controlSave" accesskey="S" class="control" type="submit" name="action" value="authenticate" title="Generate Authentication"><%=intl._t("Generate")%>(<span class="accessKey">S</span>)</button>
                </div>
            </div> 
        </div>
<%
     } // valid
%>
    </form>
    <div id="pageFooter">
    </div>
<%

  } else {
     %>Tunnels are not initialized yet, please reload in two minutes.<%
  }  // isInitialized()

%>
</body>
</html>
