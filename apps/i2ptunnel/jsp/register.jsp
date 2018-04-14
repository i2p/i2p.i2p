<%
    // NOTE: Do the header carefully so there is no whitespace before the <?xml... line

    response.setHeader("X-Frame-Options", "SAMEORIGIN");
    response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'");
    response.setHeader("X-XSS-Protection", "1; mode=block");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Accept-Ranges", "none");

%><%@page pageEncoding="UTF-8"
%><%@page contentType="text/html" import="java.io.InputStream,net.i2p.i2ptunnel.web.EditBean,net.i2p.servlet.RequestWrapper,net.i2p.client.I2PSessionException,net.i2p.client.naming.HostTxtEntry,net.i2p.data.PrivateKeyFile,net.i2p.data.SigningPrivateKey,net.i2p.util.OrderedProperties"
%><%@page
%><?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<%
  /* right now using EditBean instead of IndexBean for getSpoofedHost() */
  /* but might want to POST to it anyway ??? */
%>
<jsp:useBean class="net.i2p.i2ptunnel.web.EditBean" id="editBean" scope="request" />
<jsp:useBean class="net.i2p.i2ptunnel.ui.Messages" id="intl" scope="request" />
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
    <link href="/themes/console/images/favicon.ico" type="image/x-icon" rel="shortcut icon" />

    <% if (editBean.allowCSS()) {
  %><link rel="icon" href="<%=editBean.getTheme()%>images/favicon.ico" />
    <link href="<%=editBean.getTheme()%>i2ptunnel.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css" /> 
    <% }
  %>
<style type='text/css'>
input.default { width: 1px; height: 1px; visibility: hidden; }
</style>
</head>
<body id="tunnelRegistration">

