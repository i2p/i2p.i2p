package net.i2p.syndie.sml;

import java.lang.String;
import java.util.*;
import net.i2p.syndie.data.*;

/**
 * Parse out the SML from the text, firing off info to the receiver whenever certain 
 * elements are available.  This is a very simple parser, with no support for nested
 * tags.  A simple stack would be good to add, but DTSTTCPW.
 * 
 * 
 */
public class SMLParser {
    private static final char TAG_BEGIN = '[';
    private static final char TAG_END = ']';
    private static final char LT = '<';
    private static final char GT = '>';
    private static final char EQ = '=';
    private static final char DQUOTE = '"';
    private static final char QUOTE = '\'';
    private static final String WHITESPACE = " \t\n\r";
    private static final char NL = '\n';
    private static final char CR = '\n';
    private static final char LF = '\f';

    public void parse(String rawSML, EventReceiver receiver) {
        receiver.receiveBegin();
        int off = 0;
        off = parseHeaders(rawSML, off, receiver);
        receiver.receiveHeaderEnd();
        parseBody(rawSML, off, receiver);
        receiver.receiveEnd();
    }
    
    private int parseHeaders(String rawSML, int off, EventReceiver receiver) {
        if (rawSML == null) return off;
        int len = rawSML.length();
        if (len == off) return off;
        int keyBegin = off;
        int valBegin = -1;
        while (off < len) { 
            char c = rawSML.charAt(off);
            if ( (c == ':') && (valBegin < 0) ) {
                // moving on to the value
                valBegin = off + 1;
            } else if (c == '\n') {
                if (valBegin < 0) {
                    // end of the headers
                    off++;
                    break;
                } else {
                    String key = rawSML.substring(keyBegin, valBegin-1);
                    String val = rawSML.substring(valBegin, off);
                    receiver.receiveHeader(key.trim(), val.trim());
                    valBegin = -1;
                    keyBegin = off + 1;
                }
            }
            off++;
        }
        if ( (off >= len) && (valBegin > 0) ) {
            String key = rawSML.substring(keyBegin, valBegin-1);
            String val = rawSML.substring(valBegin, len);
            receiver.receiveHeader(key.trim(), val.trim());
        }
        return off;
    }
    
    private void parseBody(String rawSMLBody, int off, EventReceiver receiver) {
        if (rawSMLBody == null) return;
        int begin = off;
        int len = rawSMLBody.length();
        if (len <= off) return;
        int openTagBegin = -1;
        int openTagEnd = -1;
        int closeTagBegin = -1;
        int closeTagEnd = -1;
        while (off < len) {
            char c = rawSMLBody.charAt(off);
            if ( (c == NL) || (c == CR) || (c == LF) ) {
                if (openTagBegin < 0) {
                    if (begin < off)
                        receiver.receivePlain(rawSMLBody.substring(begin, off));
                    receiver.receiveNewline();
                    off++;
                    begin = off;
                    continue;
                } else {
                    // ignore NL inside a tag or between tag blocks
                }
            } else if (c == TAG_BEGIN) {
                if ( (off + 1 < len) && (TAG_BEGIN == rawSMLBody.charAt(off+1))) {
                    if (begin < off)
                        receiver.receivePlain(rawSMLBody.substring(begin, off));
                    receiver.receiveLeftBracket();
                    off += 2;
                    begin = off;
                    continue;
                } else if (openTagBegin < 0) {
                    // push everything seen and not accounted for into a plain area
                    if (closeTagEnd < 0) {
                        if (begin < off)
                            receiver.receivePlain(rawSMLBody.substring(begin, off));
                    } else {
                        if (closeTagEnd + 1 < off)
                            receiver.receivePlain(rawSMLBody.substring(closeTagEnd+1, off));
                    }
                    openTagBegin = off;
                    closeTagBegin = -1;
                    begin = off + 1;
                } else {
                    // ok, we are at the end of the tag, process it
                    closeTagBegin = off;
                    while ( (c != TAG_END) && (off < len) ) {
                        off++;
                        c = rawSMLBody.charAt(off);
                    }
                    parseTag(rawSMLBody, openTagBegin, openTagEnd, closeTagBegin, off, receiver);
                    begin = off + 1;
                    openTagBegin = -1;
                    openTagEnd = -1;
                    closeTagBegin = -1;
                    closeTagEnd = -1;
                }
            } else if (c == TAG_END) {
                if ( (openTagBegin > 0) && (closeTagBegin < 0) ) {
                    openTagEnd = off;
                } else if ( (off + 1 < len) && (TAG_END == rawSMLBody.charAt(off+1))) {
                    if (begin < off)
                        receiver.receivePlain(rawSMLBody.substring(begin, off));
                    receiver.receiveRightBracket();
                    off += 2;
                    begin = off;
                    continue;
                }
            } else if (c == LT) {
                if (begin < off)
                    receiver.receivePlain(rawSMLBody.substring(begin, off));
                receiver.receiveLT();
                off++;
                begin = off;
                continue;
            } else if (c == GT) {
                if (begin < off)
                    receiver.receivePlain(rawSMLBody.substring(begin, off));
                receiver.receiveGT();
                off++;
                begin = off;
                continue;
            }
            
            off++;
        }
        if ( (off >= len) && (openTagBegin < 0) ) {
            if (closeTagEnd < 0) {
                if (begin < off)
                    receiver.receivePlain(rawSMLBody.substring(begin, off));
            } else {
                if (closeTagEnd + 1 < off)
                    receiver.receivePlain(rawSMLBody.substring(closeTagEnd+1, off));
            }
        }
    }
    
