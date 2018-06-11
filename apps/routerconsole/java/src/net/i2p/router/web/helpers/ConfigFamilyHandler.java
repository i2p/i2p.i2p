package net.i2p.router.web.helpers;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.crypto.CertUtil;
import net.i2p.crypto.KeyStoreUtil;
import net.i2p.router.crypto.FamilyKeyCrypto;
import net.i2p.router.web.FormHandler;
import net.i2p.util.SecureDirectory;

/**
 *  @since 0.9.25
 */
public class ConfigFamilyHandler extends FormHandler {
    
    @Override
    protected void processForm() {

        if (_action.equals(_t("Create Family"))) {
            String family = getJettyString("family");
            String old = _context.getProperty(FamilyKeyCrypto.PROP_FAMILY_NAME);
            if (family == null || family.trim().length() <= 0) {
                addFormError(_t("You must enter a family name"));
            } else if (old != null) {
                addFormError("Family already configured: " + family);
            } else if (family.contains("/") || family.contains("\\")) {
                addFormError("Bad characters in Family: " + family);
            } else if (family.length() > 32) {
                // let's enforce some sanity
                addFormError("Family too long, 32 chars max: " + family);
            } else if (_context.router().saveConfig(FamilyKeyCrypto.PROP_FAMILY_NAME, family.trim())) {
                addFormNotice(_t("Configuration saved successfully."));
                addFormError(_t("Restart required to take effect"));
            } else {
                addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs"));
            }
        } else if (_action.equals(_t("Join Family"))) {
            InputStream in = _requestWrapper.getInputStream("file");
            try {
                // non-null but zero bytes if no file entered, don't know why
                if (in == null || in.available() <= 0) {
                    addFormError(_t("You must enter a file"));
                    return;
                }
                // load data
                PrivateKey pk = CertUtil.loadPrivateKey(in);
                List<X509Certificate> certs = CertUtil.loadCerts(in);
                String family = CertUtil.getSubjectValue(certs.get(0), "CN");
                if (family == null) {
                    addFormError("Bad certificate - No Subject CN");
                }
                if (family.endsWith(FamilyKeyCrypto.CN_SUFFIX) && family.length() > FamilyKeyCrypto.CN_SUFFIX.length())
                    family = family.substring(0, family.length() - FamilyKeyCrypto.CN_SUFFIX.length());
                // store to keystore
                File ks = new SecureDirectory(_context.getConfigDir(), "keystore");
                if (!ks.exists())
                    ks.mkdirs();
                ks = new File(ks, FamilyKeyCrypto.KEYSTORE_PREFIX + family + FamilyKeyCrypto.KEYSTORE_SUFFIX);
                String keypw = KeyStoreUtil.randomString();
                KeyStoreUtil.storePrivateKey(ks, KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD, family, keypw, pk, certs);
                // store certificate
                File cf = new SecureDirectory(_context.getConfigDir(), "certificates");
                if (!cf.exists())
                    cf.mkdirs();
                cf = new SecureDirectory(cf, "family");
                if (!ks.exists())
                    ks.mkdirs();
                cf = new File(cf, family + FamilyKeyCrypto.CERT_SUFFIX);
                // ignore failure
                KeyStoreUtil.exportCert(ks, KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD, family, cf);
                // save config
                Map<String, String> changes = new HashMap<String, String>();
                changes.put(FamilyKeyCrypto.PROP_FAMILY_NAME, family);
                changes.put(FamilyKeyCrypto.PROP_KEY_PASSWORD, keypw);
                changes.put(FamilyKeyCrypto.PROP_KEYSTORE_PASSWORD, KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD);
                if (_context.router().saveConfig(changes, null)) {
                    addFormNotice("Family key configured for router family: " + family);
                    addFormError(_t("Restart required to take effect"));
                } else {
                    addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs"));
                }
            } catch (GeneralSecurityException gse) {
                addFormError(_t("Load from file failed") + " - " + gse);
            } catch (IOException ioe) {
                addFormError(_t("Load from file failed") + " - " + ioe);
            } finally {
                // it's really a ByteArrayInputStream but we'll play along...
                try { in.close(); } catch (IOException ioe) {}
            }
        } else if (_action.equals(_t("Leave Family"))) {
            List<String> removes = new ArrayList<String>();
            removes.add(FamilyKeyCrypto.PROP_FAMILY_NAME);
            removes.add(FamilyKeyCrypto.PROP_KEY_PASSWORD);
            removes.add(FamilyKeyCrypto.PROP_KEYSTORE_PASSWORD);
            if (_context.router().saveConfig(null, removes)) {
                addFormNotice(_t("Configuration saved successfully."));
                addFormError(_t("Restart required to take effect"));
            } else {
                addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs"));
            }
        }
        //addFormError(_t("Unsupported") + ' ' + _action + '.');
    }
}
