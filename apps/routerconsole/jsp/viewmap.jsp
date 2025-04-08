<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 *
 * Do not tag this file for translation.
 */
        response.setContentType("image/svg+xml");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "inline; filename=\"i2pmap.svg\"");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Accept-Ranges", "none");
        response.setHeader("Connection", "Close");
        java.io.OutputStream cout = response.getOutputStream();
        net.i2p.router.web.helpers.MapMaker mm = new net.i2p.router.web.helpers.MapMaker();
        // don't include animations on updates, because the browser doesn't render them
        int mode = 7;
        String f = request.getParameter("f");
        if (f != null)
            mode = Integer.parseInt(f) >> 4;
        boolean rendered = mm.render(mode, cout);

        if (rendered)
            cout.close();
        else
            response.sendError(403, "Map not available");
%>