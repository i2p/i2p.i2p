package net.i2p.router.web;

import net.i2p.router.crypto.FamilyKeyCrypto;

/**
 *  @since 0.9.25
 */
public class ConfigFamilyHelper extends HelperBase {

    public String getFamily() {
        return _context.getProperty(FamilyKeyCrypto.PROP_FAMILY_NAME, "");
    }

    public String getKeyPW() {
        return _context.getProperty(FamilyKeyCrypto.PROP_KEY_PASSWORD, "");
    }
}
