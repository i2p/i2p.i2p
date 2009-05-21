/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package net.i2p.desktopgui.router.configuration;

import net.i2p.desktopgui.router.RouterHelper;

/**
 *
 * @author mathias
 */
public class UpdateHandler {
    public static void setUpdatePolicy(String policy) {
        RouterHelper.getContext().router().setConfigSetting(UpdateHelper.PROP_UPDATE_POLICY, policy);
        RouterHelper.getContext().router().saveConfig();
    }
}
