package net.i2p.netmonitor.gui;

import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.TreeMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Iterator;
import java.util.Random;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import net.i2p.util.Log;
import net.i2p.netmonitor.PeerStat;
import net.i2p.netmonitor.PeerSummary;

class PeerPlotConfigPane extends JPanel implements PeerPlotConfig.UpdateListener {
    private final static Log _log = new Log(PeerPlotConfigPane.class);
    private PeerPlotConfig _config;
    private NetViewerControlPane _parent;
    private JLabel _peer;
    private JButton _toggleAll;
    private OptionGroup _options[];
    private Random _rnd = new Random();
    private final static Color WHITE = new Color(255, 255, 255);
    private Color _background = WHITE;
    
    /**
     * Constructs a pane
     * @param config the plot config it represents
     * @param pane the pane this one is attached to
     */
    public PeerPlotConfigPane(PeerPlotConfig config, NetViewerControlPane pane) {
        _config = config;
        _parent = pane;
        if (_parent != null)
            _background = _parent.getBackground();
        _config.addListener(this);
        initializeComponents();
    }
    
    private void initializeComponents() {
        buildComponents();
        placeComponents(this);
        refreshView();
        //setBorder(new BevelBorder(BevelBorder.RAISED));
        setBackground(_background);
    }
    
    /** 
     * place all the gui components onto the given panel 
     * @param body the panel to place the components on
     */
    private void placeComponents(JPanel body) {
        body.setLayout(new GridBagLayout());
        GridBagConstraints cts = new GridBagConstraints();
        
        // row 0: peer name + toggle All
        cts.gridx = 0;
        cts.gridy = 0;
        cts.gridwidth = 2;
        cts.anchor = GridBagConstraints.WEST;
        cts.fill = GridBagConstraints.NONE;
        body.add(_peer, cts);
        cts.gridx = 2;
        cts.gridy = 0;
        cts.gridwidth = 1;
        cts.anchor = GridBagConstraints.EAST;
        cts.fill = GridBagConstraints.NONE;
        body.add(_toggleAll, cts);
        
        int row = 0;
        for (int i = 0; i < _options.length; i++) {
            row++;
            cts.gridx = 0;
            cts.gridy = row;
            cts.gridwidth = 3;
            cts.weightx = 5;
            cts.fill = GridBagConstraints.BOTH;
            cts.anchor = GridBagConstraints.WEST;
            body.add(_options[i]._statName, cts);
            
            for (int j = 0; j < _options[i]._statAttributes.size(); j++) {
                row++;
                cts.gridx = 0;
                cts.gridy = row;
                cts.gridwidth = 1;
                cts.weightx = 1;
                cts.fill = GridBagConstraints.NONE;
                body.add(new JLabel("   "), cts);
                cts.gridx = 1;
                cts.gridy = row;
                cts.gridwidth = 1;
                cts.weightx = 5;
                cts.fill = GridBagConstraints.BOTH;
                JCheckBox box = (JCheckBox)_options[i]._statAttributes.get(j);
                box.setBackground(_background);
                body.add(box, cts);
                cts.gridx = 2;
                cts.fill = GridBagConstraints.NONE;
                cts.anchor = GridBagConstraints.EAST;
                JButton toggleAttr = new JButton("Toggle all");
                toggleAttr.setBackground(_background);
                toggleAttr.addActionListener(new ToggleAttribute(_options[i].getStatName(), box.getText(), box));
                body.add(toggleAttr, cts);
            }
        }
    }
    
    private class ToggleAttribute implements ActionListener {
        private String _statName;
        private String _attrName;
        private JCheckBox _box;
        public ToggleAttribute(String statName, String attrName, JCheckBox box) {
            _statName = statName;
            _attrName = attrName;
            _box = box;
        }
        public void actionPerformed(ActionEvent evt) {
            boolean setActive = true;
            if (_box.isSelected()) {
                setActive = false;
            }

            List names = _parent.getViewer().getConfigNames();
            for (int i = 0; i < names.size(); i++) {
                String name = (String)names.get(i);
                PeerPlotConfig cfg = _parent.getViewer().getConfig(name);
                cfg.getSeriesConfig().setShouldPlotValue(_statName, _attrName, setActive);
                _log.debug("Setting " + _statName + "." + _attrName + " to " + setActive + " for " + name);
            }
            _parent.stateUpdated();
            _parent.refreshView();
        }
    }
    
