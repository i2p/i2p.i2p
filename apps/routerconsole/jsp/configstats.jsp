<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config stats")%>
<script type="text/javascript">
function init()
{
	checkAll = false;
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
<h1><%=intl._("I2P Stats Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigStatsHandler" id="formhandler" scope="request" />
 <% formhandler.storeMethod(request.getMethod()); %>
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />

 <jsp:useBean class="net.i2p.router.web.ConfigStatsHelper" id="statshelper" scope="request" />
 <jsp:setProperty name="statshelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
 <div class="configure">
 <form id="statsForm" name="statsForm" action="" method="POST">
 <input type="hidden" name="action" value="foo" >
 <input type="hidden" name="nonce" value="<jsp:getProperty name="formhandler" property="newNonce" />" >
 <h3><%=intl._("Configure I2P Stat Collection")%></h3>
 <p><%=intl._("Enable full stats?")%>
 <input type="checkbox" class="optbox" name="isFull" value="true" <%
 if (statshelper.getIsFull()) { %>checked="checked" <% } %> >
 (<%=intl._("change requires restart to take effect")%>)<br>
<%

  // stats.log for devs only and grows without bounds, not recommended
  boolean shouldShowLog = statshelper.shouldShowLog();
  if (shouldShowLog) {

%><%=intl._("Stat file")%>: <input type="text" name="filename" value="<%=statshelper.getFilename()%>" ><br>
Warning - Log with care, stat file grows without limit.<br>
<%

  }  // shouldShowLog

%><%=intl._("Filter")%>: (<a href="javascript:void(null);" onclick="toggleAll('*')"><%=intl._("toggle all")%></a>)<br></p>
 <div class="wideload">
 <table>
 <% while (statshelper.hasMoreStats()) {
      while (statshelper.groupRequired()) { %>
 <tr class="tablefooter">
     <td align="left" colspan="3" id=<%=statshelper.getCurrentGroupName()%>>
     <b><%=statshelper.getCurrentGroupName()%></b>
     (<a href="javascript:void(null);" onclick="toggleAll('<%=statshelper.getCurrentGroupName()%>')"><%=intl._("toggle all")%></a>)
     </td></tr>
 <tr class="tablefooter">
<%

  if (shouldShowLog) {

%>  <td align="center"><b><%=intl._("Log")%></b></td>
<%

  }  // shouldShowLog

%>    <td align="center"><b><%=intl._("Graph")%></b></td>
    <td></td></tr>
        <%
     } // end iterating over required groups for the current stat %>
 <tr>
<%

  if (shouldShowLog) {

%>   <td align="center">
     <a name="<%=statshelper.getCurrentStatName()%>"></a>
     <input type="checkbox" class="optbox <%=statshelper.getCurrentGroupName()%>" name="statList" value="<%=statshelper.getCurrentStatName()%>" <%
     if (statshelper.getCurrentIsLogged()) { %>checked="checked" <% } %> ></td>
<%

  }  // shouldShowLog

%>   <td align="center">
     <% if (statshelper.getCurrentCanBeGraphed()) { %>
       <input type="checkbox" class="optbox <%=statshelper.getCurrentGroupName()%>" name="graphList" value="<%=statshelper.getCurrentGraphName()%>" <%
       if (statshelper.getCurrentIsGraphed()) { %>checked="checked" <% } %> ><% } %></td>
     <td align="left"><b><%=statshelper.getCurrentStatName()%>:</b><br>
     <%=statshelper.getCurrentStatDescription()%></td></tr><%
    } // end iterating over all stats

  if (shouldShowLog) {

%> <tr><td colspan="3"></td></tr>
 <tr><td align="center"><input type="checkbox" class="optbox" name="explicitFilter" ></td>
     <td colspan="2"><%=intl._("Advanced filter")%>:
     <input type="text" name="explicitFilterValue" value="<%=statshelper.getExplicitFilter()%>" size="40" ></td></tr>
<%

  }  // shouldShowLog

%>   <tr class="tablefooter"><td colspan="3" align="right">
<input type="reset" class="cancel" value="<%=intl._("Cancel")%>" >
<input type="submit" name="shouldsave" class="accept" value="<%=intl._("Save changes")%>" >
</td></tr>
</table></div></form></div></div></body></html>
