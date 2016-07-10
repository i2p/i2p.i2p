package net.i2p.desktopgui.util;

import java.awt.Desktop;
import java.awt.Desktop.Action;
import java.net.URI;

public class I2PDesktop {
    
    public static void browse(String url) throws BrowseException {
        if(Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            if(desktop.isSupported(Action.BROWSE)) {
                try {
                    desktop.browse(new URI(url));
                } catch (Exception e) {
                    throw new BrowseException();
                }
            }
            else {
                throw new BrowseException();
            }
        }
        else {
            throw new BrowseException();
        }
    }
}