    /** build all of the gui components */
    private void buildComponents() {
        _peer = new JLabel("Router: " + _config.getPeerName());
        
        _toggleAll = new JButton("Show all");
        _toggleAll.setBackground(_background);
        _toggleAll.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent evt) {
                _config.disableEvents();
                for (int i = 0; i < _options.length; i++) {
                    for (int j = 0; j < _options[i]._statAttributes.size(); j++) {
                        JCheckBox box = (JCheckBox)_options[i]._statAttributes.get(j);
                        String statName = _options[i].getStatName();
                        String attrName = box.getText();
                        if (_toggleAll.getText().equals("Show all")) {
                            box.setSelected(true);
                            _config.getSeriesConfig().setShouldPlotValue(statName, attrName, true);
                        } else {
                            box.setSelected(false);
                            _config.getSeriesConfig().setShouldPlotValue(statName, attrName, false);
                        }
                    }
                }
                if (_toggleAll.getText().equals("Show all"))
                    _toggleAll.setText("Hide all");
                else
                    _toggleAll.setText("Show all");
                _config.enableEvents();
                _config.fireUpdate();
            }
        });
        
        Set statNames = _config.getSummary().getStatNames();
        List options = new ArrayList(statNames.size());
        for (Iterator iter = statNames.iterator(); iter.hasNext(); ) {
            String statName = (String)iter.next();
            List data = _config.getSummary().getData(statName);
            if ( (data != null) && (data.size() > 0) ) {
                PeerStat stat = (PeerStat)data.get(0);
                String attributes[] = stat.getValueDescriptions();
                OptionGroup group = new OptionGroup(statName, attributes);
                options.add(group);
            }
        }
        TreeMap orderedOptions = new TreeMap();
        for (int i = 0; i < options.size(); i++) {
            OptionGroup grp = (OptionGroup)options.get(i);
            orderedOptions.put(grp.getStatName(), grp);
        }
        _options = new OptionGroup[options.size()];
        int i = 0;
        for (Iterator iter = orderedOptions.values().iterator(); iter.hasNext(); ) {
            OptionGroup grp = (OptionGroup)iter.next();
            _options[i] = grp;
            i++;
        }
    }
    
    /** the settings have changed - revise */
    private void refreshView() {
        _parent.refreshView();
    }
    
    /** 
     * notified that the config has been updated 
     * @param config the config that was been updated
     */
    public void configUpdated(PeerPlotConfig config) { refreshView(); }
    
    public void stateUpdated() {
        for (int i = 0; i < _options.length; i++) {
            for (int j = 0; j < _options[i]._statAttributes.size(); j++) {
                JCheckBox box = (JCheckBox)_options[i]._statAttributes.get(j);
                if (_config.getSeriesConfig().getShouldPlotValue(_options[i].getStatName(), box.getText(), false))
                    box.setSelected(true);
                else
                    box.setSelected(false);
            }
        }
    }
    
    private class OptionGroup {
        JTextField _statName;
        List _statAttributes;
        
        public String getStatName() { return _statName.getText(); }
        
        /**
         * Creates an OptionLine.  
         * @param statName statistic group in question
         * @param statAttributes list of attributes we keep about this stat
         */
        public OptionGroup(String statName, String statAttributes[]) {
            _statName = new JTextField(statName);
            _statName.setEditable(false);
            _statName.setBackground(_background);
            _statAttributes = new ArrayList(4);
            if (statAttributes != null) {
                for (int i = 0; i < statAttributes.length; i++) {
                    JCheckBox box = new JCheckBox(statAttributes[i]);
                    box.addActionListener(new UpdateListener(OptionGroup.this));
                    _statAttributes.add(box);
                }
            }
        }
    }
    
    private class UpdateListener implements ActionListener {
        private OptionGroup _group;

        /**
         * Update Listener constructor . . . 
         * @param group the group of stats to watch
         */
        public UpdateListener(OptionGroup group) {
            _group = group;
        }
        
        /* (non-Javadoc)
         * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
         */
        public void actionPerformed(ActionEvent evt) { 
            _config.disableEvents();
            
            for (int i = 0; i < _group._statAttributes.size(); i++) {
                JCheckBox box = (JCheckBox)_group._statAttributes.get(i);
                _config.getSeriesConfig().setShouldPlotValue(_group.getStatName(), box.getText(), box.isSelected());
            }
            _config.enableEvents();
            _config.fireUpdate();
        } 
    }
}