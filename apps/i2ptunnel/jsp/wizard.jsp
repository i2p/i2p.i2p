<%
    // NOTE: Do the header carefully so there is no whitespace before the <?xml... line

    // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
    if (request.getCharacterEncoding() == null)
        request.setCharacterEncoding("UTF-8");

    response.setHeader("X-Frame-Options", "SAMEORIGIN");

%><%@page pageEncoding="UTF-8"
%><%@page contentType="text/html" import="net.i2p.i2ptunnel.web.EditBean"
%><?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<jsp:useBean class="net.i2p.i2ptunnel.web.EditBean" id="editBean" scope="request" />
<jsp:useBean class="net.i2p.i2ptunnel.web.Messages" id="intl" scope="request" />
<% String pageStr = request.getParameter("page");
   /* Get the number of the page we came from */
   int lastPage = 0;
   if (pageStr != null) {
     try {
       lastPage = Integer.parseInt(pageStr);
       if (lastPage > 7 || lastPage < 0) {
         lastPage = 0;
       }
     } catch (NumberFormatException nfe) {
       lastPage = 0;
     }
   }
   /* Determine what page to display now */
   int curPage = 1;
   if ("Previous page".equals(request.getParameter("action"))) {
     curPage = lastPage - 1;
   } else {
     curPage = lastPage + 1;
   }
   if (curPage > 7 || curPage <= 0) {
     curPage = 1;
   }
   /* Fetch and format a couple of regularly-used values */
   boolean tunnelIsClient = Boolean.valueOf(request.getParameter("isClient"));
   String tunnelType = request.getParameter("type");
   /* Special case - don't display page 4 for server tunnels */
   if (curPage == 4 && !tunnelIsClient) {
     if ("Previous page".equals(request.getParameter("action"))) {
       curPage = curPage - 1;
     } else {
       curPage = curPage + 1;
     }
   }
%>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
<head>
    <title><%=intl._("I2P Tunnel Manager - Tunnel Creation Wizard")%></title>

    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=UTF-8" />
    <link href="/themes/console/images/favicon.ico" type="image/x-icon" rel="shortcut icon" />

    <% if (editBean.allowCSS()) {
  %><link rel="icon" href="<%=editBean.getTheme()%>images/favicon.ico">
    <link href="<%=editBean.getTheme()%>default.css" rel="stylesheet" type="text/css" />
    <link href="<%=editBean.getTheme()%>i2ptunnel.css" rel="stylesheet" type="text/css" />
    <% }
  %>
