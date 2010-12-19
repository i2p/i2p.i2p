package net.i2p.desktopgui.i18n;

import net.i2p.router.RouterContext;
import net.i2p.util.Translate;

public class DesktopguiTranslator {
	
    private static final String BUNDLE_NAME = "net.i2p.desktopgui.messages";
	
	private static RouterContext ctx;
	
	private static RouterContext getRouterContext() {
		if(ctx == null) {
			ctx = RouterContext.listContexts().get(0);
		}
		return ctx;
	}
	
    public static String _(String s) {
        return Translate.getString(s, getRouterContext(), BUNDLE_NAME);
    }

    public static String _(String s, Object o) {
        return Translate.getString(s, o, getRouterContext(), BUNDLE_NAME);
    }
}
