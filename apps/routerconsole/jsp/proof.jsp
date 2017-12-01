<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<%@include file="css.jsi" %>
<%=intl.title("Proof")%>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %><h1>Proof of Ownership</h1>
<div class="main" id="proof"><p>
<jsp:useBean class="net.i2p.router.web.helpers.ProofHelper" id="proofHelper" scope="request" />
<jsp:setProperty name="proofHelper" property="contextId" value="<%=(String)session.getAttribute(\"i2p.contextId\")%>" />
<textarea cols="70" rows="15" wrap="off" readonly="readonly" spellcheck="false"><jsp:getProperty name="proofHelper" property="proof" /></textarea>
</p></div></body></html>