<%

  if (editBean.isInitialized()) {

%>
    <form method="post" enctype="multipart/form-data" action="register" accept-charset="UTF-8">
        <div class="panel" id="registration">
<%
    String tunnelTypeName;
    String tunnelType;
    boolean valid = false;
    if (curTunnel >= 0) {
        tunnelTypeName = editBean.getTunnelType(curTunnel);
        tunnelType = editBean.getInternalType(curTunnel);
      %><h2><%=intl._t("Registration Helper")%> (<%=editBean.getTunnelName(curTunnel)%>)</h2><% 
    } else {
        tunnelTypeName = "new";
        tunnelType = "new";
      %><h2>Fail</h2><p>Tunnel not found</p><% 
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
<%
    if (!"new".equals(tunnelType)) {
%>

<table>
    <tr>
        <td class="infohelp">
    <%=intl._t("Please be sure to select, copy, and paste the entire contents of the appropriate authentication data into the form of your favorite registration site")%>
        </td>
    </tr>
    <tr>
        <td>
            <b><%=intl._t("Tunnel name")%>:</b> <%=editBean.getTunnelName(curTunnel)%>
        </td>
    </tr>

<%
      if (("httpserver".equals(tunnelType)) || ("httpbidirserver".equals(tunnelType))) {
          %>
    <tr><td><b><%=intl._t("Website name")%>:</b> <%=editBean.getSpoofedHost(curTunnel)%></td></tr>
<%
       }
%>

<!--
    <tr>
        <th>
            <b><%=intl._t("Local Destination")%></b>
        </th>
    </tr>
    <tr>
        <td>
            <textarea rows="1" style="height: 3em;" cols="60" readonly="readonly" id="localDestination" title="Read Only: Local Destination (if known)" wrap="off" spellcheck="false"><%=editBean.getDestinationBase64(curTunnel)%></textarea>
        </td>
    </tr>
-->

<%
       if (b64 == null || b64.length() < 516) {
           %><tr><td class="infohelp"><%=intl._t("Local destination is not available. Start the tunnel.")%></td></tr><%
       } else if (name == null || name.equals("") || name.contains(" ") || !name.endsWith(".i2p")) {
           if (("httpserver".equals(tunnelType)) || ("httpbidirserver".equals(tunnelType))) {
               %><tr><td class="infohelp"><%=intl._t("To enable registration verification, edit tunnel and set name (or website name) to a valid host name ending in '.i2p'")%></td></tr><%
           } else {
               %><tr><td class="infohelp"><%=intl._t("To enable registration verification, edit tunnel and set name to a valid host name ending in '.i2p'")%></td></tr><%
           }
       } else {
           SigningPrivateKey spk = editBean.getSigningPrivateKey(curTunnel);
           if (spk == null) {
               %><tr><td class="infohelp"><%=intl._t("Destination signing key is not available. Start the tunnel.")%></td></tr><%
           } else {
               valid = true;
               OrderedProperties props = new OrderedProperties();
               HostTxtEntry he = new HostTxtEntry(name, b64, props);
               he.sign(spk);
          %>

    <tr>
        <th>
            <%=intl._t("Authentication for adding host {0}", name)%>
        </th>
    </tr>
    <tr>
        <td>
            <div class="displayText" tabindex="0" title="<%=intl._t("Copy and paste this to the registration site")%>"><% he.write(out); %></div>
        </td>
    </tr>
</table>

<h3><%=intl._t("Advanced authentication strings")%></h3>

<%
               props.remove(HostTxtEntry.PROP_SIG);
               props.setProperty(HostTxtEntry.PROP_ACTION, HostTxtEntry.ACTION_REMOVE);
               he.signRemove(spk);
          %>

<table>
    <tr>
        <th>
            <%=intl._t("Authentication for removing host {0}", name)%>
        </th>
    </tr>
    <tr>
        <td>
            <div class="displayText" tabindex="0" title="<%=intl._t("Copy and paste this to the registration site")%>"><% he.writeRemove(out); %></div>
        </td>
    </tr>

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
                               olddest = oldb64;
                               spk2 = pkf2.getSigningPrivKey();
                           }
                       } catch (I2PSessionException ise) {
                           throw new IllegalStateException("Unable to open private key file " + olddestfile, ise);
                       }
                   }
               }
               props.remove(HostTxtEntry.PROP_SIG);
          %>
    <tr>
        <th>
                    <%=intl._t("Authentication for changing name")%>
        </th>
    </tr>
<%
               if (oldname != null && oldname.length() > 0 && !oldname.equals(name)) {
                   props.setProperty(HostTxtEntry.PROP_ACTION, HostTxtEntry.ACTION_CHANGENAME);
                   props.setProperty(HostTxtEntry.PROP_OLDNAME, oldname);
                   he.sign(spk);
                %>
    <tr>
        <td>
            <div class="displayText" tabindex="0" title="<%=intl._t("Copy and paste this to the registration site")%>"><% he.write(out); %></div>
        </td>
    </tr>
    <tr>
        <td class="infohelp">
            <%=intl._t("This will change the name from {0} to {1}, using the same destination", oldname, name)%>
        </td>
    </tr>

<%
               } else {
                %><tr><td class="infohelp"><%=intl._t("This tunnel must be configured with the new host name.")%>
                  &nbsp;<%=intl._t("Enter old hostname below.")%></td></tr>
<%
               }
          %>

<%
               props.remove(HostTxtEntry.PROP_SIG);
          %>
    <tr>
        <th>
                    <%=intl._t("Authentication for adding alias")%>
        </th>
    </tr>
<%
               if (oldname != null && oldname.length() > 0 && !oldname.equals(name)) {
                   props.setProperty(HostTxtEntry.PROP_ACTION, HostTxtEntry.ACTION_ADDNAME);
                   props.setProperty(HostTxtEntry.PROP_OLDNAME, oldname);
                   he.sign(spk);
                %>
    <tr>
        <td>
            <div class="displayText" tabindex="0" title="<%=intl._t("Copy and paste this to the registration site")%>"><% he.write(out); %></div>
        </td>
    </tr>
    <tr>
        <td class="infohelp">
            <%=intl._t("This will add an alias {0} for {1}, using the same destination", name, oldname)%>
        </td>
    </tr>
<%
               } else {
                %><tr> <td class="infohelp"><%=intl._t("This tunnel must be configured with the new host name.")%>
                  &nbsp;<%=intl._t("Enter old hostname below.")%></td></tr>
<%
               }
          %>

<%
               props.remove(HostTxtEntry.PROP_SIG);
               props.remove(HostTxtEntry.PROP_OLDNAME);
          %>

    <tr>
        <th>
                    <%=intl._t("Authentication for changing destination")%>
        </th>
    </tr>

<%
               if (spk2 != null) {
                   props.setProperty(HostTxtEntry.PROP_ACTION, HostTxtEntry.ACTION_CHANGEDEST);
                   props.setProperty(HostTxtEntry.PROP_OLDDEST, olddest);
                   he.signInner(spk2);
                   he.sign(spk);
                %>

    <tr>
        <td>
            <div class="displayText" tabindex="0" title="<%=intl._t("Copy and paste this to the registration site")%>"><% he.write(out); %></div>
        </td>
    </tr>
    <tr>
        <td class="infohelp">
            <%=intl._t("This will change the destination for {0}", name)%>
        </td>
    </tr>

<%
               } else {
                %><tr><td class="infohelp"><%=intl._t("This tunnel must be configured with the new destination.")%>
                  &nbsp;<%=intl._t("Enter old destination below.")%></td></tr>
<%
               }
          %>

<%
               props.remove(HostTxtEntry.PROP_SIG);
               props.remove(HostTxtEntry.PROP_OLDSIG);
          %>

    <tr>
        <th>
                    <%=intl._t("Authentication for adding alternate destination")%>
        </th>
    </tr>

<%
               if (spk2 != null) {
                   props.setProperty(HostTxtEntry.PROP_ACTION, HostTxtEntry.ACTION_ADDDEST);
                   props.setProperty(HostTxtEntry.PROP_OLDDEST, olddest);
                   he.signInner(spk2);
                   he.sign(spk);
                %>
    <tr>
        <td>
            <div class="displayText" tabindex="0" title="<%=intl._t("Copy and paste this to the registration site")%>"><% he.write(out); %></div>
        </td>
    </tr>
    <tr>
        <td class="infohelp">
            <%=intl._t("This will add an alternate destination for {0}", name)%>
        </td>
    </tr>
<%
               } else {
                   // If set, use the configured alternate destination as the new alias destination,
                   // and the configured primary destination as the inner signer.
                   // This is backwards from all the other ones, so we have to make a second HostTxtEntry just for this.
                   SigningPrivateKey spk3 = null;
                   String altdest = null;
                   String altdestfile = editBean.getAltPrivateKeyFile(curTunnel);
                   if (altdestfile.length() > 0) {
                       try {
                           PrivateKeyFile pkf3 = new PrivateKeyFile(altdestfile);
                           altdest = pkf3.getDestination().toBase64();
                           if (!b64.equals(altdest)) {
                               // disallow dup
                               spk3 = pkf3.getSigningPrivKey();
                           }
                       } catch (Exception e) {}
                   }
                   if (spk3 != null) {
                       OrderedProperties props2 = new OrderedProperties();
                       HostTxtEntry he2 = new HostTxtEntry(name, altdest, props2);
                       props2.setProperty(HostTxtEntry.PROP_ACTION, HostTxtEntry.ACTION_ADDDEST);
                       props2.setProperty(HostTxtEntry.PROP_OLDDEST, b64);
                       he2.signInner(spk);
                       he2.sign(spk3);
                %><tr><td><div class="displayText" tabindex="0" title="<%=intl._t("Copy and paste this to the registration site")%>"><% he2.write(out); %></div></td></tr>
                <tr><td class="infohelp"><%=intl._t("This will add an alternate destination for {0}", name)%></td></tr>
<%
                   } else {
                %><tr><td class="infohelp"><%=intl._t("This tunnel must be configured with the new destination.")%>
                  &nbsp;<%=intl._t("Enter old destination below.")%></td></tr>
<%
                   }  // spk3
               }  // spk2
          %>

<%


               props.remove(HostTxtEntry.PROP_SIG);
               props.remove(HostTxtEntry.PROP_OLDSIG);
          %>

    <tr>
        <th>
                    <%=intl._t("Authentication for adding subdomain")%>
        </th>
    </tr>
<%
               if (oldname != null && oldname.length() > 0 && !oldname.equals(name) && spk2 != null) {
                   props.setProperty(HostTxtEntry.PROP_ACTION, HostTxtEntry.ACTION_ADDSUBDOMAIN);
                   props.setProperty(HostTxtEntry.PROP_OLDNAME, oldname);
                   props.setProperty(HostTxtEntry.PROP_OLDDEST, olddest);
                   he.signInner(spk2);
                   he.sign(spk);
                %>

    <tr>
        <td>
            <div class="displayText" tabindex="0" title="<%=intl._t("Copy and paste this to the registration site")%>"><% he.write(out); %></div>
        </td>
    </tr>
    <tr>
        <td class="infohelp">
            <%=intl._t("This will add a subdomain {0} of {1}, with a different destination", name, oldname)%>
        </td>
    </tr>

<%
               } else {
                %>
    <tr>
        <td class="infohelp">
            <%=intl._t("This tunnel must be configured with the new subdomain and destination.")%>
            &nbsp;<%=intl._t("Enter higher-level domain and destination below.")%>
        </td>
    </tr>

<%
               }
          %>

<%
          }  // spk != null
       }  // valid b64 and name
    }  // !"new".equals(tunnelType)
    if (!valid && curTunnel >= 0) {
        %>
    <tr>
        <td>
            <a href="edit?tunnel=<%=curTunnel%>"><%=intl._t("Go back and edit the tunnel")%></a>
        </td>
    </tr>
        <%
    }
