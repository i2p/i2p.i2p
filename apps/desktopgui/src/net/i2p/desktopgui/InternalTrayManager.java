package net.i2p.desktopgui;

import java.awt.Desktop;
import java.awt.Desktop.Action;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingWorker;

import net.i2p.data.DataHelper;
import net.i2p.desktopgui.router.RouterManager;
import net.i2p.desktopgui.util.BrowseException;
import net.i2p.desktopgui.util.I2PDesktop;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;
import net.i2p.util.Translate;

/**
 *  java -cp i2p.jar:router.jar:desktopgui.jar net.i2p.desktopgui.Main
 *
 *  Full access to router context.
 */
class InternalTrayManager extends TrayManager {
	
    private final RouterContext _context;
    private final Log log;
    private MenuItem _statusItem, _browserItem, _configItem, _restartItem, _stopItem,
                     _restartHardItem, _stopHardItem, _cancelItem;
    private JMenuItem _jstatusItem, _jbrowserItem, _jconfigItem, _jrestartItem, _jstopItem,
                      _jrestartHardItem, _jstopHardItem, _jcancelItem;

    private static final boolean CONSOLE_ENABLED = Desktop.isDesktopSupported() &&
                                                   Desktop.getDesktop().isSupported(Action.BROWSE);
    private static final String CONSOLE_BUNDLE_NAME = "net.i2p.router.web.messages";

    public InternalTrayManager(RouterContext ctx, Main main, boolean useSwing) {
        super(ctx, main, useSwing);
        _context = ctx;
        log = ctx.logManager().getLog(InternalTrayManager.class);
    }

    public synchronized PopupMenu getMainMenu() {
        PopupMenu popup = new PopupMenu();
        
        final MenuItem statusItem = new MenuItem("");

        final MenuItem browserLauncher;
        if (CONSOLE_ENABLED) {
            browserLauncher = new MenuItem(_t("Launch I2P Browser"));
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
                            launchBrowser();
                        }
                    }.execute();
                }
            });
        } else {
            browserLauncher = null;
        }

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

        popup.add(statusItem);
        popup.addSeparator();
        if (CONSOLE_ENABLED) {
            popup.add(browserLauncher);
            popup.addSeparator();
        }
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
        
        _statusItem = statusItem;
        _browserItem = browserLauncher;
        _configItem = desktopguiConfigurationLauncher;
        _restartItem = restartItem;
        _stopItem = stopItem;
        _restartHardItem = restartItem2;
        _stopHardItem = stopItem2;
        _cancelItem = cancelItem;

        return popup;
    }

    public synchronized JPopupMenu getSwingMainMenu() {
        JPopupMenu popup = new JPopupMenu();
        
        final JMenuItem statusItem = new JMenuItem("");

        final JMenuItem browserLauncher;
        if (CONSOLE_ENABLED) {
            browserLauncher = new JMenuItem(_t("Launch I2P Browser"));
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
                            launchBrowser();
                        }
                    }.execute();
                }
            });
        } else {
            browserLauncher = null;
        }

        JMenu desktopguiConfigurationLauncher = new JMenu(_t("Configure I2P System Tray"));
        JMenuItem configSubmenu = new JMenuItem(_t("Disable"));
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

        final JMenuItem restartItem;
        if (_context.hasWrapper()) {
            restartItem = new JMenuItem(_t("Restart I2P"));
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

        final JMenuItem stopItem = new JMenuItem(_t("Stop I2P"));
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

        final JMenuItem restartItem2;
        if (_context.hasWrapper()) {
            restartItem2 = new JMenuItem(_t("Restart I2P Immediately"));
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

        final JMenuItem stopItem2 = new JMenuItem(_t("Stop I2P Immediately"));
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

        final JMenuItem cancelItem = new JMenuItem(_t("Cancel I2P Shutdown"));
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

        popup.add(statusItem);
        popup.addSeparator();
        if (CONSOLE_ENABLED) {
            popup.add(browserLauncher);
            popup.addSeparator();
        }
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
        
        _jstatusItem = statusItem;
        _jbrowserItem = browserLauncher;
        _jconfigItem = desktopguiConfigurationLauncher;
        _jrestartItem = restartItem;
        _jstopItem = stopItem;
        _jrestartHardItem = restartItem2;
        _jstopHardItem = stopItem2;
        _jcancelItem = cancelItem;

        return popup;
    }

    /**
     * Update the menu
     * @since 0.9.26
     */
    protected synchronized void updateMenu() {
        boolean x = RouterManager.isShutdownInProgress(_context);
        boolean imminent = false;
        String status;
        if (x) {
            long time = RouterManager.getShutdownTimeRemaining(_context);
            if (time > 5000) {
                status = _t("Shutdown in {0}", DataHelper.formatDuration2(time).replace("&nbsp;", " "));
            } else {
                status = _t("Shutdown imminent");
                imminent = true;
            }
        } else {
            // status translations are in the console bundle
            status = _t("Network") + ": " +
                     Translate.getString(RouterManager.getStatus(_context), _context, CONSOLE_BUNDLE_NAME);
        }
        PopupMenu awt = trayIcon.getPopupMenu();
        if (awt != null) {
            MenuItem item = awt.getItem(0);
            String oldStatus = item.getLabel();
            if (!status.equals(oldStatus))
                item.setLabel(status);
        }
        if (_browserItem != null)
            _browserItem.setEnabled(!imminent);
        if (_configItem != null)
            _configItem.setEnabled(!imminent);
        if (_restartItem != null)
            _restartItem.setEnabled(!x);
        if (_stopItem != null)
            _stopItem.setEnabled(!x);
        if (_restartHardItem != null)
            _restartHardItem.setEnabled(!imminent);
        if (_stopHardItem != null)
            _stopHardItem.setEnabled(!imminent);
        if (_cancelItem != null)
            _cancelItem.setEnabled(x && !imminent);

        if (_jstatusItem != null)
            _jstatusItem.setText(status);
        if (_jbrowserItem != null)
            _jbrowserItem.setVisible(!imminent);
        if (_jconfigItem != null)
            _jconfigItem.setVisible(!imminent);
        if (_jrestartItem != null)
            _jrestartItem.setVisible(!x);
        if (_jstopItem != null)
            _jstopItem.setVisible(!x);
        if (_jrestartHardItem != null)
            _jrestartHardItem.setVisible(!imminent);
        if (_jstopHardItem != null)
            _jstopHardItem.setVisible(!imminent);
        if (_jcancelItem != null)
            _jcancelItem.setVisible(x && !imminent);
    }

    /**
     *  @since 0.9.26 from removed gui/DesktopguiConfigurationFrame
     */
    private void configureDesktopgui(boolean enable) {
        String property = Main.PROP_ENABLE;
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

    /**
     * Build the console URL with info from the port mapper,
     * and launch the browser at it.
     *
     * Modified from I2PTunnelHTTPClientBase.
     * TODO perhaps move this to a new PortMapper method.
     *
     * @since 0.9.26
     */
    private void launchBrowser() {
        String url = _context.portMapper().getConsoleURL();
        try {
            I2PDesktop.browse(url);
        } catch (BrowseException e1) {
            log.log(Log.WARN, "Failed to open browser!", e1);
        }    
    }
}
