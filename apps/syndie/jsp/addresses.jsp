<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" import="net.i2p.data.Base64, net.i2p.syndie.web.*, net.i2p.syndie.sml.*, net.i2p.syndie.data.*, net.i2p.syndie.*, net.i2p.client.naming.PetName, net.i2p.client.naming.PetNameDB, org.mortbay.servlet.MultiPartRequest, java.util.*, java.io.*" %><%
 request.setCharacterEncoding("UTF-8"); %><jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" 
/><!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 TRANSITIONAL//EN" "http://www.w3c.org/TR/1999/REC-html401-19991224/loose.dtd">
<html>
<head>
<title>SyndieMedia addressbook</title>
<link href="style.jsp" rel="stylesheet" type="text/css" >
</head>
<body>
<table border="1" cellpadding="0" cellspacing="0" width="100%">
<tr class="b_toplogo"><td colspan="5" valign="top" align="left" class="b_toplogo"><jsp:include page="_toplogo.jsp" /></td></tr>
<tr><td valign="top" align="left" rowspan="2" class="b_leftnav"><jsp:include page="_leftnav.jsp" /></td>
    <jsp:include page="_topnav.jsp" />
    <td valign="top" align="left" rowspan="2" class="b_rightnav"><jsp:include page="_rightnav.jsp" /></td></tr>
