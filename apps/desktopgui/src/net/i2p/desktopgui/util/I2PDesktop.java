package net.i2p.desktopgui.util;

import java.awt.Desktop;
import java.awt.TrayIcon;
import java.awt.Desktop.Action;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import net.i2p.desktopgui.router.RouterManager;
import net.i2p.util.Log;

public class I2PDesktop {
    
    private final static Log log = new Log(I2PDesktop.class);
    
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