%>

<%
    if (valid) {
%>

    <tr>
        <th>
            <%=intl._t("Specify old name and destination")%>
        </th>
    </tr>
    <tr>
        <td class="infohelp">
            <%=intl._t("This is only required for advanced authentication.")%>
            &nbsp;<%=intl._t("See above for required items.")%>
        </td>
    </tr>
<%
               String oldname = wrequest.getParameter("oldname");
               if (oldname == null) oldname = "";
          %>
    <tr>
        <td>
            <b><%=intl._t("Old hostname")%>:</b>
            <input type="text" size="30" maxlength="50" name="oldname" id="oldName" value="<%=oldname%>" class="freetext" />
        </td>
    </tr>
    <tr>
        <td>
            <b><%=intl._t("Private Key File for old Destination")%>:</b>
            <input type="file" name="olddestfile" id="oldDestFile" value="" />
        </td>
    </tr>
    <tr>
        <td class="buttons">
                    <input type="hidden" value="true" name="removeConfirm" />
                    <a class="control" href="list"><%=intl._t("Cancel")%></a>
                    <button id="controlSave" class="control" type="submit" name="action" value="authenticate"  title="<%=intl._t("Generate Authentication")%>"><%=intl._t("Generate")%></button>
        </td>
    </tr>

<%
     } // valid
%>

</table>
</div>
    </form>
<%

  } else {
     %><div id="notReady"><%=intl._t("Tunnels are not initialized yet, please reload in two minutes.")%></div><%
  }  // isInitialized()

%>
</body>
</html>