<tr class="b_content"><td valign="top" align="left" colspan="3" class="b_content"><%
if (!user.getAuthenticated()) { 
    %><span class="b_addrMsgErr">You must log in to view your addressbook</span><% 
} else {
    PetNameDB names = user.getPetNameDB();
    String action = request.getParameter("action");
    if ( (action != null) && ("Change".equals(action)) ) {
        String oldPetname = request.getParameter("petname");
        PetName cur = names.get(oldPetname);
        if (cur != null) {
          cur.setName(request.getParameter("name"));
          cur.setNetwork(request.getParameter("network"));
          cur.setProtocol(request.getParameter("protocol"));
          cur.setIsPublic(null != request.getParameter("isPublic"));
          cur.setLocation(request.getParameter("location"));
          cur.setGroups(request.getParameter("groups"));
          names.remove(oldPetname);
          names.set(cur.getName(), cur);
          names.store(user.getAddressbookLocation());
          %><span class="b_addrMsgOk">Address updated</span><%
        }
    } else if ( (action != null) && ("Add".equals(action)) ) {
        PetName cur = names.get(request.getParameter("name"));
        if (cur != null) { %><span class="b_addrMsgErr">Address already exists</span><% } else {
          cur = new PetName();
          cur.setName(request.getParameter("name"));
          cur.setNetwork(request.getParameter("network"));
          cur.setProtocol(request.getParameter("protocol"));
          cur.setIsPublic(null != request.getParameter("isPublic"));
          cur.setLocation(request.getParameter("location"));
          cur.setGroups(request.getParameter("groups"));
          names.set(cur.getName(), cur);
          names.store(user.getAddressbookLocation());
          %><span class="b_addrMsgOk">Address added</span><%
        }
    } else if ( (action != null) && ("Delete".equals(action)) ) {
        PetName cur = names.get(request.getParameter("name"));
        if (cur != null) { 
          names.remove(cur.getName());
          names.store(user.getAddressbookLocation());
          %><span class="b_addrMsgOk">Address removed</span><%
        }
    } else if ( (action != null) && ("Export".equals(action)) ) {
      %><%=BlogManager.instance().exportHosts(user)%><%
    }
    TreeSet sorted = new TreeSet(names.getNames());
    %><table border="0" width="100%" class="b_addr">
<tr class="b_addrHeader">
 <td class="b_addrHeader"><em class="b_addrHeader">Name</em></td>
 <td class="b_addrHeader"><em class="b_addrHeader">Network</em></td>
 <td class="b_addrHeader"><em class="b_addrHeader">Protocol</em></td>
 <td class="b_addrHeader"><em class="b_addrHeader">Location</em></td>
 <td class="b_addrHeader"><em class="b_addrHeader">Public?</em></td>
 <td class="b_addrHeader"><em class="b_addrHeader">Groups</em></td> 
 <td class="b_addrHeader">&nbsp;</td></tr>
<%
    StringBuffer buf = new StringBuffer(128);
    for (Iterator iter = sorted.iterator(); iter.hasNext(); ) {
        PetName name = names.get((String)iter.next());
        buf.append("<tr class=\"b_addrDetail\"><form action=\"addresses.jsp\" method=\"POST\">");
        buf.append("<input type=\"hidden\" name=\"petname\" value=\"").append(name.getName()).append("\" />");
        buf.append("<td class=\"b_addrName\"><input class=\"b_addrName\" type=\"text\" size=\"20\" name=\"name\" value=\"").append(name.getName()).append("\" /></td>");
        buf.append("<td class=\"b_addrNet\"><select class=\"b_addrNet\" name=\"network\">");
        String net = name.getNetwork();
        if (net == null) net = "";
        buf.append("<option value=\"i2p\" ");
        if ("i2p".equals(net))
            buf.append("selected=\"true\" ");
        buf.append("/>I2P</option>");

        buf.append("<option value=\"syndie\" ");
        if ( ("syndie".equals(net)) || ("".equals(net)) )
            buf.append("selected=\"true\" ");
        buf.append("/>Syndie</option>");

        buf.append("<option value=\"tor\" ");
        if ("tor".equals(net))
            buf.append("selected=\"true\" ");
        buf.append("/>TOR</option>");

        buf.append("<option value=\"freenet\" ");
        if ("freenet".equals(net))
            buf.append("selected=\"true\" ");
        buf.append("/>Freenet</option>");

        buf.append("<option value=\"internet\" ");
        if ("internet".equals(net))
            buf.append("selected=\"true\" ");
        buf.append("/>Internet</option>");

        buf.append("</select></td>");

        buf.append("<td class=\"b_addrProto\"><select class=\"b_addrProto\" name=\"protocol\">");
        String proto = name.getProtocol();
        if (proto == null) proto = "";

        buf.append("<option value=\"http\" ");
        if ("http".equals(proto))
            buf.append("selected=\"true\" ");
        buf.append("/>HTTP</option>");

        buf.append("<option value=\"irc\" ");
        if ("irc".equals(proto))
            buf.append("selected=\"true\" ");
        buf.append("/>IRC</option>");

        buf.append("<option value=\"i2phex\" ");
        if ("i2phex".equals(proto))
            buf.append("selected=\"true\" ");
        buf.append("/>I2Phex</option>");

        buf.append("<option value=\"syndiearchive\" ");
        if ("syndiearchive".equals(proto))
            buf.append("selected=\"true\" ");
        buf.append("/>Syndie archive</option>");

        buf.append("<option value=\"syndieblog\" ");
        if ("syndieblog".equals(proto))
            buf.append("selected=\"true\" ");
        buf.append("/>Syndie blog</option>");

        buf.append("</select></td>");

        buf.append("<td class=\"b_addrLoc\">");
        if (name.getLocation() != null)
            buf.append("<input class=\"b_addrLoc\" name=\"location\" size=\"50\" value=\"").append(name.getLocation()).append("\" />");
        else
            buf.append("<input class=\"b_addrLoc\" name=\"location\" size=\"50\" value=\"\" />");

        buf.append("</td>");
        buf.append("<td class=\"b_addrPublic\"><input class=\"b_addrPublic\" type=\"checkbox\" name=\"isPublic\" ");
        if (name.getIsPublic())
            buf.append("checked=\"true\" ");
        buf.append(" /></td>");
        buf.append("<td class=\"b_addrGroup\"><input class=\"b_addrGroup\" type=\"text\" name=\"groups\" size=\"10\" value=\"");
        for (int j = 0; j < name.getGroupCount(); j++) {
            buf.append(HTMLRenderer.sanitizeTagParam(name.getGroup(j)));
            if (j + 1 < name.getGroupCount()) 
                buf.append(',');
        }
        buf.append("\" /></td><td class=\"b_addrDetail\" nowrap=\"nowrap\">");
        buf.append("<input class=\"b_addrChange\" type=\"submit\" name=\"action\" value=\"Change\" /> <input class=\"b_addrDelete\" type=\"submit\" name=\"action\" value=\"Delete\" />");
        buf.append("</td></form></tr>");
        out.write(buf.toString());
        buf.setLength(0);
    }

    String net = request.getParameter("network");
    String proto = request.getParameter("protocol");
    String name = request.getParameter("name");
    String loc = request.getParameter("location");
    boolean active = (request.getParameter("action") != null);
    if (net == null || active) net = "";
    if (proto == null || active) proto = "";
    if (name == null || active) name = "";
    if (loc == null || active) loc= "";
    %>
    <tr class="b_addrDetail"><form action="addresses.jsp" method="POST">
        <td class="b_addrName"><input class="b_addrName" type="text" name="name" size="20" value="<%=name%>" /></td>
        <td class="b_addrNet"><select class="b_addrNet" name="network">
            <option value="i2p" <%="i2p".equalsIgnoreCase(net) ? " selected=\"true\" " : ""%>>I2P</option>
            <option value="syndie" <%="syndie".equalsIgnoreCase(net) ? " selected=\"true\" " : ""%>>Syndie</option>
            <option value="tor" <%="tor".equalsIgnoreCase(net) ? " selected=\"true\" " : ""%>>Tor</option>
            <option value="freenet" <%="freenet".equalsIgnoreCase(net) ? " selected=\"true\" " : ""%>>Freenet</option>
            <option value="internet" <%="internet".equalsIgnoreCase(net) ? " selected=\"true\" " : ""%>>Internet</option></select></td>
        <td class="b_addrProto"><select class="b_addrProto" name="protocol">
            <option value="http" <%="http".equalsIgnoreCase(proto) ? " selected=\"true\" " : ""%>>HTTP</option>
            <option value="irc" <%="irc".equalsIgnoreCase(proto) ? " selected=\"true\" " : ""%>>IRC</option>
            <option value="i2phex" <%="i2phex".equalsIgnoreCase(proto) ? " selected=\"true\" " : ""%>>I2Phex</option>
            <option value="syndiearchive" <%="syndiearchive".equalsIgnoreCase(proto) ? " selected=\"true\" " : ""%>>Syndie archive</option>
            <option value="syndieblog" <%="syndieblog".equalsIgnoreCase(proto) ? " selected=\"true\" " : ""%>>Syndie blog</option></select></td>
        <td class="b_addrLoc"><input class="b_addrLoc" type="text" size="50" name="location" value="<%=loc%>" /></td>
        <td class="b_addrPublic"><input class="b_addrPublic" type="checkbox" name="isPublic" /></td>
        <td class="b_addrGroup"><input class="b_addrGroup" type="text" name="groups" size="10" /></td>
        <td class="b_addrDetail"><input class="b_addrAdd" type="submit" name="action" value="Add" /></td>
    </form></tr>
    <tr class="b_addrExport"><form action="addresses.jsp" method="POST">
        <td class="b_addrExport" colspan="7">
          <span class="b_addrExport">Export the eepsites to your router's petname db</span>
          <input class="b_addrExportSubmit" type="submit" name="action" value="Export" /></td>
        </form></tr>
    </table>
    <%
}
%>
</td></tr>
</table>
</body>
