<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("config router family")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">

<%@include file="summary.jsi" %>

<jsp:useBean class="net.i2p.router.web.ConfigFamilyHelper" id="familyHelper" scope="request" />
<jsp:setProperty name="familyHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<h1><%=intl._t("I2P Router Family Configuration")%></h1>
<div class="main" id="main">
<%@include file="confignav.jsi" %>

<jsp:useBean class="net.i2p.router.web.ConfigFamilyHandler" id="formhandler" scope="request" />
<%@include file="formhandler.jsi" %>

<p><%=intl._t("Routers in the same family share a family key.")%>
<%=intl._t("To join an existing family, import the private key you exported from a router in the family.")%>
<%=intl._t("To start a new family, enter a family name.")%>
</p>

<%
   String family = familyHelper.getFamily();
   if (family.length() <= 0) {
       // no family yet
%>
<div class="configure">
<form action="" method="POST" enctype="multipart/form-data" accept-charset="UTF-8">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<h3><%=intl._t("Join Existing Router Family")%></h3>
<p><%=intl._t("Import the secret family key that you exported from an existing router in the family.")%>
<p><%=intl._t("Select secret key file")%> :
<input name="file" type="file" value="" />
</p>
<div class="formaction">
<input type="submit" name="action" class="download" value="<%=intl._t("Join Existing Router Family")%>" />
</div></form></div>

<div class="configure"><form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<h3><%=intl._t("Create New Router Family")%></h3>
<p><%=intl._t("Family Name")%> :
<input name="family" type="text" size="30" value="" />
</p>
<div class="formaction">
<input type="submit" name="action" class="accept" value="<%=intl._t("Create New Router Family")%>" />
</div></form></div>
<%
   } else {
       // family is configured
       String keypw = familyHelper.getKeyPW();
       if (keypw.length() > 0) {
           // family is active
%>
<div class="configure">
<form action="/exportfamily" method="GET">
<h3><%=intl._t("Export Family Key")%></h3>
<p><%=intl._t("Export the secret family key to be imported into other routers you control.")%>
</p>
<div class="formaction">
<input type="submit" name="action" class="go" value="<%=intl._t("Export Family Key")%>" />
</div></form></div>
<%
       } else {
           // family is not active
%>
<p><b><%=intl._t("Restart required to activate family {0}.", '"' + family + '"')%>
<%=intl._t("After restarting, you may export the family key.")%></b></p>
<%
       }
%>
<div class="configure"><form action="" method="POST">
<input type="hidden" name="nonce" value="<%=pageNonce%>" >
<h3><%=intl._t("Leave Router Family")%></h3>
<p><%=intl._t("No longer be a member of the family {0}.", '"' + family + '"')%>
<div class="formaction">
<input type="submit" name="action" class="delete" value="<%=intl._t("Leave Router Family")%>" />
</div></form></div>
<%
   }
%>
</div></body></html>
