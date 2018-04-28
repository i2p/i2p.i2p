<%
    // NOTE: Do the header carefully so there is no whitespace before the <?xml... line

    response.setHeader("X-Frame-Options", "SAMEORIGIN");
    response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'");
    response.setHeader("X-XSS-Protection", "1; mode=block");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Accept-Ranges", "none");

%><%@page pageEncoding="UTF-8"
%><%@page contentType="text/html" import="java.io.File,net.i2p.crypto.KeyStoreUtil,net.i2p.data.DataHelper,net.i2p.jetty.JettyXmlConfigurationParser"
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
   String tun = request.getParameter("tunnel");
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
    <title><%=intl._t("Hidden Services Manager")%> - <%=intl._t("SSL Helper")%></title>
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
<body id="tunnelSSL">
<%

  net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
  if (!ctx.isRouterContext()) {
      %>Unsupported in app context<%
  } else if (editBean.isInitialized()) {

%>
<div class="panel" id="ssl">
<%
    String tunnelTypeName;
    String tunnelType;
    boolean valid = false;
    if (curTunnel >= 0) {
        tunnelTypeName = editBean.getTunnelType(curTunnel);
        tunnelType = editBean.getInternalType(curTunnel);
      %><h2><%=intl._t("SSL Wizard")%> (<%=editBean.getTunnelName(curTunnel)%>)</h2><% 
    } else {
        tunnelTypeName = "new";
        tunnelType = "new";
      %><h2>Fail</h2><p>Tunnel not found</p><% 
    }

    // set a bunch of variables for the current configuration
    String b64 = editBean.getDestinationBase64(curTunnel);
    String b32 = editBean.getDestHashBase32(curTunnel);
    // todo
    String altb32 = editBean.getAltDestHashBase32(curTunnel);
    String name = editBean.getSpoofedHost(curTunnel);
    String targetHost = editBean.getTargetHost(curTunnel);
    if (targetHost != null && targetHost.indexOf(':') >= 0)
        targetHost = '[' + targetHost + ']';
    String targetPort = editBean.getTargetPort(curTunnel);
    int intPort = 0;
    try {
        intPort = Integer.parseInt(targetPort);
    } catch (NumberFormatException nfe) {}
    String clientTgt = targetHost + ':' + targetPort;
    boolean sslToTarget = editBean.isSSLEnabled(curTunnel);
    String targetLink = clientTgt;
    boolean shouldLinkify = true;
    if (shouldLinkify) {
        String url = "://" + clientTgt + "\">" + clientTgt + "</a>";
        if (sslToTarget)
            targetLink = "<a href=\"https" + url;
        else
            targetLink = "<a href=\"http" + url;
    }
    net.i2p.util.PortMapper pm = ctx.portMapper();
    int jettyPort = pm.getPort(net.i2p.util.PortMapper.SVC_EEPSITE);
    int jettySSLPort = pm.getPort(net.i2p.util.PortMapper.SVC_HTTPS_EEPSITE);

    if (name == null || name.equals(""))
        name = editBean.getTunnelName(curTunnel);
    if (!"new".equals(tunnelType)) {
        // POST handling
        String action = request.getParameter("action");
        if (action != null) {
            String nonce = request.getParameter("nonce");
            String newpw = request.getParameter("nofilter_keyPassword");
            String appNum = request.getParameter("clientAppNumber");
            String ksPath = request.getParameter("nofilter_ksPath");
            String jettySSLConfigPath = request.getParameter("nofilter_jettySSLFile");
            if (newpw != null) {
                newpw = newpw.trim();
                if (newpw.length() <= 0)
                    newpw = null;
            }
            if (!editBean.haveNonce(nonce)) {
                out.println(intl._t("Invalid form submission, probably because you used the 'back' or 'reload' button on your browser. Please resubmit.")
                            + ' ' +
                            intl._t("If the problem persists, verify that you have cookies enabled in your browser."));
            } else if (!action.equals("Generate")) {
                out.println("Unknown form action");
            } else if (newpw == null) {
                out.println("Password required");
            } else if (appNum == null || ksPath == null || jettySSLConfigPath == null) {
                out.println("Missing parameters");
            } else if (b32.length() <= 0) {
                out.println("No destination set - start tunnel first");
            } else if (name == null || !name.endsWith(".i2p")) {
                out.println("No hostname set - go back and configure");
            } else {
                boolean ok = true;

                // generate selfsigned cert
                java.util.Set<String> altNames = new java.util.HashSet<String>(4);
                altNames.add(b32);
                altNames.add(name);
                if (!name.startsWith("www."))
                    altNames.add("www." + name);
                if (altb32 != null && altb32.length() > 0)
                    altNames.add(altb32);
                File ks = new File(ksPath);
                ok = net.i2p.crypto.KeyStoreUtil.createKeys(ks, "eepsite", name, altNames, b32, newpw);
                if (ok) {
                     out.println("Created selfsigned cert");
                }

                // rewrite jetty-ssl.xml
                if (ok) {
                    String obf = org.eclipse.jetty.util.security.Password.obfuscate(newpw);
                    File f = new File(jettySSLConfigPath);
                    try {
                        org.eclipse.jetty.xml.XmlParser.Node root;
                        root = net.i2p.jetty.JettyXmlConfigurationParser.parse(f);
                        //JettyXmlConfigurationParser.setValue(root, "KeyStorePassword", ...);
                        JettyXmlConfigurationParser.setValue(root, "KeyManagerPassword", obf);
                        JettyXmlConfigurationParser.setValue(root, "TrustStorePassword", obf);
                        File fb = new File(jettySSLConfigPath + ".bkup");
                        if (fb.exists())
                            fb = new File(jettySSLConfigPath + '-' + System.currentTimeMillis() + ".bkup");
                        ok = net.i2p.util.FileUtil.copy(f, fb, false, true);
                        if (ok) {
                            java.io.Writer w = null;
                            try {
                                w = new java.io.OutputStreamWriter(new net.i2p.util.SecureFileOutputStream(f), "UTF-8");
                                w.write(root.toString());
                            } catch (java.io.IOException ioe) {
                                ioe.printStackTrace();
                                ok = false;
                            } finally {
                                if (w != null) try { w.close(); } catch (java.io.IOException ioe2) {}
                            }
                        }
                    } catch (org.xml.sax.SAXException saxe) {
                        saxe.printStackTrace();
                        out.println(DataHelper.escapeHTML(saxe.getMessage()));
                        ok = false;
                    }
                }

                // rewrite clients.config
                boolean isSSLEnabled = Boolean.parseBoolean(request.getParameter("isSSLEnabled"));
                if (ok && !isSSLEnabled) {
                }

                // stop and restart jetty

                // stop tunnel
                if (ok) {

                }

                // rewrite i2ptunnel.config
                if (ok) {

                }

                // restart tunnel
                if (ok) {

                }

                if (ok) {
                    out.println(intl. _t("Configuration changes saved"));
                }
            }
        }

%>

<form method="post" action="ssl" accept-charset="UTF-8">
<input type="hidden" name="tunnel" value="<%=curTunnel%>" />
<input type="hidden" name="nonce" value="<%=net.i2p.i2ptunnel.web.IndexBean.getNextNonce()%>" />
<input type="hidden" name="type" value="<%=tunnelType%>" />
<input type="submit" class="default" name="action" value="Save changes" />
<table>
<tr><td colspan="4" class="infohelp"><%=intl._t("Experts only!")%> Beta!</td></tr>
<tr><td colspan="4"><b><%=intl._t("Tunnel name")%>:</b> <%=editBean.getTunnelName(curTunnel)%></td></tr>
<%
      if (("httpserver".equals(tunnelType)) || ("httpbidirserver".equals(tunnelType))) {
%>
<tr><td colspan="4"><b><%=intl._t("Website name")%>:</b> <%=editBean.getSpoofedHost(curTunnel)%></td></tr>
<%
       }
       if (b64 == null || b64.length() < 516) {
           %><tr><td class="infohelp"><%=intl._t("Local destination is not available. Start the tunnel.")%></td></tr><%
       } else if (name == null || name.equals("") || name.contains(" ") || !name.endsWith(".i2p")) {
           if (("httpserver".equals(tunnelType)) || ("httpbidirserver".equals(tunnelType))) {
               %><tr><td class="infohelp"><%=intl._t("To enable registration verification, edit tunnel and set name (or website name) to a valid host name ending in '.i2p'")%></td></tr><%
           } else {
               %><tr><td class="infohelp"><%=intl._t("To enable registration verification, edit tunnel and set name to a valid host name ending in '.i2p'")%></td></tr><%
           }
       } else {
           valid = true;
%>
<tr><td colspan="4"><b><%=intl._t("Base 32")%>:</b> <%=b32%></td></tr>
<%
    if (altb32 != null && altb32.length() > 0) {
%>
        <tr><td><%=intl._t("Alt Base 32")%>: <%=altb32%></td></tr>
<%
    }  // altb32
%>
<tr><th colspan="4"><%=intl._t("Incoming I2P Port Routing")%></th></tr>
<tr><th><%=intl._t("Route From I2P Port")%></th><th><%=intl._t("With Virtual Host")%></th><th><%=intl._t("Via SSL?")%></th><th><%=intl._t("To Server Host:Port")%></th></tr>
<tr><td><%=intl._t("Default")%></td><td><%=name%></td><td><%=sslToTarget%></td><td><%=targetLink%></td></tr>
<%
    // build tables for vhost and targets
    java.util.TreeSet<Integer> ports = new java.util.TreeSet<Integer>();
    java.util.Map<Integer, String> tgts = new java.util.HashMap<Integer, String>(4);
    java.util.Map<Integer, String> spoofs = new java.util.HashMap<Integer, String>(4);
    String custom = editBean.getCustomOptions(curTunnel);
    String[] opts = DataHelper.split(custom, "[, ]");
    for (int i = 0; i < opts.length; i++) {
        String opt = opts[i];
        boolean isTgt = false;
        if (opt.startsWith("targetForPort.")) {
            opt = opt.substring("targetForPort.".length());
            isTgt = true;
        } else if (opt.startsWith("spoofedHost.")) {
            opt = opt.substring("spoofedHost.".length());
        } else {
             continue;
        }
        int eq = opt.indexOf('=');
        if (eq <= 0)
             continue;
        int port;
        try {
            port = Integer.parseInt(opt.substring(0, eq));
        } catch (NumberFormatException nfe) {
             continue;
        }
        String tgt = opt.substring(eq + 1);
        Integer iport = Integer.valueOf(port);
        ports.add(iport);
        if (isTgt)
            tgts.put(iport, tgt);
        else
            spoofs.put(iport, tgt);
    }
    // output vhost and targets
    for (Integer port : ports) {
        boolean ssl = sslToTarget;
        String spoof = spoofs.get(port);
        if (spoof == null)
            spoof = name;
        // can't spoof for HTTPS
        if (port.intValue() == 443) {
            spoof = b32;
            if (altb32 != null && altb32.length() > 0)
                spoof += "<br />" + altb32;
            ssl = true;
        }
        String tgt = tgts.get(port);
        if (tgt != null) {
            if (shouldLinkify) {
                String url = "://" + tgt + "\">" + tgt + "</a>";
                if (ssl)
                    tgt = "<a href=\"https" + url;
                else
                    tgt = "<a href=\"http" + url;
            }
        } else {
            tgt = targetLink;
        }
%>
<tr><td><%=port%></td><td><%=spoof%></td><td><%=ssl%></td><td><%=tgt%></td></tr>
<%
    }
%>
<tr><th colspan="4"><%=intl._t("Add Port Routing")%></th></tr>
<tr><td>
    <input type="text" size="6" maxlength="5" id="i2pPort" name="i2pPort" title="<%=intl._t("Specify the port the server is running on")%>" value="" class="freetext port" placeholder="required" />
</td><td>
    <input type="text" size="20" id="websiteName" name="spoofedHost" title="<%=intl._t("Website Hostname e.g. mysite.i2p")%>" value="<%=name%>" class="freetext" />
</td><td>
    <input value="1" type="checkbox" name="useSSL" class="tickbox" />
</td><td>
    <input type="text" size="20" name="targetHost" title="<%=intl._t("Hostname or IP address of the target server")%>" value="<%=targetHost%>" class="freetext host" /> :
    <input type="text" size="6" maxlength="5" id="targetPort" name="targetPort" title="<%=intl._t("Specify the port the server is running on")%>" value="" class="freetext port" placeholder="required" />
</td></tr>
<tr><th colspan="4"><%=intl._t("Jetty Clients")%></th></tr>
<tr><th><%=intl._t("Client")%></th><th><%=intl._t("Configuration Files")%></th><th><%=intl._t("Enabled?")%></th><th><%=intl._t("SSL Enabled?")%></th><th><%=intl._t("KS Exists?")%></th><th><%=intl._t("KS Dflt PW?")%></th><th><%=intl._t("Privkey Dflt PW?")%></th></tr>
<%
    // Now try to find the Jetty server in clients.config
    File configDir = ctx.getConfigDir();
    File clientsConfig = new File(configDir, "clients.config");
    java.util.Properties clientProps = new java.util.Properties();
    try {
        DataHelper.loadProps(clientProps, clientsConfig);
        for (int i = 0; i < 100; i++) {
            String prop = "clientApp." + i + ".main";
            String cls = clientProps.getProperty(prop);
            if (cls == null)
                break;
            if (!cls.equals("net.i2p.jetty.JettyStart"))
                continue;
            prop = "clientApp." + i + ".args";
            String clArgs = clientProps.getProperty(prop);
            if (clArgs == null)
                continue;
            prop = "clientApp." + i + ".name";
            String clName = clientProps.getProperty(prop);
            if (clName == null)
                clName = intl._t("I2P webserver (eepsite)");
            prop = "clientApp." + i + ".startOnLoad";
            String clStart = clientProps.getProperty(prop);
            boolean start = true;
            if (clStart != null)
                start = Boolean.parseBoolean(clStart);
            // sample args
            // clientApp.3.args="/home/xxx/.i2p/eepsite/jetty.xml" "/home/xxx/.i2p/eepsite/jetty-ssl.xml" "/home/xxx/.i2p/eepsite/jetty-rewrite.xml"
            boolean ssl = clArgs.contains("jetty-ssl.xml");

            boolean jettySSLFileInArgs = false;
            boolean jettySSLFileExists = false;
            boolean jettySSLFilePWSet = false;
            File jettyFile = null, jettySSLFile = null;
            String ksPW = null, kmPW = null, tsPW = null;
            String ksPath = null, tsPath = null;
            String host = null, port = null;
            String sslHost = null, sslPort = null;
            String error = "";
            java.util.List<String> argList = net.i2p.i2ptunnel.web.SSLHelper.parseArgs(clArgs);
            for (String arg : argList) {
                if (arg.endsWith("jetty.xml")) {
                    jettyFile = new File(arg);
                    if (!jettyFile.isAbsolute())
                        jettyFile = new File(ctx.getConfigDir(), arg);
                    jettySSLFileInArgs = true;
                } else if (arg.endsWith("jetty-ssl.xml")) {
                    jettySSLFile = new File(arg);
                    if (!jettySSLFile.isAbsolute())
                        jettySSLFile = new File(ctx.getConfigDir(), arg);
                    jettySSLFileInArgs = true;
                }
            }  // for arg in argList
            if (jettySSLFile == null && !argList.isEmpty()) {
                String arg = argList.get(0);
                File f = new File(arg);
                if (!f.isAbsolute())
                    f = new File(ctx.getConfigDir(), arg);
                File p = f.getParentFile();
                if (p != null)
                    jettySSLFile = new File(p, "jetty-ssl.xml");
            }
            boolean ksDflt = false;
            boolean kmDflt = false;
            boolean tsDflt = false;
            boolean ksExists = false;
            if (jettyFile != null && jettyFile.exists()) {
                try {
                    org.eclipse.jetty.xml.XmlParser.Node root;
                    root = net.i2p.jetty.JettyXmlConfigurationParser.parse(jettyFile);
                    host = JettyXmlConfigurationParser.getValue(root, "host");
                    port = JettyXmlConfigurationParser.getValue(root, "port");
                } catch (org.xml.sax.SAXException saxe) {
                    saxe.printStackTrace();
                    error = DataHelper.escapeHTML(saxe.getMessage());
                }
            }
            if (jettySSLFile.exists()) {
                try {
                    org.eclipse.jetty.xml.XmlParser.Node root;
                    root = net.i2p.jetty.JettyXmlConfigurationParser.parse(jettySSLFile);
                    ksPW = JettyXmlConfigurationParser.getValue(root, "KeyStorePassword");
                    kmPW = JettyXmlConfigurationParser.getValue(root, "KeyManagerPassword");
                    tsPW = JettyXmlConfigurationParser.getValue(root, "TrustStorePassword");
                    ksPath = JettyXmlConfigurationParser.getValue(root, "KeyStorePath");
                    tsPath = JettyXmlConfigurationParser.getValue(root, "TrustStorePath");
                    sslHost = JettyXmlConfigurationParser.getValue(root, "host");
                    sslPort = JettyXmlConfigurationParser.getValue(root, "port");
                    // we can't proceed unless they are there
                    // tsPW may be null
                    File ksFile = null;
                    boolean tsIsKs = true;
                    boolean ksArgs = ksPW != null && kmPW != null && ksPath != null;
                    /** 2015+ installs */
                    final String DEFAULT_KSPW_1 = KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD;
                    final String DEFAULT_KMPW_1 = "myKeyPassword";
                    /** earlier */
                    final String DEFAULT_KSPW_2 = "OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4";
                    final String DEFAULT_KMPW_2 = "OBF:1u2u1wml1z7s1z7a1wnl1u2g";
                    if (ksArgs) {
                        jettySSLFileExists = true;
                        ksDflt = ksPW.equals(DEFAULT_KSPW_1) || ksPW.equals(DEFAULT_KSPW_2);
                        kmDflt = kmPW.equals(DEFAULT_KMPW_1) || kmPW.equals(DEFAULT_KMPW_2);
                        ksFile = new File(ksPath);
                        if (!ksFile.isAbsolute())
                            ksFile = new File(ctx.getConfigDir(), ksPath);
                        ksExists = ksFile.exists();
                        tsIsKs = tsPath == null || ksPath.equals(tsPath);
                    }
                    if (tsPW != null) {
                        tsDflt = tsPW.equals(DEFAULT_KSPW_1) || tsPW.equals(DEFAULT_KSPW_2);
                    }
                } catch (org.xml.sax.SAXException saxe) {
                    saxe.printStackTrace();
                    error = DataHelper.escapeHTML(saxe.getMessage());
                }
            }
            boolean canConfigure = jettySSLFileExists;
            boolean isEnabled = canConfigure && jettySSLFileInArgs;
            boolean isPWDefault = kmDflt || !ksExists;

            // now start the output for this client

%>
<tr><td><%=DataHelper.escapeHTML(clName)%></td><td>
<%
            for (String arg : argList) {
                %><%=DataHelper.escapeHTML(arg)%><br /><%
            }
%>
    </td><td><%=start%></td><td><%=ssl%></td><td><%=ksExists%> <%=error%></td><td><%=ksDflt%></td><td><%=kmDflt%></td></tr>
<%
            if (!canConfigure) {
%>
<tr><td colspan="7">Cannot configure, no Jetty SSL configuration template exists</td></tr>
<%
            } else {
                if (isEnabled) {
%>
<tr><td colspan="7">Jetty SSL is enabled</td></tr>
<%
                } else {
%>
<tr><td colspan="7">Jetty SSL is not enabled</td></tr>
<%
                }  // isEnabled
                if (isPWDefault) {
%>
<tr><td colspan="7">Jetty SSL cert passwords are the default</td></tr>
<%
                } else {
%>
<tr><td colspan="7">Jetty SSL cert passwords are not the default</td></tr>
<%
                }  // isPWDefault
%>
<tr><td colspan="7"><b><%=intl._t("Password")%>:</b>
<input type="hidden" name="clientAppNumber" value="<%=i%>" />
<input type="hidden" name="isSSLEnabled" value="<%=isEnabled%>" />
<input type="hidden" name="nofilter_ksPath" value="<%=ksPath%>" />
<input type="hidden" name="nofilter_jettySSLFile" value="<%=jettySSLFile%>" />
<input type="password" name="nofilter_keyPassword" title="<%=intl._t("Set password required to access this service")%>" value="" class="freetext password" />
</td></tr>
<tr><td class="buttons" colspan="7">
<button id="controlSave" class="control" type="submit" name="action" value="Generate"><%=intl._t("Generate certificate")%></button>
</td></tr>
<%
            }  // canConfigure
        }  // for client
    } catch (java.io.IOException ioe) { ioe.printStackTrace(); }
%>
<tr><td colspan="4">
  <div class="displayText" tabindex="0" title="<%=intl._t("yyy")%>"></div>
</td></tr>
</table>
</form>
<%
       }  // valid b64 and name
    }  // !"new".equals(tunnelType)
    if (!valid && curTunnel >= 0) {
%>
<table>
  <tr><td><a href="edit?tunnel=<%=curTunnel%>"><%=intl._t("Go back and edit the tunnel")%></a></td></tr>
</table>
<%
    }  // !valid
%>
</div>
<%
  } else {
%>
<div id="notReady"><%=intl._t("Tunnels are not initialized yet, please reload in two minutes.")%></div>
<%
  }  // isInitialized()
%>
</body>
</html>
