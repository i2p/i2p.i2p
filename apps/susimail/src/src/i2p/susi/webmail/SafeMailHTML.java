package i2p.susi.webmail;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;

/** Rebuild message HTML from semantic tags; author fonts, CSS and active markup never survive. */
final class SafeMailHTML {
    static final int MAX_HTML = 2 * 1024 * 1024;
    private static final Set<String> TAGS = Set.of("p", "br", "div", "span", "b", "strong", "em", "i", "u", "s",
        "ul", "ol", "li", "blockquote", "pre", "code", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
        "table", "thead", "tbody", "tfoot", "tr", "td", "th", "a");
    private static final Set<String> DROP = Set.of("script", "style", "head", "title", "iframe", "object", "applet", "template");

    static byte[] render(MailPart part, String folder) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        OutputStream bounded = new OutputStream() {
            @Override public void write(int value) throws IOException {
                if (bytes.size() >= MAX_HTML) throw new IOException("HTML message exceeds the safe display limit");
                bytes.write(value);
            }
            @Override public void write(byte[] data, int offset, int length) throws IOException {
                if (length > MAX_HTML - bytes.size()) throw new IOException("HTML message exceeds the safe display limit");
                bytes.write(data, offset, length);
            }
        };
        part.decode(0, new i2p.susi.util.OutputStreamBuffer(bounded));
        java.nio.charset.Charset charset = StandardCharsets.UTF_8;
        try { if (part.charset != null) charset = java.nio.charset.Charset.forName(part.charset); }
        catch (IllegalArgumentException unsupported) { /* Safely replace undecodable display text. */ }
        return sanitize(bytes.toString(charset), part.uidl, folder).getBytes(StandardCharsets.UTF_8);
    }

    static boolean safeInlineType(String type) {
        return type != null && Set.of("text/html", "text/plain", "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp").contains(type);
    }

    static String sanitize(String html, String uidl, String folder) throws IOException {
        if (html.length() > MAX_HTML) throw new IOException("HTML message exceeds the safe display limit");
        StringBuilder out = new StringBuilder(Math.min(html.length() + 512, MAX_HTML));
        out.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><link rel=\"stylesheet\" href=\"/susimail/themes/mail-content.css\"></head><body>");
        out.append("<div class=\"safe-message-note\">Custom fonts, hidden formatting, forms and remote content are disabled. Links open a destination check.</div>");
        HTMLEditorKit.ParserCallback callback = new HTMLEditorKit.ParserCallback() {
            final Deque<String> opened = new ArrayDeque<String>();
            final Deque<String> emitted = new ArrayDeque<String>();
            int suppressed, elements, images;

            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                String name = tag.toString().toLowerCase(Locale.US);
                if (++elements > 20000 || opened.size() > 64) throw new IllegalArgumentException("HTML structure limit exceeded");
                if (DROP.contains(name)) { suppressed++; return; }
                if (suppressed > 0 || !TAGS.contains(name)) return;
                if (name.equals("a")) {
                    Object href = attributes.getAttribute(HTML.Attribute.HREF);
                    String destination = href == null ? null : safeURL(href.toString());
                    if (destination == null) { out.append("<span>"); opened.push("a"); emitted.push("span"); return; }
                    out.append("<a data-safe-link=\"1\" href=\"/susimail/?openLink=")
                        .append(escape(URLEncoder.encode(destination, StandardCharsets.UTF_8))).append("\" rel=\"noreferrer nofollow\">");
                } else {
                    out.append('<').append(name);
                    if (name.equals("td") || name.equals("th")) {
                        for (HTML.Attribute attribute : new HTML.Attribute[] { HTML.Attribute.COLSPAN, HTML.Attribute.ROWSPAN }) {
                            Object value = attributes.getAttribute(attribute);
                            if (value != null && value.toString().matches("[1-9]|10"))
                                out.append(' ').append(attribute).append("=\"").append(value).append('"');
                        }
                    }
                    out.append('>');
                }
                if (!name.equals("br") && !name.equals("hr")) { opened.push(name); emitted.push(name); }
            }

            @Override public void handleEndTag(HTML.Tag tag, int position) {
                String name = tag.toString().toLowerCase(Locale.US);
                if (DROP.contains(name)) { if (suppressed > 0) suppressed--; return; }
                if (suppressed > 0 || !opened.contains(name)) return;
                while (!opened.isEmpty()) {
                    String closed = opened.pop();
                    out.append("</").append(emitted.pop()).append('>');
                    if (closed.equals(name)) break;
                }
            }

            @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                String name = tag.toString().toLowerCase(Locale.US);
                if (++elements > 20000) throw new IllegalArgumentException("HTML structure limit exceeded");
                if (suppressed > 0) return;
                if (name.equals("br") || name.equals("hr")) out.append('<').append(name).append('>');
                else if (name.equals("img")) {
                    if (++images > 20) return;
                    Object source = attributes.getAttribute(HTML.Attribute.SRC);
                    if (source == null || !source.toString().regionMatches(true, 0, "cid:", 0, 4)) return;
                    String cid = source.toString().substring(4);
                    if (!cid.matches("[A-Za-z0-9._@+%-]{1,256}")) return;
                    Object alt = attributes.getAttribute(HTML.Attribute.ALT);
                    out.append("<img src=\"/susimail/?msg=").append(Base64.encode(uidl)).append("&amp;cid=")
                        .append(escape(URLEncoder.encode(cid, StandardCharsets.UTF_8)))
                        .append("&amp;folder=").append(escape(URLEncoder.encode(folder, StandardCharsets.UTF_8)))
                        .append("\" alt=\"").append(escape(visible(alt == null ? "Inline image" : alt.toString()))).append("\">");
                }
            }

            @Override public void handleText(char[] text, int position) {
                if (suppressed == 0) out.append(escape(visible(new String(text))));
            }
        };
        try { new ParserDelegator().parse(new StringReader(html), callback, true); }
        catch (RuntimeException malformed) { throw new IOException("HTML message could not be simplified safely", malformed); }
        out.append("</body></html>");
        return out.toString();
    }

    static String safeURL(String value) {
        if (value == null || value.length() > 2048 || SenderIdentity.hasInvisible(value)) return null;
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getRawUserInfo() != null || uri.getHost() == null || uri.getPort() > 65535 || uri.getPort() == 0)
                return null;
            String host = IDN.toASCII(uri.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.US);
            // Mail links cannot target local devices or literal addresses through the confirmation flow.
            if (!SenderIdentity.validDomain(host) || host.matches("[0-9.]+") || host.endsWith(".localhost")
                    || host.endsWith(".local") || host.endsWith(".internal") || host.equals("localhost")) return null;
            return new URI(scheme.toLowerCase(Locale.US), null, host, uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment()).toASCIIString();
        } catch (IllegalArgumentException | URISyntaxException invalid) { return null; }
    }

    private static String visible(String text) {
        StringBuilder visible = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.getType(c) == Character.FORMAT || (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
                visible.append(String.format(Locale.US, "[U+%04X]", (int) c));
            else visible.append(c);
        }
        return visible.toString();
    }

    private static String escape(String text) { return DataHelper.escapeHTML(text); }
}
