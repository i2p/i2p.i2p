package net.i2p.router.web.helpers;

import net.i2p.router.web.HelperBase;

/**
 *  The new user wizard.
 *
 *  @since 0.9.38
 */
public class WizardHelper extends HelperBase {

    public void complete() {
        _context.router().saveConfig("routerconsole.welcomeWizardComplete", "true");
    }
}
