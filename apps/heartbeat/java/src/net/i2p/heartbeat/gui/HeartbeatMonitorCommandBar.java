package net.i2p.heartbeat.gui;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

class HeartbeatMonitorCommandBar extends JPanel {
    private HeartbeatMonitorGUI _gui;
    private JComboBox _refreshRate;
    private JTextField _location;
    
    /**
     * Constructs a command bar onto the gui
     * @param gui the gui the command bar is associated with
     */
    public HeartbeatMonitorCommandBar(HeartbeatMonitorGUI gui) {
        _gui = gui;
        initializeComponents();
    }
    
    private void refreshChanged(ItemEvent evt) {}
    private void loadCalled() {
        _gui.getMonitor().load(_location.getText());
    }
    
    private void browseCalled() {
        JFileChooser chooser = new JFileChooser(_location.getText());
        chooser.setBackground(_gui.getBackground());
        chooser.setMultiSelectionEnabled(false);
        int rv = chooser.showDialog(this, "Load");
        if (rv == JFileChooser.APPROVE_OPTION) 
            _gui.getMonitor().load(chooser.getSelectedFile().getAbsolutePath());
    }
    
    private void initializeComponents() {
        _refreshRate = new JComboBox(new DefaultComboBoxModel(new Object[] {"10 second refresh", "30 second refresh", "1 minute refresh", "5 minute refresh"}));
        _refreshRate.addItemListener(new ItemListener() { public void itemStateChanged(ItemEvent evt) { refreshChanged(evt); } });
        _refreshRate.setEnabled(false);
        _refreshRate.setBackground(_gui.getBackground());
        add(_refreshRate);
        JLabel loadLabel = new JLabel("Load from: ");
        loadLabel.setBackground(_gui.getBackground());
        add(loadLabel);
        _location = new JTextField(20);
        _location.setToolTipText("Either specify a local filename or a fully qualified URL");
        _location.setBackground(_gui.getBackground());
        add(_location);
        JButton browse = new JButton("Browse...");
        browse.addActionListener(new ActionListener() { public void actionPerformed(ActionEvent evt) { browseCalled(); } });
        browse.setBackground(_gui.getBackground());
        add(browse);
        JButton load = new JButton("Load");
        load.addActionListener(new ActionListener() { public void actionPerformed(ActionEvent evt) { loadCalled(); } });
        load.setBackground(_gui.getBackground());
        add(load);
        setBackground(_gui.getBackground());
    }
}