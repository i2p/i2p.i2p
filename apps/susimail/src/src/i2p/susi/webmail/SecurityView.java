package i2p.susi.webmail;

import java.io.IOException;
import java.util.List;
import net.i2p.data.DataHelper;

/** Trusted account controls, rendered outside message HTML and without remote resources. */
final class SecurityView {
    static String render(String user, String nonce, AccountSecurity settings, String setupKey,
            List<String> recovery, String info, String error, boolean dark) throws IOException {
        boolean enabled = settings.mfaEnabled();
        List<String> blocked = java.util.Collections.emptyList();
        StringBuilder html = new StringBuilder(10000);
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Mail security</title><link rel=\"stylesheet\" href=\"/susimail/themes/security.css\"></head><body class=\"security-page\" data-mail-theme=\"")
            .append(dark ? "dark" : "light").append("\"><main class=\"security-shell\"><header class=\"security-heading\"><div><p class=\"eyebrow\">SUSIMAIL · ACCOUNT PROTECTION</p><h1>Mail security</h1><p>Control access, reduce unwanted mail, and review your protections.</p></div><a class=\"secondary-action\" href=\"/susimail/\">Return to inbox</a></header>");
        if (info != null && !info.isEmpty()) html.append("<p class=\"notice notice-success\" role=\"status\">").append(escape(info)).append("</p>");
        if (error != null && !error.isEmpty()) html.append("<p class=\"notice notice-error\" role=\"alert\">").append(escape(error)).append("</p>");
        html.append("<section class=\"security-summary\" aria-label=\"Account protection status\"><div><span class=\"status-dot\"></span><strong>Saved mail encrypted</strong><span>Headers, messages, drafts and attachments</span></div><div><strong>")
            .append(enabled ? "Two-factor protection on" : "Two-factor protection available")
            .append("</strong><span>").append(enabled ? "An authenticator or recovery code is required at login" : "Optional · add an authenticator when you are ready")
            .append("</span></div><div><strong>").append(blocked.size()).append(" blocked entries</strong><span>Private rules for ")
            .append(escape(user)).append("</span></div></section><div class=\"security-grid\">");

        html.append("<section class=\"security-card\"><p class=\"eyebrow\">SIGN-IN</p><h2>Authenticator protection</h2><p>Use a six-digit authenticator code in addition to your password. This protects this SusiMail client.</p>");
        if (!enabled) {
            if (setupKey != null) {
                html.append("<div class=\"notice\"><strong>Add an account manually in your authenticator</strong><p>Choose a time-based code with six digits and a 30-second interval. Enter this key:</p><code class=\"setup-key\">")
                    .append(escape(setupKey)).append("</code><p>Enter a code below to finish. Protection stays off until confirmation succeeds.</p></div>");
                form(html, nonce);
                input(html, "Encryption password", "password", "securityPassword", "current-password", true);
                input(html, "Authenticator code", "text", "securityCode", "one-time-code", true);
                action(html, "enableTOTP", "Confirm and enable", true);
                action(html, "cancelTOTP", "Cancel setup", false);
                html.append("</form>");
            } else {
                form(html, nonce);
                input(html, "Encryption password", "password", "securityPassword", "current-password", true);
                action(html, "startTOTP", "Set up an authenticator", true);
                html.append("</form>");
            }
        } else {
            html.append("<p class=\"status-badge\">Enabled · ").append(settings.recoveryRemaining()).append(" recovery codes remaining</p><p>Each accepted authenticator code works once. Wait for the next code if you have just used one.</p>");
            form(html, nonce);
            input(html, "Encryption password", "password", "securityPassword", "current-password", true);
            input(html, "Current authenticator or recovery code", "text", "securityCode", "one-time-code", true);
            action(html, "regenerateRecovery", "Replace recovery codes", true);
            action(html, "disableTOTP", "Turn off two-factor protection", false);
            html.append("</form>");
        }
        html.append("</section>");

        html.append("<section class=\"security-card\"><p class=\"eyebrow\">SAVED MAIL</p><h2>Encryption password</h2><p>Your email password protects saved mail when encryption is first set up. Changing this password keeps the same encrypted messages and recovery settings.</p>");
        form(html, nonce);
        input(html, "Current encryption password", "password", "securityPassword", "current-password", true);
        if (enabled) input(html, "Authenticator or recovery code", "text", "securityCode", "one-time-code", true);
        input(html, "New encryption password", "password", "newEncryptionPassword", "new-password", true);
        input(html, "Repeat new encryption password", "password", "repeatEncryptionPassword", "new-password", true);
        html.append("<p class=\"field-hint\">Use your current email password here if you want one password for sign-in and saved mail. Keep an intact backup: a forgotten encryption password cannot be recovered from authenticator codes.</p>");
        action(html, "changeEncryptionPassword", "Update encryption password", true);
        html.append("</form></section>");

        html.append("</div>");

        if (recovery != null && !recovery.isEmpty()) {
            html.append("<section class=\"security-card recovery-card\" role=\"status\"><p class=\"eyebrow\">SAVE THESE NOW</p><h2>Your new recovery codes</h2><p>Each code can replace an authenticator code once. Store them separately from this device. These values are shown once; only encrypted hashes are saved.</p><ul class=\"recovery-codes\">");
            for (String code : recovery) html.append("<li><code>").append(escape(code)).append("</code></li>");
            html.append("</ul></section>");
        }
        return html.append("<footer class=\"security-footer\">SusiMail · private local controls · no remote fonts or security analytics</footer></main></body></html>").toString();
    }

    private static void form(StringBuilder html, String nonce) {
        html.append("<form method=\"post\" action=\"/susimail/?security=1\"><input type=\"hidden\" name=\"susiNonce\" value=\"").append(escape(nonce)).append("\">");
    }
    private static void input(StringBuilder html, String label, String type, String name, String autocomplete, boolean required) {
        html.append("<label>").append(escape(label)).append("<input type=\"").append(type).append("\" name=\"").append(name)
            .append("\" autocomplete=\"").append(autocomplete).append("\" maxlength=\"").append(type.equals("password") ? 1024 : 254)
            .append("\" spellcheck=\"false\"").append(required ? " required" : "").append("></label>");
    }
    private static void action(StringBuilder html, String value, String label, boolean primary) {
        html.append("<button type=\"submit\" name=\"securityAction\" value=\"").append(value).append("\" class=\"")
            .append(primary ? "primary-action" : "secondary-action").append(value.equals("cancelTOTP") ? "\" formnovalidate>" : "\">")
            .append(escape(label)).append("</button>");
    }
    private static String escape(String value) { return DataHelper.escapeHTML(value == null ? "" : value); }
}
