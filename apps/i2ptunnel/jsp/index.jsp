<%@page contentType="text/html" %>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2PTunnel status</title>
</head><body>

<jsp:useBean class="net.i2p.i2ptunnel.WebStatusPageHelper" id="helper" scope="request" />
<jsp:setProperty name="helper" property="*" />
<b><jsp:getProperty name="helper" property="actionResults" /></b>
 
<jsp:getProperty name="helper" property="summaryList" />
<hr />
<form action="index.jsp" method="GET">
 <input type="submit" name="action" value="Stop all" />
 <input type="submit" name="action" value="Start all" />
 <input type="submit" name="action" value="Restart all" />
 <input type="submit" name="action" value="Reload config" />
</form>

<form action="edit.jsp">
<b>Add new:</b> 
 <select name="type">
  <option value="httpclient">HTTP proxy</option>
  <option value="client">Client tunnel</option>
  <option value="server">Server tunnel</option>
 </select> <input type="submit" value="GO" />
</form>

</body>
</html>
