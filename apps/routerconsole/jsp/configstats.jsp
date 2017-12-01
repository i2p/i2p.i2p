<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config stats")%>
<noscript><style type="text/css">.script {display: none;}</style></noscript>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
<script type="text/javascript">
function init()
{
	checkAll = false;
	initAjax();
}
function toggleAll(category)
{
	var inputs = document.getElementsByTagName("input");
	for(index = 0; index < inputs.length; index++)
	{
		var classes = inputs[index].className.split(' ');
		for (var idx = 0; idx < classes.length; idx++)
		{
			if(classes[idx] == category)
			{
				if(inputs[index].checked == 0)
				{
					inputs[index].checked = 1;
				}
				else if(inputs[index].checked == 1)
				{
					inputs[index].checked = 0;
				}
			}
		}
		if(category == '*')
		{
			if (checkAll == false)
			{
				inputs[index].checked = 1;
			}
			else if (checkAll == true)
			{
				inputs[index].checked = 0;
			}
		}
	}
	if(category == '*')
	{
		if (checkAll == false)
		{
			checkAll = true;
		}
		else if (checkAll == true)
		{
			checkAll = false;
		}
	}
}
</script>
</head><body onLoad="init();">
<%@include file="summary.jsi" %>
<h1><%=intl._t("I2P Stats Configuration")%></h1>
<div class="main" id="config_stats">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.helpers.ConfigStatsHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
 <jsp:useBean class="net.i2p.router.web.helpers.ConfigStatsHelper" id="statshelper" scope="request" />
 <jsp:setProperty name="statshelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
 <div class="configure">
 <form id="statsForm" name="statsForm" action="" method="POST">
 <input type="hidden" name="action" value="foo" >
 <input type="hidden" name="nonce" value="<%=pageNonce%>" >
 <h3 class="ptitle"><%=intl._t("Configure I2P Stat Collection")%>&nbsp;<a class="script" title="<%=intl._t("Toggle full stat collection and all graphing options")%>" href="javascript:void(null);" onclick="toggleAll('*')">[<%=intl._t("toggle all")%>]</a></h3>
 <p id="enablefullstats"><label><b><%=intl._t("Enable full stats?")%></b>
 <input type="checkbox" class="optbox" name="isFull" value="true" <%
 if (statshelper.getIsFull()) { %>checked="checked" <% } %> >
 (<%=intl._t("change requires restart to take effect")%>)</label><br>
<%

  // stats.log for devs only and grows without bounds, not recommended
  boolean shouldShowLog = statshelper.shouldShowLog();
  if (shouldShowLog) {

%><%=intl._t("Stat file")%>: <input type="text" name="filename" value="<%=statshelper.getFilename()%>" >
Warning - Log with care, stat file grows without limit.<br>
<%

  }  // shouldShowLog

%></p>
 <div class="wideload">
 <table id="configstats">
 <% while (statshelper.hasMoreStats()) {
      while (statshelper.groupRequired()) { %>
 <tr>
     <th align="left" colspan="3" id=<%=statshelper.getCurrentGroupName()%>>
     <b><%=intl._t(statshelper.getCurrentGroupName())%></b>
     <a class="script" title="<%=intl._t("Toggle section graphing options")%>" href="javascript:void(null);" onclick="toggleAll('<%=statshelper.getCurrentGroupName()%>')">[<%=intl._t("toggle all")%>]</a>
     </th></tr>
 <tr class="tablefooter">
<%

  if (shouldShowLog) {

%>  <td align="center"><b><%=intl._t("Log")%></b></td>
<%

  }  // shouldShowLog

%>    <td align="center"><b title="<%=intl._t("Select stats for visualization on /graphs")%>"><%=intl._t("Graph")%></b></td>
    <td></td></tr>
        <%
     } // end iterating over required groups for the current stat %>
 <tr>
<%

  if (shouldShowLog) {

%>   <td align="center"><a name="<%=statshelper.getCurrentStatName()%>"></a><input type="checkbox" class="optbox <%=statshelper.getCurrentGroupName()%>" name="statList" value="<%=statshelper.getCurrentStatName()%>" <%
     if (statshelper.getCurrentIsLogged()) { %>checked="checked" <% } %> ></td>
<%

  }  // shouldShowLog

%>   <td align="center"><% if (statshelper.getCurrentCanBeGraphed()) { %><input type="checkbox" class="optbox <%=statshelper.getCurrentGroupName()%>" id="<%=statshelper.getCurrentStatName()%>" name="graphList" value="<%=statshelper.getCurrentGraphName()%>" <%
       if (statshelper.getCurrentIsGraphed()) { %>checked="checked" <% } %> ><% } %></td> <% // no whitespace here so we can use td:empty to remove css pointer from inert labels %>
     <td align="left"><label for="<%=statshelper.getCurrentStatName()%>"><b><%=statshelper.getCurrentStatName()%>:</b>&nbsp;
     <%=statshelper.getCurrentStatDescription()%></label></td></tr><%
    } // end iterating over all stats

  if (shouldShowLog) {

%> <tr><td colspan="3"></td></tr>
 <tr><td align="center"><label><input type="checkbox" class="optbox" name="explicitFilter" ></td>
     <td colspan="2"><%=intl._t("Advanced filter")%>:</label>
<input type="text" name="explicitFilterValue" value="<%=statshelper.getExplicitFilter()%>" size="40" ></td></tr>
<%

  }  // shouldShowLog

%>   <tr class="tablefooter"><td colspan="3" align="right" class="optionsave">
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" name="shouldsave" class="accept" value="<%=intl._t("Save changes")%>" >
</td></tr>
</table></div></form></div></div></body></html>
