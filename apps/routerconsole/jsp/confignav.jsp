<h4><% if (request.getRequestURI().indexOf("config.jsp") != -1) { 
 %>Network | <% } else { %><a href="config.jsp">Network</a> | <% }
 if (request.getRequestURI().indexOf("configclients.jsp") != -1) {
 %>Clients | <% } else { %><a href="configclients.jsp">Clients</a> | <% }
 if (request.getRequestURI().indexOf("configlogging.jsp") != -1) {
 %>Logging | <% } else { %><a href="configlogging.jsp">Logging</a> | <% }
 if (request.getRequestURI().indexOf("configadvanced.jsp") != -1) {
 %>Advanced | <% } else { %><a href="configadvanced.jsp">Advanced</a> | <% } %></h4>
