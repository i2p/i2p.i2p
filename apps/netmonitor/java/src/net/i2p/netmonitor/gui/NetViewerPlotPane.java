package net.i2p.netmonitor.gui;

import javax.swing.JPanel;
import javax.swing.JTextArea;

import net.i2p.util.Log;

/**
 * Render the graph and legend
 */
class NetViewerPlotPane extends JPanel {
    private final static Log _log = new Log(NetViewerPlotPane.class);
    protected NetViewerGUI _gui;
    private JTextArea _text;
    
    /**
     * Constructs the plot pane
     * @param gui the gui the pane is attached to
     */
    public NetViewerPlotPane(NetViewerGUI gui) {
        _gui = gui;
        initializeComponents();
    }
    
    /**
     * Callback: when things change . . . 
     */
    public void stateUpdated() {
        StringBuffer buf = new StringBuffer(32*1024);
        buf.append("moo");
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