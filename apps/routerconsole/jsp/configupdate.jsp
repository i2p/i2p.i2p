<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2P Router Console - config update</title>
<%@include file="css.jsp" %>
</head><body>

<%@include file="summary.jsp" %>
<h1>I2P Update Configuration</h1>
<div class="main" id="main">
 <%@include file="confignav.jsp" %>
  
 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHandler" id="formhandler" scope="request" />
 <jsp:setProperty name="formhandler" property="*" />
 <jsp:setProperty name="formhandler" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
 <jsp:getProperty name="formhandler" property="allMessages" />
 
 <jsp:useBean class="net.i2p.router.web.ConfigUpdateHelper" id="updatehelper" scope="request" />
 <jsp:setProperty name="updatehelper" property="contextId" value="<%=(String)session.getAttribute("i2p.contextId")%>" />
<div class="messages">
<i><jsp:getProperty name="updatehelper" property="newsStatus" /></i></div> 
<div class="configure">
 <form action="configupdate.jsp" method="POST">
 <% String prev = System.getProperty("net.i2p.router.web.ConfigUpdateHandler.nonce");
    if (prev != null) System.setProperty("net.i2p.router.web.ConfigUpdateHandler.noncePrev", prev);
    System.setProperty("net.i2p.router.web.ConfigUpdateHandler.nonce", new java.util.Random().nextLong()+""); %>
 <input type="hidden" name="nonce" value="<%=System.getProperty("net.i2p.router.web.ConfigUpdateHandler.nonce")%>" />
      <h3>Check for I2P and news updates</h3>
      <div class="wideload"><table border="0" cellspacing="5">
        <tr>
          <td colspan="2"></tr>
        <tr>
          <td class= "mediumtags" align="right"><b>News:</b></td>
          <td> <% if ("true".equals(System.getProperty("net.i2p.router.web.UpdateHandler.updateInProgress", "false"))) { %> <i>Update In Progress</i><br /> <% } else { %> <input type="submit" name="action" value="Check for update now" /> 
            <% } %></tr>
        <tr>
          <td colspan="2"><hr /></td>
        </tr>
        <tr>
          <td class= "mediumtags" align="right"><b>News URL:</b></td>
          <td><input type="text" size="60" name="newsURL" value="<jsp:getProperty name="updatehelper" property="newsURL" />"></td>
        </tr>
        <tr>
          <td class= "mediumtags" align="right"><b>Refresh frequency:</b> 
          <td><jsp:getProperty name="updatehelper" property="refreshFrequencySelectBox" /> 
        <tr>
          <td class= "mediumtags" align="right"><b>Update policy:</b> 
          <td><jsp:getProperty name="updatehelper" property="updatePolicySelectBox" /> 
        <tr>
          <td class= "mediumtags" align="right"><b>Update through the eepProxy?</b> 
          <td><jsp:getProperty name="updatehelper" property="updateThroughProxy" /> 
        <tr>
          <td class= "mediumtags" align="right"><b>eepProxy host:</b> 
          <td><input type="text" size="10" name="proxyHost" value="<jsp:getProperty name="updatehelper" property="proxyHost" />" /> 
        <tr>
          <td class= "mediumtags" align="right"><b>eepProxy port:</b> 
          <td><input type="text" size="4" name="proxyPort" value="<jsp:getProperty name="updatehelper" property="proxyPort" />" /> 
        <tr>
          <td class= "mediumtags" align="right"><b>Update URLs:</b> 
          <td><textarea name="updateURL" wrap="off"><jsp:getProperty name="updatehelper" property="updateURL" /></textarea> 
        <tr>
          <td class= "mediumtags" align="right"><b>Trusted keys:</b> 
          <td><textarea name="trustedKeys" wrap="off"><jsp:getProperty name="updatehelper" property="trustedKeys" /></textarea> 
        <tr>
          <td class= "mediumtags" align="right"><b>Update with unsigned development builds?</b> 
          <td><jsp:getProperty name="updatehelper" property="updateUnsigned" /> 
        <tr>
          <td class= "mediumtags" align="right"><b>Unsigned Build URL:</b></td>
          <td><input type="text" size="60" name="zipURL" value="<jsp:getProperty name="updatehelper" property="zipURL" />"></td>
        <tr>
          <td>
          <td><div class="formaction"> 
              <input type="submit" name="action" value="Save" />
              <input type="reset" value="Cancel" />
            </div>
      </table>
    </div>
 </form>
</div>
</div>
</body>
</html>
