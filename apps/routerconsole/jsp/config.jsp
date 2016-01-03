<%@page contentType="text/html" %>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("configure bandwidth")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.ConfigNetHelper" id="nethelper" scope="request" />
<jsp:setProperty name="nethelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<h1><%=intl._t("I2P Bandwidth Configuration")%></h1>
<div class="main" id="main">
 <%@include file="confignav.jsi" %>

 <jsp:useBean class="net.i2p.router.web.ConfigNetHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>
<div class="configure">
 <form action="" method="POST">
 <input type="hidden" name="nonce" value="<%=pageNonce%>">
 <input type="hidden" name="action" value="blah" >
 <input type="hidden" name="ratesOnly" value="1" >
 <h3><%=intl._t("Bandwidth limiter")%></h3><p>
 <img src="/themes/console/images/itoopie_xsm.png" alt="">
 <b><%=intl._t("I2P will work best if you configure your rates to match the speed of your internet connection.")%></b>
 </p>
   <div class="wideload"><table><tr><td><input style="text-align: right; width: 5em;" name="inboundrate" type="text" size="5" maxlength="5" value="<jsp:getProperty name="nethelper" property="inboundRate" />" >
          <%=intl._t("KBps In")%>
        </td><td>(<jsp:getProperty name="nethelper" property="inboundRateBits" />)</td>
<% /********
<!-- let's keep this simple...
 bursting up to
    <input name="inboundburstrate" type="text" size="5" value="<jsp:getProperty name="nethelper" property="inboundBurstRate" />" /> KBps for
    <jsp:getProperty name="nethelper" property="inboundBurstFactorBox" /><br>
-->
*********/ %>
    </tr><tr>
        <td><input style="text-align: right; width: 5em;" name="outboundrate" type="text" size="5" maxlength="5" value="<jsp:getProperty name="nethelper" property="outboundRate" />" >
         <%=intl._t("KBps Out")%>
        </td><td>(<jsp:getProperty name="nethelper" property="outboundRateBits" />)</td>
<% /********
<!-- let's keep this simple...
 bursting up to
    <input name="outboundburstrate" type="text" size="2" value="<jsp:getProperty name="nethelper" property="outboundBurstRate" />" /> KBps for
  <jsp:getProperty name="nethelper" property="outboundBurstFactorBox" /><br>
 <i>KBps = kilobytes per second = 1024 bytes per second = 8192 bits per second.<br>
    A negative rate sets the default.</i><br>
-->
*********/ %>
    </tr><tr>
        <td><jsp:getProperty name="nethelper" property="sharePercentageBox" /> <%=intl._t("Share")%></td>
        <td>(<jsp:getProperty name="nethelper" property="shareRateBits" />)
</td></tr></table></div>
<p><% int share = nethelper.getShareBandwidth();
    if (share < 12) {
        out.print("<b>");
        out.print(intl._t("NOTE"));
        out.print("</b>: ");
        out.print(intl._t("You have configured I2P to share only {0} KBps.", share));
        out.print("\n");

        out.print(intl._t("I2P requires at least 12KBps to enable sharing. "));
        out.print(intl._t("Please enable sharing (participating in tunnels) by configuring more bandwidth. "));
        out.print(intl._t("It improves your anonymity by creating cover traffic, and helps the network."));
    } else {
        out.print(intl._t("You have configured I2P to share {0} KBps.", share));
        out.print("\n");

        out.print(intl._t("The higher the share bandwidth the more you improve your anonymity and help the network."));
    }
 %></p>
<p><a href="confignet"><%=intl._t("Advanced network configuration page")%></a></p><hr>
<div class="formaction">
<input type="reset" class="cancel" value="<%=intl._t("Cancel")%>" >
<input type="submit" class="accept" name="save" value="<%=intl._t("Save changes")%>" >
</div>
</form>
</div></div></body></html>
