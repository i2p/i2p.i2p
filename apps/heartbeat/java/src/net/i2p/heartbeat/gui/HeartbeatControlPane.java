package net.i2p.heartbeat.gui;

import javax.swing.JPanel;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;

import java.util.List;
import java.util.ArrayList;

import net.i2p.util.Log;

/**
 * Render the control widgets (refresh/load/snapshot and the 
 * tabbed panel with the plot config data)
 *
 */
class HeartbeatControlPane extends JPanel {
    private final static Log _log = new Log(HeartbeatControlPane.class);
    private HeartbeatMonitorGUI _gui;
    private JTabbedPane _configPane;
    private final static Color WHITE = new Color(255, 255, 255);
    private final static Color LIGHT_BLUE = new Color(180, 180, 255);
    private final static Color BLACK = new Color(0, 0, 0);
    private Color _background = WHITE;
    private Color _foreground = BLACK;
        
    public HeartbeatControlPane(HeartbeatMonitorGUI gui) {
        _gui = gui;
        initializeComponents();
    }
    
    public void addTest(PeerPlotConfig config) {
        _configPane.addTab(config.getTitle(), null, new JScrollPane(new PeerPlotConfigPane(config, this)), config.getSummary());
        _configPane.setBackgroundAt(_configPane.getTabCount()-1, _background);
        _configPane.setForegroundAt(_configPane.getTabCount()-1, _foreground);
    }
    public void removeTest(PeerPlotConfig config) {
        _gui.getMonitor().getState().removeTest(config);
        int index = _configPane.indexOfTab(config.getTitle());
        if (index >= 0)
            _configPane.removeTabAt(index);
    }
    
    public void testsUpdated() {
        List knownNames = new ArrayList(8);
        for (int i = 0; i < _gui.getMonitor().getState().getTestCount(); i++) {
            PeerPlotState state = _gui.getMonitor().getState().getTest(i);
            String title = state.getPlotConfig().getTitle();
            knownNames.add(state.getPlotConfig().getTitle());
            if (_configPane.indexOfTab(title) >= 0) {
                _log.debug("We already know about [" + title + "]");
            } else {
                _log.info("The test [" + title + "] is new to us");
                PeerPlotConfigPane pane = new PeerPlotConfigPane(state.getPlotConfig(), this);
                _configPane.addTab(state.getPlotConfig().getTitle(), null, new JScrollPane(pane), state.getPlotConfig().getSummary());
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
            _log.info("Removing test [" + title + "]");
            _configPane.removeTabAt(_configPane.indexOfTab(title));
        }
    }
    
    private void initializeComponents() {
        if (_gui != null)
            setBackground(_gui.getBackground());
        else
            setBackground(_background);
        setLayout(new BorderLayout());
        HeartbeatMonitorCommandBar bar = new HeartbeatMonitorCommandBar(_gui);
        bar.setBackground(getBackground());
        add(bar, BorderLayout.NORTH);
        _configPane = new JTabbedPane(JTabbedPane.LEFT);
        _configPane.setBackground(_background);
        //add(_configPane, BorderLayout.CENTER);
        add(_configPane, BorderLayout.SOUTH);
    }
}