<%@page contentType="text/html" %>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2PTunnel edit</title>
</head><body>

<a href="index.jsp">Back</a>

<jsp:useBean class="net.i2p.i2ptunnel.WebEditPageHelper" id="helper" scope="request" />
<jsp:setProperty name="helper" property="*" />
<b><jsp:getProperty name="helper" property="actionResults" /></b>

<jsp:getProperty name="helper" property="editForm" />
</body>
</html>
