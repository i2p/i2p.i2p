<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config stats")%>
<noscript><style type="text/css">.script {display: none;}</style></noscript>
<%@include file="summaryajax.jsi" %>
<script src="/js/configstats.js?<%=net.i2p.CoreVersion.VERSION%>" type="text/javascript"></script>
</head><body>
<%@include file="summary.jsi" %>
<h1><%=intl._t("Configuration")%></h1>
<div class="main" id="config_stats">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.helpers.ConfigStatsHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <jsp:useBean class="net.i2p.router.web.helpers.ConfigStatsHelper" id="statshelper" scope="request" />
 <jsp:setProperty name="statshelper" property="contextId" value="<%=i2pcontextId%>" />
 <div class="configure">
 <form id="statsForm" name="statsForm" action="" method="POST">
 <input type="hidden" name="action" value="foo" >
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <h3 class="ptitle"><%=intl._t("Configure I2P Stat Collection")%>&nbsp;<a class="script" id="toggle-*" title="<%=intl._t("Toggle full stat collection and all graphing options")%>" href="#">[<%=intl._t("toggle all")%>]</a></h3>
 <p id="enablefullstats"><label><b><%=intl._t("Enable full stats?")%></b>
 <input type="checkbox" class="optbox" id="enableFull" name="isFull" value="true" <%
 if (statshelper.getIsFull()) { %>checked="checked" <% } %> >
 (<%=intl._t("change requires restart to take effect")%>)</label><br>

</p>
 <div class="wideload">
 <table id="configstats">
 <% while (statshelper.hasMoreStats()) {
      while (statshelper.groupRequired()) { %>
 <tr>
     <th align="left" colspan="3" id=<%=statshelper.getCurrentGroupName()%>>
     <b><%=statshelper.getTranslatedGroupName()%></b>
     <a class="script" id="toggle-<%=statshelper.getCurrentGroupName()%>" title="<%=intl._t("Toggle section graphing options")%>" href="#">[<%=intl._t("toggle all")%>]</a>
     </th></tr>
 <tr class="tablefooter">

    <td align="center"><b title="<%=intl._t("Select stats for visualization on /graphs")%>"><%=intl._t("Graph")%></b></td>
    <td></td></tr>
        <%
     } // end iterating over required groups for the current stat %>
 <tr>

     <td align="center"><% if (statshelper.getCurrentCanBeGraphed()) { %><input type="checkbox" class="optbox <%=statshelper.getCurrentGroupName()%>" id="<%=statshelper.getCurrentStatName()%>" name="graphList" value="<%=statshelper.getCurrentGraphName()%>" <%
       if (statshelper.getCurrentIsGraphed()) { %>checked="checked" <% } %> ><% } %></td> <% // no whitespace here so we can use td:empty to remove css pointer from inert labels %>
     <td align="left"><label for="<%=statshelper.getCurrentStatName()%>"><b><%=statshelper.getCurrentStatName()%>:</b>&nbsp;
     <%=statshelper.getCurrentStatDescription()%></label></td></tr><%
    } // end iterating over all stats
%>

<tr class="tablefooter"><td colspan="3" align="right" class="optionsave">
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" name="shouldsave" class="accept" value="<%=intl._t("Save changes")%>" >
</td></tr>
</table></div></form></div></div></body></html>
