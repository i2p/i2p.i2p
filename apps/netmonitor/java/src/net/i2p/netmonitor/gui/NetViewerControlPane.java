package net.i2p.netmonitor.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import java.awt.Dimension;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;

import net.i2p.util.Log;

/**
 * Render the control widgets (refresh/load/snapshot and the 
 * tabbed panel with the plot config data)
 *
 */
class NetViewerControlPane extends JPanel {
    private final static Log _log = new Log(NetViewerControlPane.class);
    private NetViewerGUI _gui;
    private JTabbedPane _configPane;
    private final static Color WHITE = new Color(255, 255, 255);
    private final static Color LIGHT_BLUE = new Color(180, 180, 255);
    private final static Color BLACK = new Color(0, 0, 0);
    private Color _background = WHITE;
    private Color _foreground = BLACK;
        
    /**
     * Constructs a control panel onto the gui
     * @param gui the gui the panel is associated with
     */
    public NetViewerControlPane(NetViewerGUI gui) {
        _gui = gui;
        initializeComponents();
    }
    
    /** the settings have changed - revise */
    void refreshView() {
        _gui.refreshView();
    }
    
    /**
     * Callback: when tests have changed
     */
    public synchronized void stateUpdated() {
        List knownNames = new ArrayList(8);
        List peers = _gui.getViewer().getMonitor().getPeers();
        _log.debug("GUI updated with peers: " + peers);
        for (int i = 0; i < peers.size(); i++) {
            String name = (String)peers.get(i);
            String shortName = name.substring(0,4);
            knownNames.add(shortName);
            if (_configPane.indexOfTab(shortName) >= 0) {
                JScrollPane pane = (JScrollPane)_configPane.getComponentAt(_configPane.indexOfTab(shortName));
                PeerPlotConfigPane cfgPane = (PeerPlotConfigPane)pane.getViewport().getView();
                cfgPane.stateUpdated();
                _log.debug("We already know about [" + name + "]");
            } else {
                _log.info("The peer [" + name + "] is new to us");
                PeerPlotConfig cfg = new PeerPlotConfig(_gui.getViewer().getMonitor().getSummary(name));
                _gui.getViewer().addConfig(name, cfg);
                PeerPlotConfigPane pane = new PeerPlotConfigPane(cfg, this);
                JScrollPane p = new JScrollPane(pane);
                p.setBackground(_background);
                _configPane.addTab(shortName, null, p, "Peer " + name);
                _configPane.setBackgroundAt(_configPane.getTabCount()-1, _background);
                _configPane.setForegroundAt(_configPane.getTabCount()-1, _foreground);
            }
        }
        List toRemove = new ArrayList(4);
        for (int i = 0; i < _configPane.getTabCount(); i++) {
            if (knownNames.contains(_configPane.getTitleAt(i))) {
                // noop
            } else {
                toRemove.add(_configPane.getTitleAt(i));
            }
        }
        for (int i = 0; i < toRemove.size(); i++) {
            String title = (String)toRemove.get(i);
            _log.info("Removing peer [" + title + "]");
            _configPane.removeTabAt(_configPane.indexOfTab(title));
        }
    }
    
    private void initializeComponents() {
        if (_gui != null)
            setBackground(_gui.getBackground());
        else
            setBackground(_background);
        setLayout(new BorderLayout());
        NetViewerCommandBar bar = new NetViewerCommandBar(_gui);
        bar.setBackground(getBackground());
        add(bar, BorderLayout.NORTH);
        _configPane = new JTabbedPane(JTabbedPane.LEFT);
        _configPane.setBackground(_background);
        JScrollPane pane = new JScrollPane(_configPane);
        pane.setBackground(_background);
        add(pane, BorderLayout.CENTER);
        //setPreferredSize(new Dimension(800, 400));
        //setMinimumSize(new Dimension(800, 400));
        //setMaximumSize(new Dimension(800, 400));
    }
    
    NetViewer getViewer() { return _gui.getViewer(); }
}