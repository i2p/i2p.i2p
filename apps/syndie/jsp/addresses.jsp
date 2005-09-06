<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" import="net.i2p.data.Base64, net.i2p.syndie.web.*, net.i2p.syndie.sml.*, net.i2p.syndie.data.*, net.i2p.syndie.*, org.mortbay.servlet.MultiPartRequest, java.util.*, java.io.*" %>
<% request.setCharacterEncoding("UTF-8"); %>
<jsp:useBean scope="session" class="net.i2p.syndie.User" id="user" />
<html>
<head>
<title>SyndieMedia addressbook</title>
<link href="style.jsp" rel="stylesheet" type="text/css" />
</head>
<body>
<table border="1" cellpadding="0" cellspacing="0" width="100%">
<tr><td colspan="5" valign="top" align="left"><jsp:include page="_toplogo.jsp" /></td></tr>
<tr><td valign="top" align="left" rowspan="2"><jsp:include page="_leftnav.jsp" /></td>
    <jsp:include page="_topnav.jsp" />
    <td valign="top" align="left" rowspan="2"><jsp:include page="_rightnav.jsp" /></td></tr>
<tr><td valign="top" align="left" colspan="3"><%
if (!user.getAuthenticated()) { 
    %>You must log in to view your addressbook<% 
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
          %><b>Address updated</b><%
        }
    } else if ( (action != null) && ("Add".equals(action)) ) {
        PetName cur = names.get(request.getParameter("name"));
        if (cur != null) { %><b>Address already exists</b><% } else {
          cur = new PetName();
          cur.setName(request.getParameter("name"));
          cur.setNetwork(request.getParameter("network"));
          cur.setProtocol(request.getParameter("protocol"));
          cur.setIsPublic(null != request.getParameter("isPublic"));
          cur.setLocation(request.getParameter("location"));
          cur.setGroups(request.getParameter("groups"));
          names.set(cur.getName(), cur);
          names.store(user.getAddressbookLocation());
          %><b>Address added</b><%
        }
    } else if ( (action != null) && ("Delete".equals(action)) ) {
        PetName cur = names.get(request.getParameter("name"));
        if (cur != null) { 
          names.remove(cur.getName());
          names.store(user.getAddressbookLocation());
          %><b>Address removed</b><%
        }
    } else if ( (action != null) && ("Export".equals(action)) ) {
      %><%=BlogManager.instance().exportHosts(user)%><%
    }
    TreeSet sorted = new TreeSet(names.getNames());
    %><table border="0" width="100%">
<tr><td><b>Name</b></td><td><b>Network</b></td><td><b>Protocol</b></td><td><b>Location</b></td><td><b>Public?</b></td><td><b>Groups</b><td>&nbsp;</td></tr>
<%
    StringBuffer buf = new StringBuffer(128);
    for (Iterator iter = sorted.iterator(); iter.hasNext(); ) {
        PetName name = names.get((String)iter.next());
        buf.append("<tr><form action=\"addresses.jsp\" method=\"POST\">");
        buf.append("<input type=\"hidden\" name=\"petname\" value=\"").append(name.getName()).append("\" />");
        buf.append("<td><input type=\"text\" size=\"20\" name=\"name\" value=\"").append(name.getName()).append("\" /></td><td>");
        buf.append("<select name=\"network\">");
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

        buf.append("</select></td><td><select name=\"protocol\">");
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

        buf.append("</select></td><td>");
        if (name.getLocation() != null)
            buf.append("<input name=\"location\" size=\"50\" value=\"").append(name.getLocation()).append("\" />");
        else
            buf.append("<input name=\"location\" size=\"50\" value=\"\" />");

        buf.append("</td><td><input type=\"checkbox\" name=\"isPublic\" ");
        if (name.getIsPublic())
            buf.append("checked=\"true\" ");
        buf.append(" /></td><td><input type=\"text\" name=\"groups\" size=\"10\" value=\"");
        for (int j = 0; j < name.getGroupCount(); j++) {
            buf.append(HTMLRenderer.sanitizeTagParam(name.getGroup(j)));
            if (j + 1 < name.getGroupCount()) 
                buf.append(',');
        }
        buf.append("\" /></td><td nowrap=\"true\">");
        buf.append("<input type=\"submit\" name=\"action\" value=\"Change\" /> <input type=\"submit\" name=\"action\" value=\"Delete\" />");
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
    <tr><form action="addresses.jsp" method="POST"><td><input type="text" name="name" size="20" value="<%=name%>" /></td>
        <td><select name="network">
            <option value="i2p" <%="i2p".equalsIgnoreCase(net) ? " selected=\"true\" " : ""%>>I2P</option>
            <option value="syndie" <%="syndie".equalsIgnoreCase(net) ? " selected=\"true\" " : ""%>>Syndie</option>
            <option value="tor" <%="tor".equalsIgnoreCase(net) ? " selected=\"true\" " : ""%>>Tor</option>
            <option value="freenet" <%="freenet".equalsIgnoreCase(net) ? " selected=\"true\" " : ""%>>Freenet</option>
            <option value="internet" <%="internet".equalsIgnoreCase(net) ? " selected=\"true\" " : ""%>>Internet</option></select></td>
        <td><select name="protocol">
            <option value="http" <%="http".equalsIgnoreCase(proto) ? " selected=\"true\" " : ""%>>HTTP</option>
            <option value="irc" <%="irc".equalsIgnoreCase(proto) ? " selected=\"true\" " : ""%>>IRC</option>
            <option value="i2phex" <%="i2phex".equalsIgnoreCase(proto) ? " selected=\"true\" " : ""%>>I2Phex</option>
            <option value="syndiearchive" <%="syndiearchive".equalsIgnoreCase(proto) ? " selected=\"true\" " : ""%>>Syndie archive</option>
            <option value="syndieblog" <%="syndieblog".equalsIgnoreCase(proto) ? " selected=\"true\" " : ""%>>Syndie blog</option></select></td>
        <td><input type="text" size="50" name="location" value="<%=loc%>" /></td>
        <td><input type="checkbox" name="isPublic" /></td>
        <td><input type="text" name="groups" size="10" /></td>
        <td><input type="submit" name="action" value="Add" /></td>
    </form></tr>
    <tr><form action="addresses.jsp" method="POST">
        <td colspan="7">Export the eepsites to your router's userhosts.txt: <input type="submit" name="action" value="Export" /></td>
        </form></tr>
    </table>
    <%
}
%>
</td></tr>
</table>
</body>
