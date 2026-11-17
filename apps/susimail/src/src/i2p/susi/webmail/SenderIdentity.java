package i2p.susi.webmail;

import java.net.IDN;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative single-mailbox parsing for automatic replies and sender presentation. */
final class SenderIdentity {
    private static final Pattern ATOM = Pattern.compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*");
    final String address;
    final String domain;

    private SenderIdentity(String local, String domain) {
        this.address = local + '@' + domain;
        this.domain = domain;
    }

    static SenderIdentity parse(String value) {
        if (value == null || value.length() > 2048 || hasInvisible(value)) return null;
        value = value.trim();
        int left = -1, right = -1;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (quoted && c == '\\') { escaped = true; continue; }
            if (c == '"') { quoted = !quoted; continue; }
            if (!quoted) {
                if (c == '<') { if (left >= 0) return null; left = i; }
                else if (c == '>') { if (right >= 0) return null; right = i; }
                else if ((c == ',' || c == ';' || c == '(' || c == ')') && left < 0) return null;
            }
        }
        if (quoted || escaped) return null;
        if (left >= 0 || right >= 0) {
            if (left < 0 || right <= left || !value.substring(right + 1).trim().isEmpty()) return null;
            value = value.substring(left + 1, right);
        }
        int at = value.lastIndexOf('@');
        if (at <= 0 || at == value.length() - 1) return null;
        String local = value.substring(0, at);
        if (local.length() > 64 || !ATOM.matcher(local).matches()) return null;
        String domain;
        try { domain = IDN.toASCII(value.substring(at + 1), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.US); }
        catch (IllegalArgumentException invalid) { return null; }
        if (!validDomain(domain)) return null;
        return new SenderIdentity(local, domain);
    }

    static boolean validDomain(String domain) {
        if (domain.length() > 253 || domain.indexOf('.') < 1 || domain.endsWith(".")) return false;
        for (String label : domain.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) return false;
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '-') return false;
            }
        }
        return true;
    }

    static boolean hasInvisible(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || c == 0x2028 || c == 0x2029)
                return true;
        }
        return false;
    }

    /** Make invisible direction/format controls visible before HTML escaping. */
    static String display(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || c == 0x2028 || c == 0x2029)
                result.append(String.format(Locale.US, "[U+%04X]", (int) c));
            else result.append(c);
        }
        return result.toString();
    }
}