    private void parseTag(String source, int openTagBegin, int openTagEnd, int closeTagBegin, int closeTagEnd, EventReceiver receiver) {
        String tagName = getTagName(source, openTagBegin+1);
        Map attributes = getAttributes(source, openTagBegin+1+tagName.length(), openTagEnd);
        String body = null;
        if (openTagEnd + 1 >= closeTagBegin)
            body = "";
        else
            body = source.substring(openTagEnd+1, closeTagBegin);
        
        //System.out.println("Receiving tag [" + tagName + "] w/ open [" + source.substring(openTagBegin+1, openTagEnd) 
        //                   + "], close [" + source.substring(closeTagBegin+1, closeTagEnd) + "] body [" 
        //                   + body + "] attributes: " + attributes);
        parseTag(tagName, attributes, body, receiver);
    }
    
    private static final String T_BOLD = "b";
    private static final String T_ITALIC = "i";
    private static final String T_UNDERLINE = "u";
    private static final String T_CUT = "cut";
    private static final String T_IMAGE = "img";
    private static final String T_QUOTE = "quote";
    private static final String T_CODE = "code";
    private static final String T_BLOG = "blog";
    private static final String T_LINK = "link";
    private static final String T_ADDRESS = "address";
    private static final String T_H1 = "h1";
    private static final String T_H2 = "h2";
    private static final String T_H3 = "h3";
    private static final String T_H4 = "h4";
    private static final String T_H5 = "h5";
    private static final String T_HR = "hr";
    private static final String T_PRE = "pre";
    private static final String T_ATTACHMENT = "attachment";
    
    private static final String P_ATTACHMENT = "attachment";
    private static final String P_WHO_QUOTED = "author";
    private static final String P_QUOTE_LOCATION = "location";
    private static final String P_CODE_LOCATION = "location";
    private static final String P_BLOG_NAME = "name";
    private static final String P_BLOG_HASH = "bloghash";
    private static final String P_BLOG_TAG = "blogtag";
    private static final String P_BLOG_ENTRY = "blogentry";
    private static final String P_LINK_LOCATION = "location";
    private static final String P_LINK_SCHEMA = "schema";
    private static final String P_ADDRESS_NAME = "name";
    private static final String P_ADDRESS_LOCATION = "location";
    private static final String P_ADDRESS_SCHEMA = "schema";
    private static final String P_ATTACHMENT_ID = "id";
    