</head>
<body id="tunnelWizardPage">
    <div id="pageHeader">
    </div>

    <form method="post" action="<%=(curPage == 7 ? "list" : "wizard") %>">

        <div id="wizardPanel" class="panel">
            <div class="header">
                <%
                if (curPage == 1) {
                  %><h4><%=intl._("Server or client tunnel?")%></h4><%
                } else if (curPage == 2) {
                  %><h4><%=intl._("Tunnel type")%></h4><%
                } else if (curPage == 3) {
                  %><h4><%=intl._("Tunnel name and description")%></h4><%
                } else if (curPage == 4 && tunnelIsClient) {
                  %><h4><%=intl._("Tunnel destination")%></h4><%
                } else if (curPage == 5) {
                  %><h4><%=intl._("Binding address and port")%></h4><%
                } else if (curPage == 6) {
                  %><h4><%=intl._("Tunnel auto-start")%></h4><%
                } else if (curPage == 7) {
                  %><h4><%=intl._("Wizard completed")%></h4><%
                } %>
                <input type="hidden" name="page" value="<%=curPage%>" />
                <input type="hidden" name="tunnel" value="null" />
                <input type="hidden" name="nonce" value="<%=editBean.getNextNonce()%>" />
            </div>

            <div class="separator">
                <hr />
            </div>

            <% /* Page 1 - Whether to make a client or server tunnel */

            if (curPage == 1) {
            %><p>
                <%=intl._("This wizard will take you through the various options available for creating tunnels in I2P.")%>
            </p>
            <p>
                <%=intl._("The first thing to decide is whether you want to create a server or a client tunnel.")%>
                <%=intl._("If you need to connect to a remote service, such as an IRC server inside I2P or a code repository, then you will require a CLIENT tunnel.")%>
                <%=intl._("On the other hand, if you wish to host a service for others to connect to you'll need to create a SERVER tunnel.")%>
            </p>
            <div id="typeField" class="rowItem">
                <label><%=intl._("Server Tunnel")%></label>
                <input value="false" type="radio" id="baseType" name="isClient" class="tickbox" />
                <label><%=intl._("Client Tunnel")%></label>
                <input value="true" type="radio" id="baseType" name="isClient" class="tickbox" checked="checked" />
            </div><%
            } else {
            %><input type="hidden" name="isClient" value="<%=tunnelIsClient%>" /><%
            } /* curPage 1 */

               /* End page 1 */ %>

            <% /* Page 2 - Tunnel type */

            if (curPage == 2) {
            %><p>
                <%=intl._("There are several types of tunnels to choose from:")%>
            </p>
            <table><%
                if (tunnelIsClient) {
                %>
                <tr><td><%=intl._("Standard")%></td><td>
                    <%=intl._("Basic tunnel for connecting to a single service inside I2P.")%>
                    <%=intl._("Try this if none of the tunnel types below fit your requirements, or you don't know what type of tunnel you need.")%>
                </td></tr>
                <tr><td>HTTP</td><td>
                    <%=intl._("Tunnel that acts as an HTTP proxy for reaching eepsites inside I2P.")%>
                    <%=intl._("Set your browser to use this tunnel as an http proxy, or set your \"http_proxy\" environment variable for command-line applications in GNU/Linux.")%>
                    <%=intl._("Websites outside I2P can also be reached if an HTTP proxy within I2P is known.")%>
                </td></tr>
                <tr><td>IRC</td><td>
                    <%=intl._("Customised client tunnel specific for IRC connections.")%>
                    <%=intl._("With this tunnel type, your IRC client will be able to connect to an IRC network inside I2P.")%>
                    <%=intl._("Each IRC network in I2P that you wish to connect to will require its own tunnel. (See Also, SOCKS IRC)")%>
                </td></tr>
                <tr><td>SOCKS 4/4a/5</td><td>
                    <%=intl._("A tunnel that implements the SOCKS protocol.")%>
                    <%=intl._("This enables both TCP and UDP connections to be made through a SOCKS outproxy within I2P.")%>
                </td></tr>
                <tr><td>SOCKS IRC</td><td>
                    <%=intl._("A client tunnel implementing the SOCKS protocol, which is customised for connecting to IRC networks.")%>
                    <%=intl._("With this tunnel type, IRC networks in I2P can be reached by typing the I2P address into your IRC client, and configuring the IRC client to use this SOCKS tunnel.")%>
                    <%=intl._("This means that only one I2P tunnel is required rather than a separate tunnel per IRC network.")%>
                    <%=intl._("IRC networks outside I2P can also be reached if a SOCKS outproxy within I2P is known, though it depends on whether or not the outproxy has been blocked by the IRC network.")%>
                </td></tr>
                <tr><td>CONNECT</td><td>
                    <%=intl._("A client tunnel that implements the HTTP CONNECT command.")%>
                    <%=intl._("This enables TCP connections to be made through an HTTP outproxy, assuming the proxy supports the CONNECT command.")%>
                </td></tr>
                <tr><td>Streamr</td><td>
                    <%=intl._("A customised client tunnel for Streamr.")%><%
                    //XXX TODO<%=intl._("I have no idea what this is.")%>
                </td></tr><%
                } else {
                %>
                <tr><td><%=intl._("Standard")%></td><td>
                    <%=intl._("A basic server tunnel for hosting a generic service inside I2P.")%>
                    <%=intl._("Try this if none of the tunnel types below fit your requirements, or you don't know what type of tunnel you need.")%>
                </td></tr>
                <tr><td>HTTP</td><td>
                    <%=intl._("A server tunnel that is customised for HTTP connections.")%>
                    <%=intl._("Use this tunnel type if you want to host an eepsite.")%>
                </td></tr>
                <tr><td>HTTP bidir</td><td>
                    <%=intl._("A customised server tunnel that can both serve HTTP data and connect to other server tunnels.")%>
                    <%=intl._("This tunnel type is predominantly used when running a Seedless server.")%>
                </td></tr>
                <tr><td>IRC</td><td>
                    <%=intl._("A customised server tunnel for hosting IRC networks inside I2P.")%>
                    <%=intl._("Usually, a separate tunnel needs to be created for each IRC server that is to be accessible inside I2P.")%>
                </td></tr>
                <tr><td>Streamr</td><td>
                    <%=intl._("A customised server tunnel for Streamr.")%><%
                    //XXX TODO<%=intl._("I have no idea what this is.")%>
                </td></tr><%
                }
                %>
            </table>
            <div id="typeField" class="rowItem">
                <%
                if (tunnelIsClient) {
                %><select name="type">
                    <option value="client"><%=intl._("Standard")%></option>
                    <option value="httpclient">HTTP</option>
                    <option value="ircclient">IRC</option>
                    <option value="sockstunnel">SOCKS 4/4a/5</option>
                    <option value="socksirctunnel">SOCKS IRC</option>
                    <option value="connectclient">CONNECT</option>
                    <option value="streamrclient">Streamr</option>
                </select><%
                } else {
                %><select name="type">
                    <option value="server"><%=intl._("Standard")%></option>
                    <option value="httpserver">HTTP</option>
                    <option value="httpbidirserver">HTTP bidir</option>
                    <option value="ircserver">IRC</option>
                    <option value="streamrserver">Streamr</option>
                </select><%
                } /* tunnelIsClient */ %>
            </div><%
            } else {
            %><input type="hidden" name="type" value="<%=tunnelType%>" /><%
            } /* curPage 2 */

               /* End page 2 */ %>

            <% /* Page 3 - Name and description */

            if (curPage == 3) {
            %><p>
                <%=intl._("Choose a name and description for your tunnel.")%>
                <%=intl._("These can be anything you want - they are just for ease of identifying the tunnel in the routerconsole.")%>
            </p>
            <div id="nameField" class="rowItem">
                <label for="name" accesskey="N">
                    <%=intl._("Name")%>:(<span class="accessKey">N</span>)
                </label>
                <input type="text" size="30" maxlength="50" name="name" id="name" title="Tunnel Name" value="<%=(!"null".equals(request.getParameter("name")) ? request.getParameter("name") : "" ) %>" class="freetext" />
            </div>
            <div id="descriptionField" class="rowItem">
                <label for="description" accesskey="e">
                    <%=intl._("Description")%>:(<span class="accessKey">E</span>)
                </label>
                <input type="text" size="60" maxlength="80" name="description"  id="description" title="Tunnel Description" value="<%=(!"null".equals(request.getParameter("description")) ? request.getParameter("description") : "" ) %>" class="freetext" />
            </div><%
            } else {
            %><input type="hidden" name="name" value="<%=request.getParameter("name")%>" />
            <input type="hidden" name="description" value="<%=request.getParameter("description")%>" /><%
            } /* curPage 3 */

               /* End page 3 */ %>

            <% /* Page 4 - Target destination or proxy list */

            if (tunnelIsClient) {
              if ("httpclient".equals(tunnelType) || "connectclient".equals(tunnelType) || "sockstunnel".equals(tunnelType) || "socksirctunnel".equals(tunnelType)) {
                if (curPage == 4) {
          %><p>
                <%=intl._("If you know of any outproxies for this type of tunnel (either HTTP or SOCKS), fill them in below.")%>
                <%=intl._("Separate multiple proxies with commas.")%>
            </p>
            <div id="destinationField" class="rowItem">
                <label for="proxyList" accesskey="x">
                    <%=intl._("Outproxies")%>(<span class="accessKey">x</span>):
                </label>
                <input type="text" size="30" id="proxyList" name="proxyList" title="List of Outproxy I2P destinations" value="<%=(!"null".equals(request.getParameter("proxyList")) ? request.getParameter("proxyList") : "" ) %>" class="freetext" />
            </div><%
                } else {
            %><input type="hidden" name="proxyList" value="<%=request.getParameter("proxyList")%>" /><%
                } /* curPage 4 */
              } else if ("client".equals(tunnelType) || "ircclient".equals(tunnelType) || "streamrclient".equals(tunnelType)) {
                if (curPage == 4) {
          %><p>
                <%=intl._("Type in the I2P destination of the service that this client tunnel should connect to.")%>
                <%=intl._("This could be the full base 64 destination key, or an I2P URL from your address book.")%>
            </p>
            <div id="destinationField" class="rowItem">
                <label for="targetDestination" accesskey="T">
                    <%=intl._("Tunnel Destination")%>(<span class="accessKey">T</span>):
                </label>
                <input type="text" size="30" id="targetDestination" name="targetDestination" title="Destination of the Tunnel" value="<%=(!"null".equals(request.getParameter("targetDestination")) ? request.getParameter("targetDestination") : "" ) %>" class="freetext" />
                <span class="comment">(<%=intl._("name or destination")%>; <%=intl._("b32 not recommended")%>)</span>
            </div><%
                } else {
            %><input type="hidden" name="targetDestination" value="<%=request.getParameter("targetDestination")%>" /><%
                } /* curPage 4 */
              }
            } /* tunnelIsClient */

               /* End page 4 */ %>

            <% /* Page 5 - Binding ports and addresses*/

            if ((tunnelIsClient && "streamrclient".equals(tunnelType)) || (!tunnelIsClient && !"streamrserver".equals(tunnelType))) {
              if (curPage == 5) {
            %><p>
                <%=intl._("This is the IP that your service is running on, this is usually on the same machine so 127.0.0.1 is autofilled.")%><%
                //XXX TODO<%=intl._("For some reason streamrclient also uses this.")%>
            </p>
            <div id="hostField" class="rowItem">
                <label for="targetHost" accesskey="H">
                    <%=intl._("Host")%>(<span class="accessKey">H</span>):
                </label>
                <input type="text" size="20" id="targetHost" name="targetHost" title="Target Hostname or IP" value="<%=(!"null".equals(request.getParameter("targetHost")) ? request.getParameter("targetHost") : "127.0.0.1" ) %>" class="freetext" />
            </div><%
              } else {
            %><input type="hidden" name="targetHost" value="<%=request.getParameter("targetHost")%>" /><%
              } /* curPage 5 */
            } /* streamrclient or !streamrserver */ %>
            <%
            if (!tunnelIsClient) {
              if (curPage == 5) {
            %><p>
                <%=intl._("This is the port that the service is accepting connections on.")%>
            </p>
            <div id="portField" class="rowItem">
                <label for="targetPort" accesskey="P">
                    <%=intl._("Port")%>(<span class="accessKey">P</span>):
                </label>
                <input type="text" size="6" maxlength="5" id="targetPort" name="targetPort" title="Target Port Number" value="<%=(!"null".equals(request.getParameter("targetPort")) ? request.getParameter("targetPort") : "" ) %>" class="freetext" />
            </div><%
              } else {
            %><input type="hidden" name="targetPort" value="<%=request.getParameter("targetPort")%>" /><%
              } /* curPage 5 */
            } /* !tunnelIsClient */ %>
            <%
            if (tunnelIsClient || "httpbidirserver".equals(tunnelType)) {
              if (curPage == 5) {
            %><p>
                <%=intl._("This is the port that the client tunnel will be accessed from locally.")%>
                <%=intl._("This is also the client port for the HTTPBidir server tunnel.")%>
            </p>
            <div id="portField" class="rowItem">
                <label for="port" accesskey="P">
                    <span class="accessKey">P</span>ort:
                </label>
                <input type="text" size="6" maxlength="5" id="port" name="port" title="Access Port Number" value="<%=(!"null".equals(request.getParameter("port")) ? request.getParameter("port") : "" ) %>" class="freetext" />
            </div><%
              } else {
            %><input type="hidden" name="port" value="<%=request.getParameter("port")%>" /><%
              } /* curPage 5 */
            } /* tunnelIsClient or httpbidirserver */ %>
            <%
            if ((tunnelIsClient && !"streamrclient".equals(tunnelType)) || "httpbidirserver".equals(tunnelType) || "streamrserver".equals(tunnelType)) {
              if (curPage == 5) {
            %><p>
                <%=intl._("How do you want this tunnel to be accessed? By just this machine, your entire subnet, or external internet?")%>
                <%=intl._("You will most likely want to just allow 127.0.0.1")%><%
                //XXX TODO<%=intl._("Note that it is relevant to most Client tunnels, and httpbidirserver and streamrserver tunnels.")%><%
                //XXX TODO<%=intl._("So the wording may need to change slightly for the client vs. server tunnels.")%>
            </p>
            <div id="reachField" class="rowItem">
                <label for="reachableBy" accesskey="r">
                    <%=intl._("Reachable by")%>(<span class="accessKey">R</span>):
                </label>
                <select id="reachableBy" name="reachableBy" title="IP for Client Access" class="selectbox">
              <%
                    String clientInterface = request.getParameter("reachableBy");
                    if ("null".equals(clientInterface)) {
                      clientInterface = "127.0.0.1";
                    }
                    for (String ifc : editBean.interfaceSet()) {
                        out.write("<option value=\"");
                        out.write(ifc);
                        out.write('\"');
                        if (ifc.equals(clientInterface))
                            out.write(" selected=\"selected\"");
                        out.write('>');
                        out.write(ifc);
                        out.write("</option>\n");
                    }
              %>
                </select>
            </div><%
              } else {
            %><input type="hidden" name="reachableBy" value="<%=request.getParameter("reachableBy")%>" /><%
              } /* curPage 5 */
            } /* (tunnelIsClient && !streamrclient) ||  httpbidirserver || streamrserver */

               /* End page 5 */ %>

            <% /* Page 6 - Automatic start */

            if (curPage == 6) {
            %><p>
                <%=intl._("The I2P router can automatically start this tunnel for you when the router is started.")%>
                <%=intl._("This can be useful for frequently-used tunnels (especially server tunnels), but for tunnels that are only used occassionally it would mean that the I2P router is creating and maintaining unnecessary tunnels.")%>
            </p>
            <div id="startupField" class="rowItem">
                <label for="startOnLoad" accesskey="a">
                    <%=intl._("Auto Start")%>(<span class="accessKey">A</span>):
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="startOnLoad" title="Start Tunnel Automatically"<%=("1".equals(request.getParameter("startOnLoad")) ? " checked=\"checked\"" : "")%> class="tickbox" />
                <span class="comment"><%=intl._("(Check the Box for 'YES')")%></span>
            </div><%
            } else {
              if ("1".equals(request.getParameter("startOnLoad"))) {
            %><input type="hidden" name="startOnLoad" value="<%=request.getParameter("startOnLoad")%>" /><%
              }
            } /* curPage 6 */

               /* End page 6 */ %>

            <% /* Page 7 - Wizard complete */

            if (curPage == 7) {
            %><p>
                <%=intl._("The wizard has now collected enough information to create your tunnel.")%>
                <%=intl._("Upon clicking the Save button below, the wizard will set up the tunnel, and take you back to the main I2PTunnel page.")%>
                <%
                if ("1".equals(request.getParameter("startOnLoad"))) {
                %><%=intl._("Because you chose to automatically start the tunnel when the router starts, you don't have to do anything further.")%>
                <%=intl._("The router will start the tunnel once it has been set up.")%><%
                } else {
                %><%=intl._("Because you chose not to automatically start the tunnel, you will have to manually start it.")%>
                <%=intl._("You can do this by clicking the Start button on the main page which corresponds to the new tunnel.")%><%
                } %>
            </p>
            <p>
                <%=intl._("Below is a summary of the options you chose:")%>
            </p>
            <table>
                <tr><td><%=intl._("Server or client tunnel?")%></td><td>
                    <%=(tunnelIsClient ? "Client" : "Server")%>
                </td></tr>
                <tr><td><%=intl._("Tunnel type")%></td><td><%
                if ("client".equals(tunnelType) || "server".equals(tunnelType)) { %>
                    <%=intl._("Standard")%><%
                } else if ("httpclient".equals(tunnelType) || "httpserver".equals(tunnelType)) { %>
                    HTTP<%
                } else if ("httpbidirserver".equals(tunnelType)) { %>
                    HTTP bidir<%
                } else if ("ircclient".equals(tunnelType) || "ircserver".equals(tunnelType)) { %>
                    IRC<%
                } else if ("sockstunnel".equals(tunnelType)) { %>
                    SOCKS 4/4a/5<%
                } else if ("socksirctunnel".equals(tunnelType)) { %>
                    SOCKS IRC<%
                } else if ("connectclient".equals(tunnelType)) { %>
                    CONNECT<%
                } else if ("streamrclient".equals(tunnelType) || "streamrserver".equals(tunnelType)) { %>
                    Streamr<%
                } %>
                </td></tr>
                <tr><td><%=intl._("Tunnel name and description")%></td><td>
                    <%=request.getParameter("name")%><br />
                    <%=request.getParameter("description")%>
                </td></tr><%
                if (tunnelIsClient) { %>
                <tr><td><%=intl._("Tunnel destination")%></td><td><%
                  if ("httpclient".equals(tunnelType) || "connectclient".equals(tunnelType) || "sockstunnel".equals(tunnelType) || "socksirctunnel".equals(tunnelType)) { %>
                    <%=request.getParameter("proxyList")%><%
                  } else if ("client".equals(tunnelType) || "ircclient".equals(tunnelType) || "streamrclient".equals(tunnelType)) { %>
                    <%=request.getParameter("targetDestination")%><%
                  } %>
                </td></tr><%
                } %>
                <tr><td><%=intl._("Binding address and port")%></td><td><%
                if ((tunnelIsClient && "streamrclient".equals(tunnelType)) || (!tunnelIsClient && !"streamrserver".equals(tunnelType))) { %>
                    <%=request.getParameter("targetHost")%><br /><%
                }
                if (!tunnelIsClient) { %>
                    <%=request.getParameter("targetPort")%><br /><%
                }
                if (tunnelIsClient || "httpbidirserver".equals(tunnelType)) { %>
                    <br /><%=request.getParameter("port")%><%
                }
                if ((tunnelIsClient && !"streamrclient".equals(tunnelType)) || "httpbidirserver".equals(tunnelType) || "streamrserver".equals(tunnelType)) { %>
                    <br /><%=request.getParameter("reachableBy")%><%
                } %>
                </td></tr>
                <tr><td><%=intl._("Tunnel auto-start")%></td><td><%
                if ("1".equals(request.getParameter("startOnLoad"))) { %>
                    Yes<%
                } else { %>
                    No<%
                } %>
                </td></tr>
            </table>
            <p>
                <%=intl._("Alongside these basic settings, there are a number of advanced options for tunnel configuration.")%>
                <%=intl._("The wizard will set reasonably sensible default values for these, but you can view and/or edit these by clicking on the tunnel's name in the main I2PTunnel page.")%>
            </p>

            <input type="hidden" name="tunnelDepth" value="2" />
            <input type="hidden" name="tunnelVariance" value="0" />
            <input type="hidden" name="tunnelQuantity" value="2" />
            <input type="hidden" name="tunnelBackupQuantity" value="0" />
            <input type="hidden" name="clientHost" value="internal" />
            <input type="hidden" name="clientport" value="internal" />
            <input type="hidden" name="customOptions" value="" />

            <%
              if (!"streamrclient".equals(tunnelType)) {
            %><input type="hidden" name="profile" value="bulk" />
            <input type="hidden" name="reduceCount" value="1" />
            <input type="hidden" name="reduceTime" value="20" /><%
              } /* !streamrclient */ %>

            <%
              if (tunnelIsClient) { /* Client-only defaults */
                if (!"streamrclient".equals(tunnelType)) {
            %><input type="hidden" name="newDest" value="0" />
            <input type="hidden" name="closeTime" value="30" /><%
                }
                if ("httpclient".equals(tunnelType) || "connectclient".equals(tunnelType) || "sockstunnel".equals(tunnelType) || "socksirctunnel".equals(tunnelType)) {
            %><input type="hidden" name="proxyUsername" value="" />
            <input type="hidden" name="proxyPassword" value="" />
            <input type="hidden" name="outproxyUsername" value="" />
            <input type="hidden" name="outproxyPassword" value="" /><%
                }
                if ("httpclient".equals(tunnelType)) {
            %><input type="hidden" name="jumpList" value="http://i2host.i2p/cgi-bin/i2hostjump?
http://stats.i2p/cgi-bin/jump.cgi?a=
http://i2jump.i2p/" /><%
                } /* httpclient */
              } else { /* Server-only defaults */
            %><input type="hidden" name="privKeyFile" value="<%=editBean.getPrivateKeyFile(-1)%>" />
            <input type="hidden" name="encrypt" value="" />
            <input type="hidden" name="encryptKey" value="" />
            <input type="hidden" name="accessMode" value="0" />
            <input type="hidden" name="accessList" value="" />
            <input type="hidden" name="limitMinute" value="0" />
            <input type="hidden" name="limitHour" value="0" />
            <input type="hidden" name="limitDay" value="0" />
            <input type="hidden" name="totalMinute" value="0" />
            <input type="hidden" name="totalHour" value="0" />
            <input type="hidden" name="totalDay" value="0" />
            <input type="hidden" name="maxStreams" value="0" />
            <input type="hidden" name="cert" value="0" /><%
              } /* tunnelIsClient */
            } /* curPage 7 */

               /* End page 7 */ %>
        </div>

        <div id="globalOperationsPanel" class="panel">
            <div class="header"></div>
            <div class="footer">
                <div class="toolbox">
                    <a class="control" href="list"><%=intl._("Cancel")%></a>
                    <% if (curPage != 1 && curPage != 7) {
                    %><button id="controlPrevious" accesskey="P" class="control" type="submit" name="action" value="Previous page" title="Previous Page"><%=intl._("Previous")%>(<span class="accessKey">P</span>)</button><%
                    } %>
                    <% if (curPage == 7) {
                    %><button id="controlSave" accesskey="S" class="control" type="submit" name="action" value="Save changes" title="Save Tunnel"><%=intl._("Save Tunnel")%>(<span class="accessKey">S</span>)</button><%
                    } else if (curPage == 6) {
                    %><button id="controlFinish" accesskey="F" class="control" type="submit" name="action" value="Next page" title="Finish Wizard"><%=intl._("Finish")%>(<span class="accessKey">F</span>)</button><%
                    } else {
                    %><button id="controlNext" accesskey="N" class="control" type="submit" name="action" value="Next page" title="Next Page"><%=intl._("Next")%>(<span class="accessKey">N</span>)</button><%
                    } %>
                </div>
            </div>
        </div>

    </form>

    <div id="pageFooter">
    </div>
</body>
</html>
