<%
    // NOTE: Do the header carefully so there is no whitespace before the <?xml... line

    // http://www.crazysquirrel.com/computing/general/form-encoding.jspx
    if (request.getCharacterEncoding() == null)
        request.setCharacterEncoding("UTF-8");

    response.setHeader("X-Frame-Options", "SAMEORIGIN");
    response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'");
    response.setHeader("X-XSS-Protection", "1; mode=block");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Accept-Ranges", "none");

%><%@page pageEncoding="UTF-8"
%><%@page contentType="text/html" import="net.i2p.i2ptunnel.web.EditBean"
%><?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<jsp:useBean class="net.i2p.i2ptunnel.web.EditBean" id="editBean" scope="request" />
<jsp:useBean class="net.i2p.i2ptunnel.ui.Messages" id="intl" scope="request" />
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
   tunnelType = net.i2p.data.DataHelper.stripHTML(tunnelType);
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
    <title><%=intl._t("I2P Tunnel Manager - Tunnel Creation Wizard")%></title>

    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <link href="/themes/console/images/favicon.ico" type="image/x-icon" rel="shortcut icon" />

    <% if (editBean.allowCSS()) {
  %><link rel="icon" href="<%=editBean.getTheme()%>images/favicon.ico" />
    <link href="<%=editBean.getTheme()%>i2ptunnel.css?<%=net.i2p.CoreVersion.VERSION%>" rel="stylesheet" type="text/css" />
    <% }
  %>
</head>
<body id="tunnelWizardPage">

    <form method="post" action="<%=(curPage == 7 ? "list" : "wizard") %>">

        <div id="wizardPanel" class="panel">

                <%
                if (curPage == 1) {
                  %><h2><%=intl._t("Server or client tunnel?")%></h2><%
                } else if (curPage == 2) {
                  %><h2><%=intl._t("Tunnel type")%></h2><%
                } else if (curPage == 3) {
                  %><h2><%=intl._t("Tunnel name and description")%></h2><%
                } else if (curPage == 4 && tunnelIsClient) {
                  %><h2><%=intl._t("Tunnel destination")%></h2><%
                } else if (curPage == 5) {
                  %><h2><%=intl._t("Binding address and port")%></h2><%
                } else if (curPage == 6) {
                  %><h2><%=intl._t("Tunnel auto-start")%></h2><%
                } else if (curPage == 7) {
                  %><h2><%=intl._t("Wizard completed")%></h2><%
                } %>
                <input type="hidden" name="page" value="<%=curPage%>" />
                <input type="hidden" name="tunnel" value="null" />
                <input type="hidden" name="nonce" value="<%=net.i2p.i2ptunnel.web.IndexBean.getNextNonce()%>" />