    private void parseTag(String tagName, Map attr, String body, EventReceiver receiver) {
        tagName = tagName.toLowerCase();
        if (T_BOLD.equals(tagName)) {
            receiver.receiveBold(body);
        } else if (T_ITALIC.equals(tagName)) {
            receiver.receiveItalic(body);
        } else if (T_UNDERLINE.equals(tagName)) {
            receiver.receiveUnderline(body);
        } else if (T_CUT.equals(tagName)) {
            receiver.receiveCut(body);
        } else if (T_IMAGE.equals(tagName)) {
            receiver.receiveImage(body, getInt(P_ATTACHMENT, attr));
        } else if (T_QUOTE.equals(tagName)) {
            receiver.receiveQuote(body, getString(P_WHO_QUOTED, attr), getSchema(P_QUOTE_LOCATION, attr), getLocation(P_QUOTE_LOCATION, attr));
        } else if (T_CODE.equals(tagName)) {
            receiver.receiveCode(body, getSchema(P_CODE_LOCATION, attr), getLocation(P_CODE_LOCATION, attr));
        } else if (T_BLOG.equals(tagName)) {
            List locations = new ArrayList();
            int i = 0;
            while (true) {
                String s = getString("archive" + i, attr);
                if (s != null)
                    locations.add(new SafeURL(s));
                else
                    break;
                i++;
            }
            receiver.receiveBlog(getString(P_BLOG_NAME, attr), getString(P_BLOG_HASH, attr), getString(P_BLOG_TAG, attr), 
                                 getLong(P_BLOG_ENTRY, attr), locations, body);
        } else if (T_LINK.equals(tagName)) {
            receiver.receiveLink(getString(P_LINK_SCHEMA, attr), getString(P_LINK_LOCATION, attr), body);
        } else if (T_ADDRESS.equals(tagName)) {
            receiver.receiveAddress(getString(P_ADDRESS_NAME, attr), getString(P_ADDRESS_SCHEMA, attr), getString(P_ADDRESS_LOCATION, attr), body);
        } else if (T_H1.equals(tagName)) {
            receiver.receiveH1(body);
        } else if (T_H2.equals(tagName)) {
            receiver.receiveH2(body);
        } else if (T_H3.equals(tagName)) {
            receiver.receiveH3(body);
        } else if (T_H4.equals(tagName)) {
            receiver.receiveH4(body);
        } else if (T_H5.equals(tagName)) {
            receiver.receiveH5(body);
        } else if (T_HR.equals(tagName)) {
            receiver.receiveHR();
        } else if (T_PRE.equals(tagName)) {
            receiver.receivePre(body);
        } else if (T_ATTACHMENT.equals(tagName)) {
            receiver.receiveAttachment((int)getLong(P_ATTACHMENT_ID, attr), body);
        } else {
            System.out.println("need to learn how to parse the tag [" + tagName + "]");
        }
    }
    
    private String getString(String param, Map attributes) { return (String)attributes.get(param); }
    private String getSchema(String param, Map attributes) {
        String url = getString(param, attributes);
        if (url != null) {
            SafeURL u = new SafeURL(url);
            return u.getSchema();
        } else {
            return null;
        }
    }
    
    private String getLocation(String param, Map attributes) {
        String url = getString(param, attributes);
        if (url != null) {
            SafeURL u = new SafeURL(url);
            return u.getLocation();
        } else {
            return null;
        }
    }
    
