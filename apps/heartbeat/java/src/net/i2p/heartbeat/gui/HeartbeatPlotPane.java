package net.i2p.heartbeat.gui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.swing.JPanel;
import javax.swing.JTextArea;

import net.i2p.heartbeat.PeerDataWriter;
import net.i2p.util.Log;

/**
 * Render the graph and legend
 */
class HeartbeatPlotPane extends JPanel {
    private final static Log _log = new Log(HeartbeatPlotPane.class);
    protected HeartbeatMonitorGUI _gui;
    private JTextArea _text;
    
    /**
     * Constructs the plot pane
     * @param gui the gui the pane is attached to
     */
    public HeartbeatPlotPane(HeartbeatMonitorGUI gui) {
        _gui = gui;
        initializeComponents();
    }
    
    /**
     * Callback: when things change . . . 
     */
    public void stateUpdated() {
        StringBuffer buf = new StringBuffer(32*1024);
        PeerDataWriter writer = new PeerDataWriter();
        
        for (int i = 0; i < _gui.getMonitor().getState().getTestCount(); i++) {
            StaticPeerData data = _gui.getMonitor().getState().getTest(i).getCurrentData();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
            try {
                writer.persist(data, baos);
            } catch (IOException ioe) {
                _log.error("wtf, error writing to a byte array?", ioe);
            }
            buf.append(new String(baos.toByteArray())).append("\n\n\n");
        }
        
        _text.setText(buf.toString());
    }
    
    protected void initializeComponents() {
        setBackground(_gui.getBackground());
        //Dimension size = new Dimension(800, 600);
        _text = new JTextArea("",30,80); // 16, 60);
        _text.setAutoscrolls(true);
        _text.setEditable(false);
        // _text.setLineWrap(true);
        // add(new JScrollPane(_text));
        add(_text);
        // add(new JScrollPane(_text, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS));
        // setPreferredSize(size);
    }
}