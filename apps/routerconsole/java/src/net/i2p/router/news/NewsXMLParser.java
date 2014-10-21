package net.i2p.router.news;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;
import org.cybergarage.xml.Node;
import org.cybergarage.xml.ParserException;
import org.cybergarage.xml.parser.JaxpParser;

/**
 *  Parse out the news.xml file which is in Atom format (RFC4287).
 *
 *  We use the XML parser from the UPnP library.
 *
 *  @since 0.9.17
 */
public class NewsXMLParser {
    private final I2PAppContext _context;
    private final Log _log;
    private List<NewsEntry> _entries;
    private NewsMetadata _metadata;
    private XHTMLMode _mode;

    private static final Set xhtmlWhitelist = new HashSet(Arrays.asList(new String[] {
        "a", "b", "br", "div", "i", "p", "span", "font", "blockquote", "hr",
        "del", "ins", "em", "strong", "mark", "sub", "sup", "tt", "code", "strike", "s", "u",
        "h4", "h5", "h6",
        "ol", "ul", "li", "dl", "dt", "dd",
        "table", "tr", "td", "th"
    }));

    /**
     *  The action taken when encountering a non-whitelisted
     *  XHTML element in the feed content.
     */
    public enum XHTMLMode {
        /** abort the parsing on any non-whitelisted element */
        ABORT,
        /** remove only the non-whitelisted element */
        REMOVE_ELEMENT,
        /** skip the feed entry containing the non-whitelisted element */
        SKIP_ENTRY,
        /** disable whitelist checks */
        ALLOW_ALL
    }

    public NewsXMLParser(I2PAppContext ctx) { 
        _context = ctx;
        _log = ctx.logManager().getLog(NewsXMLParser.class);
        _mode = XHTMLMode.REMOVE_ELEMENT;
    }

    /**
     *  Sets the action taken when encountering a non-whitelisted
     *  XHTML element in the feed content.
     *  Must be set before parse().
     *  Default REMOVE_ELEMENT.
     */
    public void setXHTMLMode(XHTMLMode mode) {
        _mode = mode;
    }

    /**
     *  Process the XML file.
     *
     *  @param file XML content only. Any su3 or gunzip handling must have
     *              already happened.
     *  @throws IOException on any parse error
     */
    public void parse(File file) throws IOException {
        parse(new BufferedInputStream(new FileInputStream(file)));
    }

    /**
     *  Process the XML input stream.
     *
     *  @param in XML content only. Any su3 or gunzip handling must have
     *            already happened.
     *  @throws IOException on any parse error
     */
    public void parse(InputStream in) throws IOException {
        _entries = null;
        _metadata = null;
        JaxpParser parser = new JaxpParser();
        try {
            Node root = parser.parse(in);
            extract(root);
        } catch (ParserException pe) {
            throw new I2PParserException(pe);
        }
    }

    /**
     *  The news entries.
     *  Must call parse() first.
     *
     *  @return sorted, newest first, null if parse failed
     */
    public List<NewsEntry> getEntries() {
        return _entries;
    }

    /**
     *  The news metatdata.
     *  Must call parse() first.
     *
     *  @return null if parse failed
     */
    public NewsMetadata getMetadata() {
        return _metadata;
    }

    private void extract(Node root) throws I2PParserException {
        if (!root.getName().equals("feed"))
            throw new I2PParserException("no feed in XML");
        _metadata = extractNewsMetadata(root);
        _entries = extractNewsEntries(root);
    }

    private static NewsMetadata extractNewsMetadata(Node feed) throws I2PParserException {
        NewsMetadata rv = new NewsMetadata();
        Node n = feed.getNode("title");
        if (n != null)
            rv.feedTitle = n.getValue();
        n = feed.getNode("subtitle");
        if (n != null)
            rv.feedSubtitle = n.getValue();
        n = feed.getNode("id");
        if (n != null)
            rv.feedID = n.getValue();
        n = feed.getNode("updated");
        if (n != null) {
            String v = n.getValue();
            if (v != null) {
                long time = RFC3339Date.parse3339Date(v);
                if (time > 0)
                    rv.feedUpdated = time;
            }
        }

        Node r = feed.getNode("i2p:release");
        if (r == null)
            throw new I2PParserException("no release data in XML");
        // release attributes
        String a = r.getAttributeValue("date");
        if (a.length() > 0) {
            long time = RFC3339Date.parse3339Date(a);
            if (time > 0)
                rv.date = time;
        }
        a = r.getAttributeValue("minVersion");
        if (a.length() > 0)
            rv.minVersion = a;
        a = r.getAttributeValue("minJavaVersion");
        if (a.length() > 0)
            rv.minJavaVersion = a;
        // release nodes
        n = r.getNode("i2p:version");
        if (n != null)
            rv.i2pVersion = n.getValue();
        List<Node> urls = getNodes(r, "i2p:torrent");
        for (Node t : urls) {
            // returns "" for none
            String href = t.getAttributeValue("href");
            if (href.length() > 0) {
                String type = t.getAttributeValue("type");
                if (type.equals("su2"))
                    rv.su2Torrent = href;
                else if (type.equals("su3"))
                    rv.su3Torrent = href;
            }
        }

        return rv;
    }

