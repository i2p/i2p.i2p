<%
    // NOTE: Do the header carefully so there is no whitespace before the <?xml... line

%><%@page pageEncoding="UTF-8"
%><%@page contentType="text/html" import="net.i2p.i2ptunnel.web.WizardBean"
%><?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<jsp:useBean class="net.i2p.i2ptunnel.web.WizardBean" id="wizardBean" scope="request" />
<jsp:useBean class="net.i2p.i2ptunnel.web.Messages" id="intl" scope="request" />
<% String pageStr = request.getParameter("page");
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
   int curPage = 1;
   if ("Previous page".equals(request.getParameter("action"))) {
     curPage = lastPage - 1;
   } else {
     curPage = lastPage + 1;
   }
   if (curPage > 7 || curPage <= 0) {
     curPage = 1;
   }
   boolean tunnelIsClient = Boolean.valueOf(request.getParameter("isClient"));
   String tunnelType = request.getParameter("type");
%>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
<head>
    <title><%=intl._("I2P Tunnel Manager - Tunnel Creation Wizard")%></title>
    
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=UTF-8" />
    <link href="/themes/console/images/favicon.ico" type="image/x-icon" rel="shortcut icon" />
    
    <% if (wizardBean.allowCSS()) {
  %><link href="<%=wizardBean.getTheme()%>default.css" rel="stylesheet" type="text/css" /> 
    <link href="<%=wizardBean.getTheme()%>i2ptunnel.css" rel="stylesheet" type="text/css" />
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
                <input type="hidden" name="nonce" value="<%=wizardBean.getNextNonce()%>" />
            </div>

            <div class="separator">
                <hr />
            </div>

            <% /* Page 1 - Whether to make a client or server tunnel */

            if (curPage == 1) {
            %><div id="typeField" class="rowItem">
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
            %><div id="typeField" class="rowItem">
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
            %><div id="nameField" class="rowItem">
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
          %><div id="destinationField" class="rowItem">
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
          %><div id="destinationField" class="rowItem">
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
            %><div id="hostField" class="rowItem">
                <label for="targetHost" accesskey="H">
                    <%=intl._("Host")%>(<span class="accessKey">H</span>):
                </label>
                <input type="text" size="20" id="targetHost" name="targetHost" title="Target Hostname or IP" value="<%=(!"null".equals(request.getParameter("targetHost")) ? request.getParameter("targetHost") : "" ) %>" class="freetext" />
            </div><%
              } else {
            %><input type="hidden" name="targetHost" value="<%=request.getParameter("targetHost")%>" /><%
              } /* curPage 5 */
            } /* streamrclient or !streamrserver */ %>
            <%
            if (!tunnelIsClient) {
              if (curPage == 5) {
            %><div id="portField" class="rowItem">
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
            %><div id="portField" class="rowItem">
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
            %><div id="reachField" class="rowItem">
                <label for="reachableBy" accesskey="r">
                    <%=intl._("Reachable by")%>(<span class="accessKey">R</span>):
                </label>
                <select id="reachableBy" name="reachableBy" title="IP for Client Access" class="selectbox">
              <%
                    for (String ifc : wizardBean.interfaceSet()) {
                        out.write("<option value=\"");
                        out.write(ifc);
                        out.write('\"');
                        if (ifc.equals("127.0.0.1"))
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
            %><div id="startupField" class="rowItem">
                <label for="startOnLoad" accesskey="a">
                    <%=intl._("Auto Start")%>(<span class="accessKey">A</span>):
                </label>
                <input value="1" type="checkbox" id="startOnLoad" name="startOnLoad" title="Start Tunnel Automatically" class="tickbox" />
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
            %>

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
            %><input type="hidden" name="encrypt" value="" />
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
                <div class=toolbox">
                    <a class="control" href="list"><%=intl._("Cancel")%></a>
                    <% if (curPage != 1) {
                    %><button id="controlPrevious" accesskey="P" class="control" type="submit" name="action" value="Previous page" title="Previous Page"><%=intl._("Previous")%>(<span class="accessKey">P</span>)</button><%
                    } %>
                    <% if (curPage == 7) {
                    %><button id="controlFinish" accesskey="F" class="control" type="submit" name="action" value="Save changes" title="Finish Wizard"><%=intl._("Finish")%>(<span class="accessKey">F</span>)</button><%
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
