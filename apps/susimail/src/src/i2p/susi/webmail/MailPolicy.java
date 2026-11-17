package i2p.susi.webmail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import i2p.susi.util.OutputStreamBuffer;

/** Explainable, bounded local signals; domain authentication alone does not make content benign. */
final class MailPolicy {
    static final class Signals {
        int score;
        final List<String> reasons = new ArrayList<String>();
        void add(int points, String reason) { score += points; reasons.add(reason); }
    }
    static final class Result {
        boolean blocked, spam;
        int score;
        final List<String> reasons = new ArrayList<String>();
    }
    private static final class SampleComplete extends IOException { private static final long serialVersionUID = 1L; }

    static Signals inspect(MailPart root) {
        Signals signals = new Signals();
        if (root == null) return signals;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        Deque<MailPart> remaining = new ArrayDeque<MailPart>();
        remaining.add(root);
        int parts = 0;
        while (!remaining.isEmpty() && parts < 8 && bytes.size() < 32768) {
            MailPart part = remaining.removeFirst();
            if (part.multipart || part.message) { remaining.addAll(part.parts); continue; }
            if (!("text/plain".equals(part.type) || "text/html".equals(part.type) || part.type == null)) continue;
            parts++;
            try {
                part.decode(0, new OutputStreamBuffer(new OutputStream() {
                    @Override public void write(int value) throws IOException {
                        if (bytes.size() >= 32768) throw new SampleComplete();
                        bytes.write(value);
                    }
                    @Override public void write(byte[] data, int offset, int length) throws IOException {
                        int accepted = Math.min(length, 32768 - bytes.size());
                        bytes.write(data, offset, accepted);
                        if (accepted < length) throw new SampleComplete();
                    }
                }));
            } catch (SampleComplete enough) { break; }
            catch (IOException undecodable) { signals.add(3, "A message part could not be inspected."); }
        }
        String text = bytes.toString(StandardCharsets.UTF_8).toLowerCase(Locale.US);
        if (text.contains("<form") && (text.contains("password") || text.contains("credential")))
            signals.add(7, "The message contains a credential collection form.");
        if (text.contains("javascript:") || text.contains("data:text/html"))
            signals.add(6, "The message contains an active or embedded HTML link.");
        if (text.contains("@font-face") && (text.contains("unicode-range") || text.contains("font-variant")))
            signals.add(5, "Custom font rules may conceal the displayed message.");
        if (text.matches("(?s).*font-size\\s*:\\s*0(?:px|pt|em|[; }]).*") || text.matches("(?s).*opacity\\s*:\\s*0(?:[; }]).*"))
            signals.add(3, "The message attempts to hide text with formatting.");
        if ((text.contains("verify your password") || text.contains("confirm your password")) && text.contains("http"))
            signals.add(4, "The message asks for a password at a linked destination.");
        if ((text.contains("urgent") || text.contains("immediately")) && (text.contains("wire transfer") || text.contains("gift card") || text.contains("seed phrase")))
            signals.add(4, "Urgent financial or recovery-secret requests need review.");
        return signals;
    }

    static Result assess(Mail mail, AccountSecurity settings, Signals body) throws IOException {
        Result result = new Result();
        result.score = body.score;
        result.reasons.addAll(body.reasons);
        result.blocked = settings.isBlocked(mail.getSenderAddress());
        if (result.blocked) { result.score = Math.max(result.score, 100); result.reasons.add("The sender matches your private blocklist."); }
        if (mail.isReplyRedirected()) { result.score += 2; result.reasons.add("The reply address differs from the sender address."); }
        if (SenderIdentity.hasInvisible(mail.sender) || SenderIdentity.hasInvisible(mail.subject)) {
            result.score += 3; result.reasons.add("Hidden formatting characters appear in the message identity.");
        }
        String subject = mail.subject.toLowerCase(Locale.US);
        if ((subject.contains("urgent") || subject.contains("suspended")) && (subject.contains("password") || subject.contains("account"))) {
            result.score += 3; result.reasons.add("The subject combines account pressure with urgency.");
        }
        result.spam = result.blocked || (settings.spamEnabled() && !settings.isReleased(mail.uidl) && result.score >= settings.spamThreshold());
        return result;
    }
}