<table>
            <% /* Page 1 - Whether to make a client or server tunnel */

            if (curPage == 1) {
            %>
    <tr>
        <td>
            <p>
                <%=intl._t("This wizard will take you through the various options available for creating tunnels in I2P.")%>
            </p>
            <p>
                <%=intl._t("The first thing to decide is whether you want to create a server or a client tunnel.")%>
                <%=intl._t("If you need to connect to a remote service, such as an IRC server inside I2P or a code repository, then you will require a CLIENT tunnel.")%>
                <%=intl._t("On the other hand, if you wish to host a service for others to connect to you'll need to create a SERVER tunnel.")%>
            </p>
        </td>
    </tr>
    <tr>
        <td class="options">
            <span class="multiOption" id="isServer">
                <label><input value="false" type="radio" id="baseType" name="isClient" class="tickbox" />
                &nbsp;<%=intl._t("Server Tunnel")%></label>
            </span>
            <span class="multiOption" id="isClient">
                <label><input value="true" type="radio" id="baseType" name="isClient" class="tickbox" checked="checked" />
                &nbsp;<%=intl._t("Client Tunnel")%></label>
            </span>
        </td>
    </tr>

            <%
            } else {
            %><input type="hidden" name="isClient" value="<%=tunnelIsClient%>" /><%
            } /* curPage 1 */

               /* End page 1 */ %>

            <% /* Page 2 - Tunnel type */

            if (curPage == 2) {
            %>
    <tr>
        <td>
            <p>
                <%=intl._t("There are several types of tunnels to choose from:")%>
            </p>
        </td>
    </tr>
    <tr>
        <td id="wizardTable">
            <table id="wizardTunnelTypes">
            <%
                if (tunnelIsClient) {
                %>
                <tr><td><%=intl._t("Standard")%></td><td>
                    <%=intl._t("Basic tunnel for connecting to a single service inside I2P.")%>
                    <%=intl._t("Try this if none of the tunnel types below fit your requirements, or you don't know what type of tunnel you need.")%>
                </td></tr>
                <tr><td>HTTP</td><td>
                    <%=intl._t("Tunnel that acts as an HTTP proxy for reaching eepsites inside I2P.")%>
                    <%=intl._t("Set your browser to use this tunnel as an http proxy, or set your \"http_proxy\" environment variable for command-line applications in GNU/Linux.")%>
                    <%=intl._t("Websites outside I2P can also be reached if an HTTP proxy within I2P is known.")%>
                </td></tr>
                <tr><td>IRC</td><td>
                    <%=intl._t("Customised client tunnel specific for IRC connections.")%>
                    <%=intl._t("With this tunnel type, your IRC client will be able to connect to an IRC network inside I2P.")%>
                    <%=intl._t("Each IRC network in I2P that you wish to connect to will require its own tunnel. (See Also, SOCKS IRC)")%>
                </td></tr>
                <tr><td>SOCKS 4/4a/5</td><td>
                    <%=intl._t("A tunnel that implements the SOCKS protocol.")%>
                    <%=intl._t("This enables both TCP and UDP connections to be made through a SOCKS outproxy within I2P.")%>
                </td></tr>
                <tr><td>SOCKS IRC</td><td>
                    <%=intl._t("A client tunnel implementing the SOCKS protocol, which is customised for connecting to IRC networks.")%>
                    <%=intl._t("With this tunnel type, IRC networks in I2P can be reached by typing the I2P address into your IRC client, and configuring the IRC client to use this SOCKS tunnel.")%>
                    <%=intl._t("This means that only one I2P tunnel is required rather than a separate tunnel per IRC network.")%>
                    <%=intl._t("IRC networks outside I2P can also be reached if a SOCKS outproxy within I2P is known, though it depends on whether or not the outproxy has been blocked by the IRC network.")%>
                </td></tr>
                <tr><td>CONNECT</td><td>
                    <%=intl._t("A client tunnel that implements the HTTP CONNECT command.")%>
                    <%=intl._t("This enables TCP connections to be made through an HTTP outproxy, assuming the proxy supports the CONNECT command.")%>
                </td></tr>
                <tr><td>Streamr</td><td>
                    <%=intl._t("A customised client tunnel for Streamr.")%><%
                    //XXX TODO<%=intl._t("I have no idea what this is.")%>
                </td></tr><%
                } else {
                %>
                <tr><td><%=intl._t("Standard")%></td><td>
                    <%=intl._t("A basic server tunnel for hosting a generic service inside I2P.")%>
                    <%=intl._t("Try this if none of the tunnel types below fit your requirements, or you don't know what type of tunnel you need.")%>
                </td></tr>
                <tr><td>HTTP</td><td>
                    <%=intl._t("A server tunnel that is customised for HTTP connections.")%>
                    <%=intl._t("Use this tunnel type if you want to host an eepsite.")%>
                </td></tr>
                <tr><td>HTTP bidir</td><td>
                    <%=intl._t("A customised server tunnel that can both serve HTTP data and connect to other server tunnels.")%>
                    <%=intl._t("This tunnel type is predominantly used when running a Seedless server.")%>
                </td></tr>
                <tr><td>IRC</td><td>
                    <%=intl._t("A customised server tunnel for hosting IRC networks inside I2P.")%>
                    <%=intl._t("Usually, a separate tunnel needs to be created for each IRC server that is to be accessible inside I2P.")%>
                </td></tr>
                <tr><td>Streamr</td><td>
                    <%=intl._t("A customised server tunnel for Streamr.")%><%
                    //XXX TODO<%=intl._t("I have no idea what this is.")%>
                </td></tr><%
                }
                %>

                <tr>
                    <td>
                        <%=intl._t("Select tunnel type")%>:
                    </td>
                    <td>
                <%
                if (tunnelIsClient) {
                %><select name="type">
                    <option value="client"><%=intl._t("Standard")%></option>
                    <option value="httpclient">HTTP/CONNECT</option>
                    <option value="ircclient">IRC</option>
                    <option value="sockstunnel">SOCKS 4/4a/5</option>
                    <option value="socksirctunnel">SOCKS IRC</option>
                    <option value="connectclient">CONNECT</option>
                    <option value="streamrclient">Streamr</option>
                </select><%
                } else {
                %><select name="type">
                    <option value="server"><%=intl._t("Standard")%></option>
                    <option value="httpserver">HTTP</option>
                    <option value="httpbidirserver">HTTP bidir</option>
                    <option value="ircserver">IRC</option>
                    <option value="streamrserver">Streamr</option>
                </select><%
                } /* tunnelIsClient */ %>
                    </td>
                </tr>
            </table>
        </td>
    </tr>
            <%
            } else {
            %><input type="hidden" name="type" value="<%=tunnelType%>" /><%
            } /* curPage 2 */

               /* End page 2 */ %>

            <% /* Page 3 - Name and description */

            if (curPage == 3) {
            %>
    <tr>
        <td>
            <p>
                <%=intl._t("Choose a name and description for your tunnel.")%>
                <%=intl._t("These can be anything you want - they are just for ease of identifying the tunnel in the routerconsole.")%>
            </p>
        </td>
    </tr>
    <tr>
        <td>
            <span class="tag"><%=intl._t("Name")%>:</span>
                <input type="text" size="30" maxlength="50" name="name" id="name" placeholder="New Tunnel" title="<%=intl._t("Name of tunnel to be displayed on Tunnel Manager home page and the router console sidebar")%>" value="<%=(!"null".equals(request.getParameter("name")) ? net.i2p.data.DataHelper.stripHTML(request.getParameter("name")) : "" ) %>" class="freetext" />
        </td>
    </tr>
    <tr>
        <td>
            <span class="tag"><%=intl._t("Description")%>:</span>
                <input type="text" size="60" maxlength="80" name="nofilter_description"  id="description" title="<%=intl._t("Description of tunnel to be displayed on Tunnel Manager home page")%>" value="<%=(!"null".equals(request.getParameter("nofilter_description")) ? net.i2p.data.DataHelper.stripHTML(request.getParameter("nofilter_description")) : "" ) %>" class="freetext" />
        </td>
    </tr>
            <%
            } else {
            %><input type="hidden" name="name" value="<%=net.i2p.data.DataHelper.stripHTML(request.getParameter("name"))%>" />
            <input type="hidden" name="nofilter_description" value="<%=net.i2p.data.DataHelper.stripHTML(request.getParameter("nofilter_description"))%>" /><%
            } /* curPage 3 */

               /* End page 3 */ %>

            <% /* Page 4 - Target destination or proxy list */

            if (tunnelIsClient) {
              if ("httpclient".equals(tunnelType) || "connectclient".equals(tunnelType) || "sockstunnel".equals(tunnelType) || "socksirctunnel".equals(tunnelType)) {
                if (curPage == 4) {
          %>
    <tr>
        <td>
            <p>
                <%=intl._t("If you know of any outproxies for this type of tunnel (either HTTP or SOCKS), fill them in below.")%>
                <%=intl._t("Separate multiple proxies with commas.")%>
            </p>
        </td>
    </tr>
    <tr>
        <td>
            <span class="tag"><%=intl._t("Outproxies")%>:</span>
                <input type="text" size="30" id="proxyList" name="proxyList" title="<%=intl._t("List of I2P outproxy destinations, separated with commas (e.g. proxy1.i2p,proxy2.i2p)")%>" value="<%=(!"null".equals(request.getParameter("proxyList")) ? net.i2p.data.DataHelper.stripHTML(request.getParameter("proxyList")) : "" ) %>" class="freetext" />
        </td>
    </tr>
            <%
                } else {
            %><input type="hidden" name="proxyList" value="<%=net.i2p.data.DataHelper.stripHTML(request.getParameter("proxyList"))%>" /><%
                } /* curPage 4 */
              } else if ("client".equals(tunnelType) || "ircclient".equals(tunnelType) || "streamrclient".equals(tunnelType)) {
                if (curPage == 4) {
            %>
    <tr>
        <td>
            <p>
                <%=intl._t("Type in the I2P destination of the service that this client tunnel should connect to.")%>
                <%=intl._t("This could be the full base 64 destination key, or an I2P URL from your address book.")%>
            </p>
        </td>
    </tr>
    <tr>
        <td>
            <span class="tag"><%=intl._t("Tunnel Destination")%>:</span>
                <input type="text" size="30" id="targetDestination" name="targetDestination" title="<%=intl._t("Enter a b64 or .i2p address here")%>" value="<%=(!"null".equals(request.getParameter("targetDestination")) ? net.i2p.data.DataHelper.stripHTML(request.getParameter("targetDestination")) : "" ) %>" class="freetext" />
            &nbsp;(<%=intl._t("name, name:port, or destination")%>
                     <% if ("streamrclient".equals(tunnelType)) { /* deferred resolution unimplemented in streamr client */ %>
                         - <%=intl._t("b32 not recommended")%>
                     <% } %> )
        </td>
    </tr>
            <%
                } else {
            %><input type="hidden" name="targetDestination" value="<%=net.i2p.data.DataHelper.stripHTML(request.getParameter("targetDestination"))%>" /><%
                } /* curPage 4 */
              }
            } /* tunnelIsClient */

               /* End page 4 */ %>

            <% /* Page 5 - Binding ports and addresses*/

            if ((tunnelIsClient && "streamrclient".equals(tunnelType)) || (!tunnelIsClient && !"streamrserver".equals(tunnelType))) {
              if (curPage == 5) {
            %>
    <tr>
        <td>
            <p>
                <%=intl._t("This is the IP that your service is running on, this is usually on the same machine so 127.0.0.1 is autofilled.")%>
                <% //TODO For some reason streamrclient also uses this. %>
            </p>
        </td>
    </tr>
    <tr>
        <td>
            <span class="tag"><%=intl._t("Host")%>:</span>
                <input type="text" size="20" id="targetHost" name="targetHost" title="<%=intl._t("Hostname or IP address of the target server")%>" placeholder="127.0.0.1" value="<%=(!"null".equals(request.getParameter("targetHost")) ? net.i2p.data.DataHelper.stripHTML(request.getParameter("targetHost")) : "127.0.0.1" ) %>" class="freetext" />
        </td>
    </tr>
            <%
              } else {
            %><input type="hidden" name="targetHost" value="<%=net.i2p.data.DataHelper.stripHTML(request.getParameter("targetHost"))%>" /><%
              } /* curPage 5 */
            } /* streamrclient or !streamrserver */ %>
            <%
            if (!tunnelIsClient) {
              if (curPage == 5) {
            %>
    <tr>
        <td>
            <p>
                <%=intl._t("This is the port that the service is accepting connections on.")%>
            </p>
        </td>
    </tr>
    <tr>
        <td>
            <span class="tag"><%=intl._t("Port")%>:</span>
                <input type="text" size="6" maxlength="5" id="targetPort" name="targetPort" title="<%=intl._t("Specify the port the server is running on")%>" value="<%=(!"null".equals(request.getParameter("targetPort")) ? net.i2p.data.DataHelper.stripHTML(request.getParameter("targetPort")) : "" ) %>" class="freetext" />
        </td>
    </tr>
            <%
              } else {
            %><input type="hidden" name="targetPort" value="<%=net.i2p.data.DataHelper.stripHTML(request.getParameter("targetPort"))%>" /><%
              } /* curPage 5 */
            } /* !tunnelIsClient */ %>
            <%
            if (tunnelIsClient || "httpbidirserver".equals(tunnelType)) {
              if (curPage == 5) {
            %>
    <tr>
        <td>
            <p>
                <%=intl._t("This is the port that the client tunnel will be accessed from locally.")%>
                <%=intl._t("This is also the client port for the HTTPBidir server tunnel.")%>
            </p>
        </td>
    </tr>
    <tr>
        <td>
            <span class="tag"><%=intl._t("Port")%>:</span>
                <input type="text" size="6" maxlength="5" id="port" name="port" title="<%=intl._t("Specify the local port this service should be accessible from")%>" value="<%=(!"null".equals(request.getParameter("port")) ? net.i2p.data.DataHelper.stripHTML(request.getParameter("port")) : "" ) %>" class="freetext" />
        </td>
    </tr>
            <%
              } else {
            %><input type="hidden" name="port" value="<%=net.i2p.data.DataHelper.stripHTML(request.getParameter("port"))%>" /><%
              } /* curPage 5 */
            } /* tunnelIsClient or httpbidirserver */ %>
            <%
            if ((tunnelIsClient && !"streamrclient".equals(tunnelType)) || "httpbidirserver".equals(tunnelType) || "streamrserver".equals(tunnelType)) {
              if (curPage == 5) {
            %>
    <tr>
        <td>
            <p>
                <%=intl._t("How do you want this tunnel to be accessed? By just this machine, your entire subnet, or external internet?")%>
                <%=intl._t("You will most likely want to just allow 127.0.0.1")%><%
                //TODO Note that it is relevant to most Client tunnels, and httpbidirserver and streamrserver tunnels.
                //TODO So the wording may need to change slightly for the client vs. server tunnels. %>
            </p>
        </td>
    </tr>
    <tr>
        <td>
            <span class="tag"><%=intl._t("Reachable by")%>:</span>
                <select id="reachableBy" name="reachableBy" title="<%=intl._t("Listening interface (IP address) for client access (normally 127.0.0.1)")%>" class="selectbox">
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
        </td>
    </tr>
               <%
              } else {
            %><input type="hidden" name="reachableBy" value="<%=net.i2p.data.DataHelper.stripHTML(request.getParameter("reachableBy"))%>" /><%
              } /* curPage 5 */
            } /* (tunnelIsClient && !streamrclient) ||  httpbidirserver || streamrserver */

               /* End page 5 */ %>

            <% /* Page 6 - Automatic start */

            if (curPage == 6) {
            %>
    <tr>
        <td>
            <p>
                <%=intl._t("The I2P router can automatically start this tunnel for you when the router is started.")%>
                <%=intl._t("This can be useful for frequently-used tunnels (especially server tunnels), but for tunnels that are only used occassionally it would mean that the I2P router is creating and maintaining unnecessary tunnels.")%>
            </p>
        </td>
    </tr>
    <tr>
        <td class="options">
                <label title="<%=intl._t("Enable this option to ensure this service is available when the router starts")%>"><input value="1" type="checkbox" id="startOnLoad" name="startOnLoad" <%=("1".equals(request.getParameter("startOnLoad")) ? " checked=\"checked\"" : "")%> class="tickbox" />
            &nbsp;<%=intl._t("Automatically start tunnel when router starts")%></label>
        </td>
    </tr>
            <%
            } else {
              if ("1".equals(request.getParameter("startOnLoad"))) {
            %><input type="hidden" name="startOnLoad" value="<%=net.i2p.data.DataHelper.stripHTML(request.getParameter("startOnLoad"))%>" /><%
              }
            } /* curPage 6 */

               /* End page 6 */ %>

            <% /* Page 7 - Wizard complete */

            if (curPage == 7) {
            %>
    <tr>
        <td>
            <p>
                <%=intl._t("The wizard has now collected enough information to create your tunnel.")%>
                <%=intl._t("Upon clicking the Save button below, the wizard will set up the tunnel, and take you back to the main I2PTunnel page.")%>
                <%
                if ("1".equals(request.getParameter("startOnLoad"))) {
                %><%=intl._t("Because you chose to automatically start the tunnel when the router starts, you don't have to do anything further.")%>
                <%=intl._t("The router will start the tunnel once it has been set up.")%><%
                } else {
                %><%=intl._t("Because you chose not to automatically start the tunnel, you will have to manually start it.")%>
                <%=intl._t("You can do this by clicking the Start button on the main page which corresponds to the new tunnel.")%><%
                } %>
            </p>
            <p>
                <%=intl._t("Below is a summary of the options you chose:")%>
            </p>
        </td>
    </tr>
    <tr>
        <td id="wizardTable">
            <table id="wizardSummary">
                <tr><td><%=intl._t("Server or client tunnel?")%></td><td>
                    <%=(tunnelIsClient ? "Client" : "Server")%>
                </td></tr>
                <tr><td><%=intl._t("Tunnel type")%></td><td><%
                if ("client".equals(tunnelType) || "server".equals(tunnelType)) { %>
                    <%=intl._t("Standard")%><%
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
                <tr><td><%=intl._t("Tunnel name")%></td>
                <td><%=net.i2p.data.DataHelper.stripHTML(request.getParameter("name"))%></td></tr>    
                <tr><td><%=intl._t("Tunnel description")%></td>
                <td><%=net.i2p.data.DataHelper.stripHTML(request.getParameter("nofilter_description"))%></td></tr>
                <%
                if (tunnelIsClient) { %>
                <tr><td><%=intl._t("Tunnel destination")%></td><td><%
                  if ("httpclient".equals(tunnelType) || "connectclient".equals(tunnelType) || "sockstunnel".equals(tunnelType) || "socksirctunnel".equals(tunnelType)) { %>
                    <%=net.i2p.data.DataHelper.stripHTML(request.getParameter("proxyList"))%><%
                  } else if ("client".equals(tunnelType) || "ircclient".equals(tunnelType) || "streamrclient".equals(tunnelType)) { %>
                    <%=net.i2p.data.DataHelper.stripHTML(request.getParameter("targetDestination"))%><%
                  } %>
                </td></tr><%
                } %>
                <%
                if ((tunnelIsClient && "streamrclient".equals(tunnelType)) || (!tunnelIsClient && !"streamrserver".equals(tunnelType))) { %>
                    <tr><td><%=intl._t("Binding address")%></td><td>
                    <%=net.i2p.data.DataHelper.stripHTML(request.getParameter("targetHost"))%></td></tr><%
                }
                if (!tunnelIsClient) { %>
                    <tr><td><%=intl._t("Tunnel port")%></td><td><%=net.i2p.data.DataHelper.stripHTML(request.getParameter("targetPort"))%></td></tr><%
                }
                if (tunnelIsClient || "httpbidirserver".equals(tunnelType)) { %>
                    <tr><td><%=intl._t("Port")%></td><td><%=net.i2p.data.DataHelper.stripHTML(request.getParameter("port"))%></td></tr><%
                }
                if ((tunnelIsClient && !"streamrclient".equals(tunnelType)) || "httpbidirserver".equals(tunnelType) || "streamrserver".equals(tunnelType)) { %>
                    <tr><td><%=intl._t("Reachable by")%></td><td><%=net.i2p.data.DataHelper.stripHTML(request.getParameter("reachableBy"))%></td></tr><%
                } %>
                <tr><td><%=intl._t("Tunnel auto-start")%></td><td><%
                if ("1".equals(request.getParameter("startOnLoad"))) { %>
                    Yes<%
                } else { %>
                    No<%
                } %>
                </td></tr>
            </table>
        </td>
    </tr>
    <tr>
        <td class="infohelp">
            <p>
                <%=intl._t("Alongside these basic settings, there are a number of advanced options for tunnel configuration.")%>
                <%=intl._t("The wizard will set reasonably sensible default values for these, but you can view and/or edit these by clicking on the tunnel's name in the main I2PTunnel page.")%>
            </p>

            <input type="hidden" name="tunnelDepth" value="3" />
            <input type="hidden" name="tunnelVariance" value="0" />
            <input type="hidden" name="tunnelQuantity" value="2" />
            <input type="hidden" name="tunnelBackupQuantity" value="0" />
            <input type="hidden" name="clientHost" value="internal" />
            <input type="hidden" name="clientport" value="internal" />
            <input type="hidden" name="nofilter_customOptions" value="" />

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
            <input type="hidden" name="nofilter_proxyPassword" value="" />
            <input type="hidden" name="outproxyUsername" value="" />
            <input type="hidden" name="nofilter_outproxyPassword" value="" /><%
                }
                if ("httpclient".equals(tunnelType)) {
            %><input type="hidden" name="jumpList" value="http://i2host.i2p/cgi-bin/i2hostjump?
http://stats.i2p/cgi-bin/jump.cgi?a=" /><%
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
            <input type="hidden" name="cert" value="0" />
        </td>
    </tr>
            <%
              } /* tunnelIsClient */
            } /* curPage 7 */

               /* End page 7 */ %>
    <tr>
        <td class="buttons">
                    <a class="control" title="<%=intl._t("Cancel the wizard and return to Tunnel Manager home page")%>" href="list"><%=intl._t("Cancel")%></a>
                    <% if (curPage != 1 && curPage != 7) {
                    %><button id="controlPrevious" class="control" type="submit" name="action" value="Previous page" title="<%=intl._t("Return to previous page")%>"><%=intl._t("Previous")%></button><%
                    } %>
                    <% if (curPage == 7) {
                    %><button id="controlSave" class="control" type="submit" name="action" value="Save changes" title="<%=intl._t("Save tunnel configuration")%>"><%=intl._t("Save Tunnel")%></button><%
                    } else if (curPage == 6) {
                    %><button id="controlFinish" class="control" type="submit" name="action" value="Next page" title="<%=intl._t("Finish Wizard and review tunnel settings")%>"><%=intl._t("Finish")%></button><%
                    } else {
                    %><button id="controlNext" class="control" type="submit" name="action" value="Next page" title="<%=intl._t("Advance to next page")%>"><%=intl._t("Next")%></button><%
                    } %>
        </td>
    </tr>
</table>
        </div>

    </form>

</body>
</html>
