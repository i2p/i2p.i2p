<%@page import="net.i2p.syndie.BlogManager" %>
<jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" />
<jsp:useBean scope="session" class="net.i2p.syndie.data.ArchiveIndex" id="archive" />
<% session.setAttribute("archive", BlogManager.instance().getArchive().getIndex()); %>
<!--
<center>[syndiemedia]</center>
-->