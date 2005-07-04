<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config stats</title>
<link rel="stylesheet" href="default.css" type="text/css" />
<script language="JavaScript">
function toggleAll(category) {
  //alert("toggle all for " + category);
  var shouldCheck = false;
  var shouldCheckDetermined = false;
  var elements = document.statsForm.elements;
  for (var i = 0; i < elements.length; i++) {
    //alert("cur element: " + i);
    var curElement = elements.item(i);
    //alert("cur elem: " + curElement);
    var curName = curElement.name;
    //alert("cur name: " + curName);
    if (curName == 'statList') {
      if (shouldCheckDetermined == false) {
        shouldCheckDetermined = true;
        shouldCheck = !curElement.checked;
      }
      if (shouldCheck)
        curElement.checked = true;
      else
        curElement.checked = false;
    }
  }
}
</script>
</head><body>
<%@include file="nav.jsp" %>
<%@include file="summary.jsp" %>

<div class="main" id="main">
 <%@include file="confignav.jsp" %>
 
 <jsp:useBean class="net.i2p.router.web.ConfigStatsHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:setProperty name="formhandler" property="*" />
 <font color="red"><jsp:getProperty name="formhandler" property="errors" /></font>
 <i><jsp:getProperty name="formhandler" property="notices" /></i>

 <jsp:useBean class="net.i2p.router.web.ConfigStatsHelper" id="statshelper" scope="request" />
 <jsp:setProperty name="statshelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 
 <form id="statsForm" name="statsForm" action="configstats.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigStatsHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigStatsHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigStatsHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="action" value="foo" />
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigStatsHandler.nonce")%>" />
 Stat file: <input type="text" name="filename" value="<%=statshelper.getFilename()%>" /><br />
 Filter: (<a href="javascript:toggleAll('*')">toggle all</a>)<br />
 <table>
 <% while (statshelper.hasMoreStats()) {
      while (statshelper.groupRequired()) { %>
 <tr><td valign="top" align="left" colspan="2">
     <b><%=statshelper.getCurrentGroupName()%></b>
     <!--(<a href="javascript:toggleAll('<%=statshelper.getCurrentGroupName()%>')">toggle all</a>)-->
     </td></tr><%
     } // end iterating over required groups for the current stat %>
 <tr><td valign="top" align="left">
     <input type="checkbox" name="statList" value="<%=statshelper.getCurrentStatName()%>" <% 
     if (statshelper.getCurrentIsLogged()) { %>checked="true" <% } %>/></td>
     <td valign="top" align="left"><b><%=statshelper.getCurrentStatName()%>:</b><br />
     <%=statshelper.getCurrentStatDescription()%></td></tr><%
    } // end iterating over all stats %>
 <tr><td colspan="2"><hr /></td></tr>
 <tr><td><input type="checkbox" name="explicitFilter" /></td>
     <td>Advanced filter: 
     <input type="text" name="explicitFilterValue" value="<%=statshelper.getExplicitFilter()%>" size="40" /></td></tr>
 <tr><td colspan="2"><hr /></td></tr>
 <tr><td><input type="submit" name="shouldsave" value="Save changes" /> </td>
     <td><input type="reset" value="Cancel" /></td></tr>
 </form>
 </table>
</div>

</body>
</html>
