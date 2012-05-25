<div class="confignav" id="confignav">
<center>
<% if (request.getRequestURI().indexOf("config.jsp") != -1) { 
 %>Network | <% } else { %><a href="config.jsp">Network</a> | <% }
 String userAgent = request.getHeader("User-Agent");
 if (userAgent == null || !userAgent.contains("MSIE")) {
     if (request.getRequestURI().indexOf("configui.jsp") != -1) {
     %>UI | <% } else { %><a href="configui.jsp">UI</a> | <% }
 }
 if (request.getRequestURI().indexOf("configservice.jsp") != -1) {
 %>Service | <% } else { %><a href="configservice.jsp">Service</a> | <% }
 if (request.getRequestURI().indexOf("configupdate.jsp") != -1) {
 %>Update | <% } else { %><a href="configupdate.jsp">Update</a> | <% }
 if (request.getRequestURI().indexOf("configtunnels.jsp") != -1) {
 %>Tunnels | <% } else { %><a href="configtunnels.jsp">Tunnels</a> | <% }
 if (request.getRequestURI().indexOf("configclients.jsp") != -1) {
 %>Clients | <% } else { %><a href="configclients.jsp">Clients</a> | <% }
 if (request.getRequestURI().indexOf("configpeer.jsp") != -1) {
 %>Peers | <% } else { %><a href="configpeer.jsp">Peers</a> | <% }
 if (request.getRequestURI().indexOf("configkeyring.jsp") != -1) {
 %>Keyring | <% } else { %><a href="configkeyring.jsp">Keyring</a> | <% }
 if (request.getRequestURI().indexOf("configlogging.jsp") != -1) {
 %>Logging | <% } else { %><a href="configlogging.jsp">Logging</a> | <% }
 if (request.getRequestURI().indexOf("configstats.jsp") != -1) {
 %>Stats | <% } else { %><a href="configstats.jsp">Stats</a> | <% }
 if (request.getRequestURI().indexOf("configadvanced.jsp") != -1) {
 %>Advanced<% } else { %><a href="configadvanced.jsp">Advanced</a><% } %>
</center>
</div>
