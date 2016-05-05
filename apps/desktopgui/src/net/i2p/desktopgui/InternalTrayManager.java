package net.i2p.desktopgui;

import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.SwingWorker;

import net.i2p.desktopgui.router.RouterManager;
import net.i2p.desktopgui.util.BrowseException;
import net.i2p.desktopgui.util.I2PDesktop;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  java -cp i2p.jar:router.jar:desktopgui.jar net.i2p.desktopgui.Main
 *
 *  Full access to router context.
 */
class InternalTrayManager extends TrayManager {
	
    private final RouterContext _context;
    private final Log log;
    private MenuItem _restartItem, _stopItem, _cancelItem;

    public InternalTrayManager(RouterContext ctx, Main main) {
        super(ctx, main);
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

        PopupMenu desktopguiConfigurationLauncher = new PopupMenu(_t("Configure I2P System Tray"));
        MenuItem configSubmenu = new MenuItem(_t("Disable"));
        configSubmenu.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                new SwingWorker<Object, Object>() {
                    @Override
                    protected Object doInBackground() throws Exception {
                        configureDesktopgui(false);
                        return null;
                    }
                }.execute();
            }
        });

        final MenuItem restartItem;
        if (_context.hasWrapper()) {
            restartItem = new MenuItem(_t("Restart I2P"));
            restartItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent arg0) {
                    new SwingWorker<Object, Object>() {
                        @Override
                        protected Object doInBackground() throws Exception {
                            RouterManager.restartGracefully(_context);
                            return null;
                        }
                    }.execute();
                }
            });
        } else {
            restartItem = null;
        }

        final MenuItem stopItem = new MenuItem(_t("Stop I2P"));
        stopItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                new SwingWorker<Object, Object>() {
                    @Override
                    protected Object doInBackground() throws Exception {
                        RouterManager.shutDownGracefully(_context);
                        return null;
                    }
                }.execute();
            }
        });

        final MenuItem restartItem2;
        if (_context.hasWrapper()) {
            restartItem2 = new MenuItem(_t("Restart I2P Immediately"));
            restartItem2.addActionListener(new ActionListener() {
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
        } else {
            restartItem2 = null;
        }

        final MenuItem stopItem2 = new MenuItem(_t("Stop I2P Immediately"));
        stopItem2.addActionListener(new ActionListener() {
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

        final MenuItem cancelItem = new MenuItem(_t("Cancel I2P Shutdown"));
        cancelItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                new SwingWorker<Object, Object>() {
                    @Override
                    protected Object doInBackground() throws Exception {
                        RouterManager.cancelShutdown(_context);
                        return null;
                    }
                }.execute();
            }
        });

        popup.add(browserLauncher);
        popup.addSeparator();
        desktopguiConfigurationLauncher.add(configSubmenu);
        popup.add(desktopguiConfigurationLauncher);
        popup.addSeparator();
        if (_context.hasWrapper())
            popup.add(restartItem);
        popup.add(stopItem);
        if (_context.hasWrapper())
            popup.add(restartItem2);
        popup.add(stopItem2);
        popup.add(cancelItem);
        
        _restartItem = restartItem;
        _stopItem = stopItem;
        _cancelItem = cancelItem;

        return popup;
    }

    /**
     * Update the menu
     * @since 0.9.26
     */
    protected void updateMenu() {
        boolean x = RouterManager.isShutdownInProgress(_context);
        if (_restartItem != null)
            _restartItem.setEnabled(!x);
        _stopItem.setEnabled(!x);
        _cancelItem.setEnabled(x);
    }

    /**
     *  @since 0.9.26 from removed gui/DesktopguiConfigurationFrame
     */
    private void configureDesktopgui(boolean enable) {
        String property = "desktopgui.enabled";
        String value = Boolean.toString(enable);
        try {

            _context.router().saveConfig(property, value);
            if (!enable) {
                // TODO popup that explains how to re-enable in console
                _main.shutdown(null);
            }
        } catch (Exception ex) {
            log.error("Error saving config", ex);
        }
    }
}
