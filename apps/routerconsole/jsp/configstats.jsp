<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config stats</title>
<%@include file="css.jsp" %>
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
		if(inputs[index].id == category)
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
<%@include file="summary.jsp" %>
<h1>I2P Stats Configuration</h1>
<div class="main" id="main">
 <%@include file="confignav.jsp" %>

 <jsp:useBean class="net.i2p.router.web.ConfigStatsHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:getProperty name="formhandler" property="allMessages" />

 <jsp:useBean class="net.i2p.router.web.ConfigStatsHelper" id="statshelper" scope="request" />
 <jsp:setProperty name="statshelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <div class="configure">
 <form id="statsForm" name="statsForm" action="configstats.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigStatsHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigStatsHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigStatsHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="action" value="foo" />
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigStatsHandler.nonce")%>" />
 <h3>Configure I2P Stat Collection</h3>
 Enable full stats?
 <input type="checkbox" class="optbox" name="isFull" value="true" <% 
 if (statshelper.getIsFull()) { %>checked="true" <% } %>/>
 (change requires restart to take effect)<br />
 Stat file: <input type="text" name="filename" value="<%=statshelper.getFilename()%>" /><br />
 Filter: (<a href="javascript: void(null);" onclick="toggleAll('*')">toggle all</a>)<hr />
 <div class="wideload">
 <table>
 <% while (statshelper.hasMoreStats()) {
      while (statshelper.groupRequired()) { %>
 <tr class="tablefooter"><td align="left" colspan="3">
     <b><%=statshelper.getCurrentGroupName()%></b>
     (<a href="javascript: void(null);" onclick="toggleAll('<%=statshelper.getCurrentGroupName()%>')">toggle all</a>)
     </td></tr><tr class="tablefooter"><td align="center"><b>Log</b></td><td align="center"><b>Graph</b></td><td></td></tr><%
     } // end iterating over required groups for the current stat %>
 <tr><td align="center">
     <a name="<%=statshelper.getCurrentStatName()%>"></a>
     <input id="<%=statshelper.getCurrentGroupName()%>" type="checkbox" class="optbox" name="statList" value="<%=statshelper.getCurrentStatName()%>" <% 
     if (statshelper.getCurrentIsLogged()) { %>checked="true" <% } %>/></td>
     <td align="center">
     <% if (statshelper.getCurrentCanBeGraphed()) { %>
       <input id="<%=statshelper.getCurrentGroupName()%>" type="checkbox" class="optbox" name="graphList" value="<%=statshelper.getCurrentGraphName()%>" <% 
       if (statshelper.getCurrentIsGraphed()) { %>checked="true" <% } %>/><% } %></td>
     <td align="left"><b><%=statshelper.getCurrentStatName()%>:</b><br />
     <%=statshelper.getCurrentStatDescription()%></td></tr><%
    } // end iterating over all stats %>
 <tr><td colspan="3"></td></tr>
 <tr><td align="center"><input type="checkbox" class="optbox" name="explicitFilter" /></td>
     <td colspan="2">Advanced filter: 
     <input type="text" name="explicitFilterValue" value="<%=statshelper.getExplicitFilter()%>" size="40" /></td></tr>
     <tr class="tablefooter"><td colspan="3" align="right"><input type="submit" name="shouldsave" value="Save changes" /><input type="reset" value="Cancel" /></td></tr></form>
 </table>
</div>
</div>
</div>
</body>
</html>
