package net.i2p.heartbeat.gui;

import java.awt.BorderLayout;

import javax.swing.JLabel;
import javax.swing.JScrollPane;

import org.jfree.chart.ChartPanel;

import net.i2p.util.Log;

/**
 * Render the graph and legend
 *
 */
class JFreeChartHeartbeatPlotPane extends HeartbeatPlotPane {
    private final static Log _log = new Log(JFreeChartHeartbeatPlotPane.class);
    private ChartPanel _panel;
    private JFreeChartAdapter _adapter;
        
    public JFreeChartHeartbeatPlotPane(HeartbeatMonitorGUI gui) {
        super(gui);
    }
    
    public void stateUpdated() {
        if (_panel == null) {
            remove(0); // remove the dummy
            
            _adapter = new JFreeChartAdapter();
            _panel = _adapter.createPanel(_gui.getMonitor().getState());
            _panel.setBackground(_gui.getBackground());
            add(new JScrollPane(_panel), BorderLayout.CENTER);
            _gui.pack();
        } else {
            _adapter.updateChart(_panel, _gui.getMonitor().getState());
            //_gui.pack();
        }
    }
    
    protected void initializeComponents() {
        // noop
        setLayout(new BorderLayout());
        add(new JLabel(), BorderLayout.CENTER);
        //dummy.setBackground(_gui.getBackground());
        //dummy.setPreferredSize(new Dimension(800,600));
        //add(dummy);

        //add(_panel);

    }
}