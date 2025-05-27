// Adapted from Jetty ResourceService.java and ResourceListing.java
//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//
package net.i2p.servlet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.text.Collator;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.ee8.nested.ResourceService;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollators;
import org.eclipse.jetty.util.resource.Resources;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

/**
 *  To customize directory listings.
 *  Used by I2PDefaultServlet.
 *
 *  @since Jetty 12
 */
public class I2PResourceService extends ResourceService {

    private static final String FORMAT = "yyyy-MM-dd HH:mm";

    /**
     * Copied and modified from ResourceService.java.
     * Overridden to set the Locale for the dates.
     *
     * Get the resource list as a HTML directory listing.
     */
    @Override
    protected void sendDirectory(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Resource resource,
                                 String pathInContext)
        throws IOException
    {
        if (!isDirAllowed())
        {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        byte[] data;
        String base = URIUtil.addEncodedPaths(request.getRequestURI(), "/");
        String dir = getListHTML(resource, base, pathInContext.length() > 1, request.getQueryString());
        if (dir == null)
        {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                "No directory");
            return;
        }

        data = dir.getBytes(StandardCharsets.UTF_8);
        response.setContentType("text/html;charset=utf-8");
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
    }

    /**
     * Copied and modified from Resource.java (Jetty 9) / ResourceListing.java (Jetty 12).
     * Modified to set the Locale for the dates.
     *
     * Get the resource list as a HTML directory listing.
     * @param base The base URL
     * @param parent True if the parent directory should be included
     * @return String of HTML
     * @since Jetty 12 moved from I2PDefaultServlet
     */
    private static String getListHTML(Resource resource, String base, boolean parent, String query)
        throws IOException
    {
        // This method doesn't check aliases, so it is OK to canonicalize here.
        base = URIUtil.normalizePath(base);
        if (base == null)
            return null;
        if (!Resources.isReadableDirectory(resource))
            return null;

        List<Resource> listing = resource.list().stream()
            .filter(distinctBy(Resource::getFileName))
            .collect(Collectors.toCollection(ArrayList::new));

        boolean sortOrderAscending = true;
        String sortColumn = "N"; // name (or "M" for Last Modified, or "S" for Size)

        // check for query
        if (query != null)
        {
            Fields params = new Fields(true);
            UrlEncoded.decodeUtf8To(query, 0, query.length(), params);

            String paramO = params.getValue("O");
            String paramC = params.getValue("C");
            if (StringUtil.isNotBlank(paramO))
            {
                switch (paramO)
                {
                    case "A" -> sortOrderAscending = true;
                    case "D" -> sortOrderAscending = false;
                }
            }
            if (StringUtil.isNotBlank(paramC))
            {
                if (paramC.equals("N") || paramC.equals("M") || paramC.equals("S"))
                {
                    sortColumn = paramC;
                }
            }
        }

        // Perform sort
        Comparator<? super Resource> sort;
        switch (sortColumn)
        {
            case "M": sort = ResourceCollators.byLastModified(sortOrderAscending);
                      break;
            case "S": sort = new SizeComparator();
                      if (!sortOrderAscending)
                          sort = sort.reversed();
                      break;
            default:  sort = new FileComparator();
                      if (!sortOrderAscending)
                          sort = sort.reversed();
                      break;
        }
        DataHelper.sort(listing, sort);

        String decodedBase = URIUtil.decodePath(base);
        String title = "Directory: " + deTag(decodedBase);

        StringBuilder buf = new StringBuilder(4096);

        // Doctype Declaration + XHTML. The spec says the encoding MUST be "utf-8" in all cases at it is ignored;
        // see: https://html.spec.whatwg.org/multipage/semantics.html#attr-meta-charset
        buf.append("""
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
            <html xmlns="http://www.w3.org/1999/xhtml" lang="en" xml:lang="en">
            """);

        // HTML Header
        buf.append("<head>\n");
        buf.append("<link href=\"jetty-dir.css\" rel=\"stylesheet\" />\n");
        buf.append("<title>");
        buf.append(title);
        buf.append("</title>\n");
        buf.append("</head>\n");

        // HTML Body
        buf.append("<body>\n");
        buf.append("<h1 class=\"title\">").append(title).append("</h1>\n");

        // HTML Table
        final String ARROW_DOWN = "&nbsp; &#8681;";
        final String ARROW_UP = "&nbsp; &#8679;";

        buf.append("<table class=\"listing\">\n");
        buf.append("<thead>\n");

        String arrow = "";
        String order = "A";
        if (sortColumn.equals("N"))
        {
            if (sortOrderAscending)
            {
                order = "D";
                arrow = ARROW_UP;
            }
            else
            {
                order = "A";
                arrow = ARROW_DOWN;
            }
        }

        buf.append("<tr><th class=\"name\"><a href=\"?C=N&amp;O=").append(order).append("\">");
        buf.append("Name").append(arrow);
        buf.append("</a></th>");

        arrow = "";
        order = "A";
        if (sortColumn.equals("M"))
        {
            if (sortOrderAscending)
            {
                order = "D";
                arrow = ARROW_UP;
            }
            else
            {
                order = "A";
                arrow = ARROW_DOWN;
            }
        }

        buf.append("<th class=\"lastmodified\"><a href=\"?C=M&amp;O=").append(order).append("\">");
        buf.append("Last Modified (UTC)").append(arrow);
        buf.append("</a></th>");

        arrow = "";
        order = "A";
        if (sortColumn.equals("S"))
        {
            if (sortOrderAscending)
            {
                order = "D";
                arrow = ARROW_UP;
            }
            else
            {
                order = "A";
                arrow = ARROW_DOWN;
            }
        }
        buf.append("<th class=\"size\"><a href=\"?C=S&amp;O=").append(order).append("\">");
        buf.append("Size").append(arrow);
        buf.append("</a></th></tr>\n");
        buf.append("</thead>\n");

        buf.append("<tbody>\n");

        String encodedBase = hrefEncodeURI(base);

        if (parent)
        {
            // Name
            buf.append("<tr><td class=\"name\"><a href=\"");
            // TODO This produces an absolute link from the /context/<listing-dir> path, investigate if we can use relative links reliably now
            buf.append(URIUtil.addPaths(encodedBase, "../"));
            buf.append("\">Parent Directory</a></td>");
            // Last Modified
            buf.append("<td class=\"lastmodified\">-</td>");
            // Size
            buf.append("<td class=\"size\">-</td>");
            buf.append("</tr>\n");
        }

        DateFormat dfmt = new SimpleDateFormat(FORMAT, Locale.UK);
        TimeZone utc = TimeZone.getTimeZone("GMT");
        dfmt.setTimeZone(utc);

        for (Resource item : listing)
        {
            // Listings always return non-composite Resource entries
            String name = item.getFileName();
            if (StringUtil.isBlank(name))
                continue; // a resource either not backed by a filename (eg: MemoryResource), or has no filename (eg: a segment-less root "/")

            // Ensure name has a slash if it's a directory
            boolean isDir = item.isDirectory();
            if (isDir && !name.endsWith("/"))
                name += "/";

            // Name
            buf.append("<tr><td class=\"name\"><a href=\"");
            // TODO should this be a relative link?
            String path = URIUtil.addEncodedPaths(encodedBase, URIUtil.encodePath(name));
            buf.append(path);
            buf.append("\">");
            buf.append(deTag(name));
            buf.append("&nbsp;</a></td>");

            // Last Modified
            buf.append("<td class=\"lastmodified\">");
            Instant lastModified = item.lastModified();
            buf.append(dfmt.format(new Date(lastModified.toEpochMilli())));
            buf.append("&nbsp;</td>");

            // Size
            buf.append("<td class=\"size\">");
            if (isDir) {
                buf.append('-');
            } else {
                long length = item.length();
                if (length >= 0)
                {
                    buf.append(String.format("%,d bytes", item.length()));
                } else {
                    buf.append('-');
                }
            }
            buf.append("</td></tr>\n");
        }

        buf.append("</tbody>\n");
        buf.append("</table>\n");
        buf.append("</body></html>\n");

        return buf.toString();
    }

