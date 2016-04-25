<%@page contentType="text/html" import="java.io.InputStream,net.i2p.i2ptunnel.web.EditBean,net.i2p.servlet.RequestWrapper,net.i2p.client.I2PSessionException,net.i2p.client.naming.HostTxtEntry,net.i2p.data.PrivateKeyFile,net.i2p.data.SigningPrivateKey,net.i2p.util.OrderedProperties"
%><%@page trimDirectiveWhitespaces="true"
%><?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<%
  /* right now using EditBean instead of IndexBean for getSpoofedHost() */
  /* but might want to POST to it anyway ??? */
%>
<jsp:useBean class="net.i2p.i2ptunnel.web.EditBean" id="editBean" scope="request" />
<jsp:useBean class="net.i2p.i2ptunnel.web.Messages" id="intl" scope="request" />
<%
   RequestWrapper wrequest = new RequestWrapper(request);
   String tun = wrequest.getParameter("tunnel");
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
    <form method="post" enctype="multipart/form-data" action="register" accept-charset="UTF-8">
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
  <span class="comment">
    <%=intl._t("Please be sure to select, copy, and paste the entire contents of the appropriate authentication data into the form of your favorite registration site")%>
  </span>
            <div class="separator">
                <hr />
            </div>
            <div id="nameField" class="rowItem">
                <label for="name" accesskey="N">
                    <%=intl._t("Name")%>
                </label>
                <span class="text"><%=editBean.getTunnelName(curTunnel)%></span>
            </div>
<%            
      if (("httpserver".equals(tunnelType)) || ("httpbidirserver".equals(tunnelType))) {
          %><div id="websiteField" class="rowItem">
                <label for="spoofedHost" accesskey="W">
                    <%=intl._t("Website name")%>
                </label>
                <span class="text"><%=editBean.getSpoofedHost(curTunnel)%></span>    
            </div>
<%
       }
%>
            <div id="destinationField" class="rowItem">
                <label for="localDestination" accesskey="L">
                    <%=intl._t("Local destination")%>
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
          %><div id="sigField" class="rowItem">
                <label for="signature">
                    <%=intl._t("Authentication for adding host")%>
                </label>
                <textarea rows="1" style="height: 3em;" cols="60" readonly="readonly" id="localDestination" title="Copy and paste this to the registration site" wrap="off" spellcheck="false"><% he.write(out); %></textarea>               
            </div>
        </div>
        <div id="tunnelAdvancedNetworking" class="panel">
            <div class="header">
                <h4><%=intl._t("Advanced authentication strings")%></h4>
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
                <span class="comment"><%=intl._t("This will remove the entry for {0}", name)%></span>
            </div>
            <div class="separator">
                <hr />
            </div>
<%
               String oldname = wrequest.getParameter("oldname");
               String olddestfile = wrequest.getFilename("olddestfile");
               SigningPrivateKey spk2 = null;
               String olddest = null;
               if (olddestfile != null) {
                   InputStream destIn = wrequest.getInputStream("olddestfile");
                   if (destIn.available() > 0) {
                       try {
                           PrivateKeyFile pkf2 = new PrivateKeyFile(destIn);
                           String oldb64 = pkf2.getDestination().toBase64();
                           if (!b64.equals(oldb64)) {
                               // disallow dup
                               olddest = b64;
                               spk2 = pkf2.getSigningPrivKey();
                           }
                       } catch (I2PSessionException ise) {
                           throw new IllegalStateException("Unable to open private key file " + olddestfile, ise);
                       }
                   }
               }
               props.remove(HostTxtEntry.PROP_SIG);
          %><div id="sigField" class="rowItem">
                <label for="signature">
                    <%=intl._t("Authentication for changing name")%>
                </label>
<%
               if (oldname != null && oldname.length() > 0 && !oldname.equals(name)) {
                   props.setProperty(HostTxtEntry.PROP_ACTION, HostTxtEntry.ACTION_CHANGENAME);
                   props.setProperty(HostTxtEntry.PROP_OLDNAME, oldname);
                   he.sign(spk);
                %><textarea rows="1" style="height: 3em;" cols="60" readonly="readonly" id="localDestination" title="Copy and paste this to the registration site" wrap="off" spellcheck="false"><% he.write(out); %></textarea>               
                <span class="comment"><%=intl._t("This will change the name from {0} to {1}, using the same destination", oldname, name)%></span>
<%
               } else {
                %><span class="comment"><%=intl._t("This tunnel must be configured with the new host name.")%></span>
                  <span class="comment"><%=intl._t("Enter old host name below.")%></span>
<%
               }
          %></div>
            <div class="separator">
                <hr />
            </div>
<%
               props.remove(HostTxtEntry.PROP_SIG);
          %><div id="sigField" class="rowItem">
                <label for="signature">
                    <%=intl._t("Authentication for adding alias")%>
                </label>
<%
               if (oldname != null && oldname.length() > 0 && !oldname.equals(name)) {
                   props.setProperty(HostTxtEntry.PROP_ACTION, HostTxtEntry.ACTION_ADDNAME);
                   props.setProperty(HostTxtEntry.PROP_OLDNAME, oldname);
                   he.sign(spk);
                %><textarea rows="1" style="height: 3em;" cols="60" readonly="readonly" id="localDestination" title="Copy and paste this to the registration site" wrap="off" spellcheck="false"><% he.write(out); %></textarea>               
                <span class="comment"><%=intl._t("This will add an alias {0} for {1}, using the same destination", name, oldname)%></span>
<%
               } else {
                %><span class="comment"><%=intl._t("This tunnel must be configured with the new host name.")%></span>
                  <span class="comment"><%=intl._t("Enter old host name below.")%></span>
<%
               }
          %></div>
            <div class="separator">
                <hr />
            </div>
