package i2p.susi.webmail;
import java.io.*;
import java.util.*;
/** Encrypted profile version gate. Newer profiles requiring MFA fail closed in this version. */
final class AccountSecurity {
    private final MailVault vault;
    AccountSecurity(MailVault vault) { this.vault = vault; }
    static byte[] initialProfile() throws IOException { Properties p = new Properties(); p.setProperty("version","1"); p.setProperty("mfa.enabled","false"); ByteArrayOutputStream out = new ByteArrayOutputStream(); p.store(out,null); return out.toByteArray(); }
    boolean mfaEnabled() throws IOException {
        byte[] bytes = vault.read(vault.directory.resolve("security.profile"), 256*1024);
        try { Properties p = new Properties(); p.load(new ByteArrayInputStream(bytes));
            if (!"1".equals(p.getProperty("version"))) throw new IOException("Unsupported account security profile");
            if (!"false".equals(p.getProperty("mfa.enabled"))) throw new IOException("This account requires a version with authenticator support");
            return false;
        } finally { Arrays.fill(bytes,(byte)0); }
    }
    boolean verifyLogin(String code, long now) throws IOException { mfaEnabled(); return true; }
}
