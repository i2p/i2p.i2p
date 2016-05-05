package net.i2p.desktopgui;

import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.SwingWorker;
import net.i2p.desktopgui.gui.DesktopguiConfigurationFrame;

import net.i2p.desktopgui.router.RouterManager;
import net.i2p.desktopgui.util.BrowseException;
import net.i2p.desktopgui.util.I2PDesktop;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  java -cp i2p.jar:desktopgui.jar net.i2p.desktopgui.Main
 *
 *  Full access to router context.
 */
class InternalTrayManager extends TrayManager {
	
    private final RouterContext _context;
    private final Log log;

    public InternalTrayManager(RouterContext ctx) {
        super(ctx);
        _context = ctx;
        log = ctx.logManager().getLog(InternalTrayManager.class);
    }

    @Override
    public PopupMenu getMainMenu() {
        PopupMenu popup = new PopupMenu();
        
        MenuItem browserLauncher = new MenuItem(_t("Launch I2P Browser"));
        browserLauncher.addActionListener(new ActionListener() {
            
            @Override
            public void actionPerformed(ActionEvent arg0) {
                new SwingWorker<Object, Object>() {

                    @Override
                    protected Object doInBackground() throws Exception {
                        return null;
                    }
                    
                    @Override
                    protected void done() {
                        try {
                            I2PDesktop.browse("http://localhost:7657");
                        } catch (BrowseException e1) {
                            log.log(Log.WARN, "Failed to open browser!", e1);
                        }    
                    }
                    
                }.execute();
            }
        });
        MenuItem desktopguiConfigurationLauncher = new MenuItem(_t("Configure desktopgui"));
        desktopguiConfigurationLauncher.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent arg0) {
                new SwingWorker<Object, Object>() {

                    @Override
                    protected Object doInBackground() throws Exception {
                        new DesktopguiConfigurationFrame(_context).setVisible(true);
                        return null;
                    }

                }.execute();
            }

        });
        MenuItem restartItem = new MenuItem(_t("Restart I2P"));
        restartItem.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent arg0) {
                new SwingWorker<Object, Object>() {

                    @Override
                    protected Object doInBackground() throws Exception {
                        RouterManager.restart(_context);
                        return null;
                    }
                    
                }.execute();
                
            }
            
        });
        MenuItem stopItem = new MenuItem(_t("Stop I2P"));
        stopItem.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent arg0) {
                new SwingWorker<Object, Object>() {
                    
                    @Override
                    protected Object doInBackground() throws Exception {
                        RouterManager.shutDown(_context);
                        return null;
                    }
                    
                }.execute();
                
            }
            
        });
        
        popup.add(browserLauncher);
        popup.addSeparator();
        popup.add(desktopguiConfigurationLauncher);
        popup.addSeparator();
        popup.add(restartItem);
        popup.add(stopItem);
        
        return popup;
    }
}