    private int getInt(String attributeName, Map attributes) {
        String val = (String)attributes.get(attributeName.toLowerCase());
        if (val != null) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException nfe) {
                nfe.printStackTrace();
                return -1;
            }
        } else {
            return -1;
        }
    }
    
    private long getLong(String attributeName, Map attributes) {
        String val = (String)attributes.get(attributeName.toLowerCase());
        if (val != null) {
            try {
                return Long.parseLong(val.trim());
            } catch (NumberFormatException nfe) {
                nfe.printStackTrace();
                return -1;
            }
        } else {
            return -1;
        }
    }
    
    private String getTagName(String source, int nameStart) {
        int off = nameStart;
        while (true) {
            char c = source.charAt(off);
            if ( (c == TAG_END) || (WHITESPACE.indexOf(c) >= 0) )
                return source.substring(nameStart, off);
            off++;
        }
    }
    private Map getAttributes(String source, int attributesStart, int openTagEnd) {
        Map rv = new HashMap();
        int off = attributesStart;
        int nameStart = -1;
        int nameEnd = -1;
        int valStart = -1;
        int valEnd = -1;
        while (true) {
            char c = source.charAt(off);
            if ( (c == TAG_END) || (off >= openTagEnd) )
                break;
            if (WHITESPACE.indexOf(c) < 0) {
                if (nameStart < 0) {
                    nameStart = off;
                } else if (c == EQ) {
                    if (nameEnd < 0)
                        nameEnd = off;
                } else if ( (c == QUOTE) || (c == DQUOTE) ) {
                    if (valStart < 0) {
                        valStart = off;
                    } else {
                        valEnd = off;
                        
                        String name = source.substring(nameStart, nameEnd);
                        String val = source.substring(valStart+1, valEnd);
                        rv.put(name.trim(), val.trim());
                        nameStart = -1;
                        nameEnd = -1;
                        valStart = -1;
                        valEnd = -1;
                    }
                }
            }
            off++;
        }
        return rv;
    }
    
    public interface EventReceiver {
        public void receiveHeader(String header, String value);
        public void receiveLink(String schema, String location, String text);
        /** @param blogArchiveLocations list of SafeURL */
        public void receiveBlog(String name, String blogKeyHash, String blogPath, long blogEntryId, 
                                List blogArchiveLocations, String anchorText);
        public void receiveArchive(String name, String description, String locationSchema, String location, 
                                   String postingKey, String anchorText);
        public void receiveImage(String alternateText, int attachmentId);
        public void receiveAddress(String name, String schema, String location, String anchorText);
        public void receiveAttachment(int id, String anchorText);
        public void receiveBold(String text);
        public void receiveItalic(String text);
        public void receiveUnderline(String text);
        public void receiveH1(String text);
        public void receiveH2(String text);
        public void receiveH3(String text);
        public void receiveH4(String text);
        public void receiveH5(String text);
        public void receivePre(String text);
        public void receiveHR();
        public void receiveQuote(String text, String whoQuoted, String quoteLocationSchema, String quoteLocation);
        public void receiveCode(String text, String codeLocationSchema, String codeLocation);
        public void receiveCut(String summaryText);
        public void receivePlain(String text);
        public void receiveNewline();
        public void receiveLT();
        public void receiveGT();
        public void receiveLeftBracket();
        public void receiveRightBracket();
        public void receiveBegin();
        public void receiveEnd();
        public void receiveHeaderEnd();
    }
    
    public static void main(String args[]) {
        test(null);
        test("");
        test("A: B");
        test("A: B\n");
        test("A: B\nC: D");
        test("A: B\nC: D\n");
        test("A: B\nC: D\n\n");
        
        test("A: B\nC: D\n\nblah");
        test("A: B\nC: D\n\nblah[[");
        test("A: B\nC: D\n\nblah]]");
        test("A: B\nC: D\n\nblah]]blah");
        test("A: B\nC: D\n\nfoo[a]b[/a]bar");
        test("A: B\nC: D\n\nfoo[a]b[/a]bar[b][/b]");
        test("A: B\nC: D\n\nfoo[a]b[/a]bar[b][/b]baz");
        
        test("A: B\nC: D\n\n<a href=\"http://odci.gov\">hi</a>");
        
        test("A: B\n\n[a b='c']d[/a]");
        test("A: B\n\n[a b='c' d='e' f='g']h[/a]");
        test("A: B\n\n[a b='c' d='e' f='g']h[/a][a b='c' d='e' f='g']h[/a][a b='c' d='e' f='g']h[/a]");
        
        test("A: B\n\n[a   b='c' ]d[/a]");
        test("A: B\n\n[a   b=\"c\" ]d[/a]");
        
        test("A: B\n\n[b]This[/b] is [i]special[/i][cut]why?[/cut][u]because I say so[/u].\neven if you dont care");
    }
    private static void test(String rawSML) {
        SMLParser parser = new SMLParser();
        parser.parse(rawSML, new EventReceiverImpl());
    }
}