<%
               props.remove(HostTxtEntry.PROP_SIG);
          %><div id="sigField" class="rowItem">
                <label for="signature">
                    <%=intl._t("Authentication for changing destination")%>
                </label>
<%
               if (spk2 != null) {
                   props.setProperty(HostTxtEntry.PROP_ACTION, HostTxtEntry.ACTION_CHANGEDEST);
                   props.setProperty(HostTxtEntry.PROP_OLDDEST, olddest);
                   he.signInner(spk2);
                   he.sign(spk);
                %><textarea rows="1" style="height: 3em;" cols="60" readonly="readonly" id="localDestination" title="Copy and paste this to the registration site" wrap="off" spellcheck="false"><% he.write(out); %></textarea>               
                <span class="comment"><%=intl._t("This will change the destination for {0}", name)%></span>
<%
               } else {
                %><span class="comment"><%=intl._t("This tunnel must be configured with the new destination.")%></span>
                  <span class="comment"><%=intl._t("Enter old destination below.")%></span>
<%
               }
          %></div>
            <div class="separator">
                <hr />
            </div>
<%
               props.remove(HostTxtEntry.PROP_SIG);
               props.remove(HostTxtEntry.PROP_OLDSIG);
          %><div id="sigField" class="rowItem">
                <label for="signature">
                    <%=intl._t("Authentication for adding alternate destination")%>
                </label>
<%
               if (spk2 != null) {
                   props.setProperty(HostTxtEntry.PROP_ACTION, HostTxtEntry.ACTION_ADDDEST);
                   props.setProperty(HostTxtEntry.PROP_OLDDEST, olddest);
                   he.signInner(spk2);
                   he.sign(spk);
                %><textarea rows="1" style="height: 3em;" cols="60" readonly="readonly" id="localDestination" title="Copy and paste this to the registration site" wrap="off" spellcheck="false"><% he.write(out); %></textarea>               
                <span class="comment"><%=intl._t("This will add an alternate destination for {0}", name)%></span>
<%
               } else {
                %><span class="comment"><%=intl._t("This tunnel must be configured with the new destination.")%></span>
                  <span class="comment"><%=intl._t("Enter old destination below.")%></span>
<%
               }
          %></div>
            <div class="separator">
                <hr />
            </div>
<%
               props.remove(HostTxtEntry.PROP_SIG);
               props.remove(HostTxtEntry.PROP_OLDSIG);
          %><div id="sigField" class="rowItem">
                <label for="signature">
                    <%=intl._t("Authentication for adding subdomain")%>
                </label>
<%
               if (oldname != null && oldname.length() > 0 && !oldname.equals(name) && spk2 != null) {
                   props.setProperty(HostTxtEntry.PROP_ACTION, HostTxtEntry.ACTION_ADDSUBDOMAIN);
                   props.setProperty(HostTxtEntry.PROP_OLDNAME, oldname);
                   props.setProperty(HostTxtEntry.PROP_OLDDEST, olddest);
                   he.signInner(spk2);
                   he.sign(spk);
                %><textarea rows="1" style="height: 3em;" cols="60" readonly="readonly" id="localDestination" title="Copy and paste this to the registration site" wrap="off" spellcheck="false"><% he.write(out); %></textarea>               
                <span class="comment"><%=intl._t("This will add a subdomain {0} of {1}, with a different destination", name, oldname)%></span>
<%
               } else {
                %><span class="comment"><%=intl._t("This tunnel must be configured with the new subdomain and destination.")%></span>
                  <span class="comment"><%=intl._t("Enter higher-level domain and destination below.")%></span>
<%
               }
          %></div>

          <div class="footer">
            </div>
<%
          }  // spk != null
       }  // valid b64 and name
    }  // !"new".equals(tunnelType)
    if (!valid && curTunnel >= 0) {
        %><a href="edit?tunnel=<%=curTunnel%>"><%=intl._t("Go back and edit the tunnel")%></a><%
    }
%>
        </div>


<%
    if (valid) {
%>
        <div id="globalOperationsPanel" class="panel">
            <div class="header">
                <h4><%=intl._t("Specify old name and destination")%></h4>
            </div>
  <span class="comment">
    <%=intl._t("This is only required for advanced authentication.")%>
    <%=intl._t("See above for required items.")%>
  </span>
<%
               String oldname = wrequest.getParameter("oldname");
               if (oldname == null) oldname = "";
          %><div id="sigField" class="rowItem">
                <label for="signature">
                    <%=intl._t("Old Host Name")%>
                </label>
                <input type="text" size="30" maxlength="50" name="oldname" id="name" title="Old Host Name" value="<%=oldname%>" class="freetext" />               
            </div> 
            <div id="sigField" class="rowItem">
                <label for="signature">
                    <%=intl._t("Private Key File for old Destination")%>
                </label>
                <input type="file" size="50%" name="olddestfile" id="name" value="" />               
            </div> 
            <div class="footer">
                <div class="toolbox">
                    <input type="hidden" value="true" name="removeConfirm" />
                    <button id="controlCancel" class="control" type="submit" name="action" value="" title="Cancel"><%=intl._t("Cancel")%></button>
                    <button id="controlSave" accesskey="S" class="control" type="submit" name="action" value="authenticate" title="Generate Authentication"><%=intl._t("Generate")%></button>
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
