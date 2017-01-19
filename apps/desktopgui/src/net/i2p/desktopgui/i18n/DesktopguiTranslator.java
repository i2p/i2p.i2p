package net.i2p.desktopgui.i18n;

import net.i2p.I2PAppContext;
import net.i2p.util.Translate;

public class DesktopguiTranslator {
    
    private static final String BUNDLE_NAME = "net.i2p.desktopgui.messages";
    
    public static String _t(I2PAppContext ctx, String s) {
        return Translate.getString(s, ctx, BUNDLE_NAME);
    }

    public static String _t(I2PAppContext ctx, String s, Object o) {
        return Translate.getString(s, o, ctx, BUNDLE_NAME);
    }
}
