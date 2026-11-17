package i2p.susi.webmail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import i2p.susi.util.Buffer;
import i2p.susi.webmail.encoding.EncodingFactory;

/** Bounded raw header parsing. Field boundaries are established before RFC 2047 decoding. */
final class MailHeaders {
    static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_FIELDS = 512;
    private static final int MAX_LINE = 32 * 1024;
    private static final Set<String> SINGLETONS = Set.of("from", "sender", "reply-to", "subject", "date",
        "message-id", "mime-version", "content-type", "content-transfer-encoding");

    static final class Field {
        final String name;
        final String raw;
        Field(String raw) {
            this.raw = raw;
            name = raw.substring(0, raw.indexOf(':')).toLowerCase(Locale.US);
        }
        String value() { return raw.substring(raw.indexOf(':') + 1).replaceAll("\r\n[ \t]+", " ").trim(); }
    }

    final List<Field> fields;
    final String raw;

    private MailHeaders(List<Field> fields, String raw) {
        this.fields = fields;
        this.raw = raw;
    }

    /** Input ends before the CRLFCR separator, as supplied by EOFOnMatchInputStream. */
    static MailHeaders read(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        byte[] buffer = new byte[4096];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (bytes.size() + count > MAX_BYTES)
                throw new IOException("Message headers exceed the size limit");
            bytes.write(buffer, 0, count);
        }
        String raw = bytes.toString(StandardCharsets.ISO_8859_1);
        List<Field> fields = new ArrayList<Field>();
        StringBuilder field = null;
        for (String line : raw.split("\r\n", -1)) {
            if (line.length() > MAX_LINE)
                throw new IOException("Message header line exceeds the size limit");
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if ((c < 32 && c != '\t') || c == 127)
                    throw new IOException("Invalid control byte in message headers");
            }
            if (line.isEmpty()) continue;
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                if (field == null) throw new IOException("Header continuation without a field");
                field.append(line).append("\r\n");
            } else {
                if (field != null) fields.add(new Field(field.toString()));
                int colon = line.indexOf(':');
                if (colon <= 0) throw new IOException("Invalid message header field");
                for (int i = 0; i < colon; i++) {
                    char c = line.charAt(i);
                    if (c < 33 || c > 126) throw new IOException("Invalid message header name");
                }
                if (fields.size() >= MAX_FIELDS) throw new IOException("Too many message headers");
                field = new StringBuilder(line).append("\r\n");
            }
        }
        if (field != null) fields.add(new Field(field.toString()));
        return new MailHeaders(fields, raw);
    }

    String[] decodedLines() throws IOException {
        String[] lines = new String[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            // Only values may contain encoded words. Never decode a field name or reparse decoded lines.
            Buffer decoded = EncodingFactory.getEncoding("HEADERLINE").decode("Value: " + field.value() + "\r\n");
            String value;
            try (InputStream text = decoded.getInputStream()) {
                value = new String(text.readAllBytes(), StandardCharsets.UTF_8);
            } finally { decoded.readComplete(true); }
            if (!value.startsWith("Value: ")) throw new IOException("Invalid decoded message header");
            value = value.substring(7);
            if (value.endsWith("\r\n")) value = value.substring(0, value.length() - 2);
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0)
                throw new IOException("Encoded header contains a control character");
            lines[i] = field.name + ": " + value.trim();
        }
        return lines;
    }

    String ambiguousField() {
        Set<String> seen = new HashSet<String>();
        for (Field field : fields) {
            if (SINGLETONS.contains(field.name) && !seen.add(field.name)) return field.name;
        }
        return count("from") != 1 ? "from" : null;
    }

    int count(String name) {
        int count = 0;
        for (Field field : fields) if (field.name.equals(name)) count++;
        return count;
    }
}