    private static <T> Predicate<T> distinctBy(Function<? super T, Object> keyExtractor)
    {
        HashSet<Object> map = new HashSet<>();
        return t -> map.add(keyExtractor.apply(t));
    }
    
    /**
     *  I2P
     *
     *  @since 0.9.51
     */
    private static class FileComparator implements Comparator<Resource> {
        private final Comparator<Object> _coll;

        public FileComparator() {
            _coll = Collator.getInstance(Locale.US);
        }

        public int compare(Resource ra, Resource rb) {
            try {
                boolean da = ra.isDirectory();
                boolean db = rb.isDirectory();
                if (da && !db) return -1;
                if (!da && db) return 1;
            } catch (Exception e) {
                // see above
            }
            return _coll.compare(ra.toString(), rb.toString());
        }
    }
    
    /**
     *  I2P
     *
     *  @since Jetty 12
     */
    private static class SizeComparator implements Comparator<Resource> {
        private final Comparator<Object> _coll;

        public SizeComparator() {
            _coll = Collator.getInstance(Locale.US);
        }

        public int compare(Resource ra, Resource rb) {
            try {
                boolean da = ra.isDirectory();
                boolean db = rb.isDirectory();
                long sa = da ? -1 : ra.length();
                long sb = db ? -1 : rb.length();
                int rv = Long.compare(sa, sb);
                if (rv != 0) return rv;
            } catch (Exception e) {
                // see above
            }
            return _coll.compare(ra.toString(), rb.toString());
        }
    }

    /**
     * Copied unchanged from Resource.java
     *
     * Encode any characters that could break the URI string in an HREF.
     * Such as &lt;a href="/path/to;&lt;script&gt;Window.alert("XSS"+'%20'+"here");&lt;/script&gt;"&gt;Link&lt;/a&gt;
     * 
     * The above example would parse incorrectly on various browsers as the "&lt;" or '"' characters
     * would end the href attribute value string prematurely.
     * 
     * @param raw the raw text to encode.
     * @return the defanged text.
     * @since Jetty 12 moved from I2PDefaultServlet
     */
    private static String hrefEncodeURI(String raw) 
    {
        StringBuffer buf = null;

        loop:
        for (int i=0;i<raw.length();i++)
        {
            char c=raw.charAt(i);
            switch(c)
            {
                case '\'':
                case '"':
                case '<':
                case '>':
                    buf=new StringBuffer(raw.length()<<1);
                    break loop;
            }
        }
        if (buf==null)
            return raw;

        for (int i=0;i<raw.length();i++)
        {
            char c=raw.charAt(i);       
            switch(c)
            {
              case '"':
                  buf.append("%22");
                  continue;
              case '\'':
                  buf.append("%27");
                  continue;
              case '<':
                  buf.append("%3C");
                  continue;
              case '>':
                  buf.append("%3E");
                  continue;
              default:
                  buf.append(c);
                  continue;
            }
        }

        return buf.toString();
    }
    
    /**
     * Copied unchanged from Resource.java
     * @since Jetty 12 moved from I2PDefaultServlet
     */
    private static String deTag(String raw) 
    {
        return StringUtil.sanitizeXmlString(raw);
    }
}