    private List<NewsEntry> extractNewsEntries(Node feed) throws I2PParserException {
        List<NewsEntry> rv = new ArrayList<NewsEntry>();
        List<Node> entries = getNodes(feed, "entry");
        for (Node entry : entries) {
            NewsEntry e = new NewsEntry();
            Node n = entry.getNode("title");
            if (n != null)
                e.title = n.getValue();
            n = entry.getNode("link");
            if (n != null)
                e.link = n.getValue();
            n = entry.getNode("id");
            if (n != null)
                e.id = n.getValue();
            n = entry.getNode("updated");
            if (n != null) {
                String v = n.getValue();
                if (v != null) {
                    long time = RFC3339Date.parse3339Date(v);
                    if (time > 0)
                        e.updated = time;
                }
            }
            n = entry.getNode("summary");
            if (n != null)
                e.summary = n.getValue();
            n = entry.getNode("author");
            if (n != null) {
                n = n.getNode("name");
                if (n != null)
                    e.authorName = n.getValue();
            }
            n = entry.getNode("content");
            if (n != null) {
                String a = n.getAttributeValue("type");
                if (a.length() > 0)
                    e.contentType = a;
                // now recursively sanitize
                // and convert everything in the content to string
                StringBuilder buf = new StringBuilder(256);
                for (int i = 0; i < n.getNNodes(); i++) {
                    Node sn = n.getNode(i);
                    try {
                        boolean removed = validate(sn);
                        if (removed) {
                            i--;
                            continue;
                        }
                    } catch (I2PParserException ipe) {
                        switch (_mode) {
                          case ABORT:
                            throw ipe;
                          case SKIP_ENTRY:
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Skipping entry", ipe);
                            e = null;
                            break;
                          case REMOVE_ELEMENT:
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Removing element", ipe);
                            continue;
                          case ALLOW_ALL:
                          default:
                            break;
                        }
                    }
                    if (e == null)
                        break;
                    buf.append(sn.toString());
                }
                if (e == null)
                    continue;
                e.content = buf.toString();
            }
            rv.add(e);
        }
        Collections.sort(rv);
        return rv;
    }

    /**
     *  Helper to get all Nodes matching the name
     */
    private static List<Node> getNodes(Node node, String name) {
        List<Node> rv = new ArrayList<Node>();
        int count = node.getNNodes();
        for (int i = 0; i < count; i++) {
            Node n = node.getNode(i);
            if (n.getName().equals(name))
                rv.add(n);
        }
        return rv;
    }

    /**
     *  @throws I2PParserException if any node not in whitelist (depends on mode)
     *  @return true if node was removed from parent (only for REMOVE_ELEMENT mode)
     */
    private boolean validate(Node node) throws I2PParserException {
        String name = node.getName();
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Validating element: " + name);
        if (!xhtmlWhitelist.contains(name.toLowerCase(Locale.US))) {
            switch (_mode) {
              case ABORT:
              case SKIP_ENTRY:
                throw new I2PParserException("Invalid XHTML element \"" + name + '"');
              case REMOVE_ELEMENT:
                // fixme this screws up the iteration
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Removing element: " + node);
                node.getParentNode().removeNode(node);
                return true;
              case ALLOW_ALL:
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Allowing non-whitelisted element by configuration: " + node);
                break;
            }
        }
        int count = node.getNNodes();
        for (int i = 0; i < node.getNNodes(); i++) {
            boolean removed = validate(node.getNode(i));
            if (removed)
                i--;
        }
        return false;
    }

    /**
     *  Extend IOE since cybergarage ParserException extends Exception
     */
    private static class I2PParserException extends IOException {
        public I2PParserException(String s) {
            super(s);
        }

        public I2PParserException(Throwable t) {
            super("XML Parse Error", t);
        }
    }

    public static void main(String[] args) {
        try {
            NewsXMLParser parser = new NewsXMLParser(new I2PAppContext());
            parser.setXHTMLMode(XHTMLMode.ABORT);
            //parser.setXHTMLMode(XHTMLMode.REMOVE_ELEMENT);
            //parser.setXHTMLMode(XHTMLMode.SKIP_ENTRY);
            //parser.setXHTMLMode(XHTMLMode.ALLOW_ALL);
            parser.parse(new File(args[0]));
            NewsMetadata ud = parser.getMetadata();
            List<NewsEntry> entries = parser.getEntries();
            System.out.println("Latest version is " + ud.i2pVersion);
            System.out.println("Release timestamp: " + ud.date);
            System.out.println("Feed timestamp: " + ud.feedUpdated);
            System.out.println("Found " + entries.size() + " news entries");
            for (int i = 0; i < entries.size(); i++) {
                NewsEntry e = entries.get(i);
                System.out.println("News #" + (i+1) + ": " + e.title + '\n' + e.content);
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }
}
