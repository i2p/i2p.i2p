package net.i2p.heartbeat.gui;

import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
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

class PeerPlotConfigPane extends JPanel implements PeerPlotConfig.UpdateListener {
    private final static Log _log = new Log(PeerPlotConfigPane.class);
    private PeerPlotConfig _config;
    private HeartbeatControlPane _parent;
    private JLabel _title;
    private JButton _delete;
    private JLabel _fromLabel;
    private JTextField _from;
    private JTextArea _comments;
    private JLabel _peerLabel;
    private JTextField _peerKey;
    private JLabel _localLabel;
    private JTextField _localKey;
    private OptionLine _options[];
    private Random _rnd = new Random();
    private final static Color WHITE = new Color(255, 255, 255);
    private Color _background = WHITE;
    
    /**
     * Constructs a pane
     * @param config the plot config it represents
     * @param pane the pane this one is attached to
     */
    public PeerPlotConfigPane(PeerPlotConfig config, HeartbeatControlPane pane) {
        _config = config;
        _parent = pane;
        if (_parent != null)
            _background = _parent.getBackground();
        _config.addListener(this);
        initializeComponents();
    }
    
    /** called when the user wants to stop monitoring this test */
    private void delete() {
        _parent.removeTest(_config);
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
        
        // row 0: title + delete
        cts.gridx = 0;
        cts.gridy = 0;
        cts.gridwidth = 5;
        cts.anchor = GridBagConstraints.WEST;
        cts.fill = GridBagConstraints.NONE;
        body.add(_title, cts);
        cts.gridx = 5;
        cts.gridwidth = 1;
        cts.anchor = GridBagConstraints.NORTHWEST;
        cts.fill = GridBagConstraints.BOTH;
        body.add(_delete, cts);
        
        // row 1: from + location
        cts.gridx = 0;
        cts.gridy = 1;
        cts.gridwidth = 1;
        cts.fill = GridBagConstraints.NONE;
        body.add(_fromLabel, cts);
        cts.gridx = 1;
        cts.gridwidth = 5;
        cts.fill = GridBagConstraints.BOTH;
        body.add(_from, cts);

        // row 2: comment
        cts.gridx = 0;
        cts.gridy = 2;
        cts.gridwidth = 6;
        cts.fill = GridBagConstraints.BOTH;
        body.add(_comments, cts);
        
        // row 3: peer + peerKey 
        cts.gridx = 0;
        cts.gridy = 3;
        cts.gridwidth = 1;
        cts.fill = GridBagConstraints.NONE;
        body.add(_peerLabel, cts);
        cts.gridx = 1;
        cts.gridwidth = 5;
        cts.fill = GridBagConstraints.BOTH;
        body.add(_peerKey, cts);
        
        // row 4: local + localKey
        cts.gridx = 0;
        cts.gridy = 4;
        cts.gridwidth = 1;
        cts.fill = GridBagConstraints.NONE;
        body.add(_localLabel, cts);
        cts.gridx = 1;
        cts.gridwidth = 5;
        cts.fill = GridBagConstraints.BOTH;
        body.add(_localKey, cts);
        
        // row 5-N: data row
        for (int i = 0; i < _options.length; i++) {
            cts.gridx = 0;
            cts.gridy = 5 + i;
            cts.gridwidth = 1;
            cts.fill = GridBagConstraints.NONE;
            cts.anchor = GridBagConstraints.WEST;
            if (_options[i]._durationMinutes <= 0)
                body.add(new JLabel("Data: "), cts);
            else
                body.add(new JLabel(_options[i]._durationMinutes + "m avg: "), cts);
            
            cts.gridx = 1;
            body.add(_options[i]._send, cts);
            cts.gridx = 2;
            body.add(_options[i]._recv, cts);
            cts.gridx = 3;
            body.add(_options[i]._lost, cts);
            cts.gridx = 4;
            body.add(_options[i]._all, cts);
            cts.gridx = 5;
            body.add(_options[i]._color, cts);
        }
    }
    
    /** build all of the gui components */
    private void buildComponents() {
        _title = new JLabel(_config.getSummary());
        _title.setBackground(_background);
        _delete = new JButton("Delete");
        _delete.addActionListener(new ActionListener() { public void actionPerformed(ActionEvent evt) { delete(); } });
        _delete.setEnabled(false);
        _delete.setBackground(_background);
        _fromLabel = new JLabel("Location: ");
        _fromLabel.setBackground(_background);
        _from = new JTextField(_config.getLocation());
        _from.setEditable(false);
        _from.setBackground(_background);
        _comments = new JTextArea(_config.getClientConfig().getComment(), 2, 20);
        // _comments = new JTextArea(_config.getClientConfig().getComment(), 2, 40);
        _comments.setEditable(false);
        _comments.setBackground(_background);
        _peerLabel = new JLabel("Peer: ");
        _peerLabel.setBackground(_background);
        _peerKey = new JTextField(_config.getClientConfig().getPeer().toBase64(), 8);
        _peerKey.setBackground(_background);
        _localLabel = new JLabel("Local: ");
        _localLabel.setBackground(_background);
        _localKey = new JTextField(_config.getClientConfig().getUs().toBase64(), 8);
        _localKey.setBackground(_background);
        
        int averagedPeriods[] = _config.getClientConfig().getAveragePeriods();
        if (averagedPeriods == null)
            averagedPeriods = new int[0];
        
        _options = new OptionLine[1 + averagedPeriods.length];
        _options[0] = new OptionLine(0);
        for (int i = 0; i < averagedPeriods.length; i++) {
            _options[1+i] = new OptionLine(averagedPeriods[i]);
        }   
    }
    
    /** the settings have changed - revise */
    private void refreshView() {
        for (int i = 0; i < _options.length; i++) {
            PeerPlotConfig.PlotSeriesConfig cfg = getConfig(_options[i]._durationMinutes);
            if (cfg == null) {
                _log.warn("Config for minutes " + _options[i]._durationMinutes + " was not found?");
                continue;
            }
            //_log.debug("Refreshing view for minutes ["+ _options[i]._durationMinutes + "]: send [" + 
            //           _options[i]._send.isSelected() + "/" + cfg.getPlotSendTime() + "] recv [" + 
            //           _options[i]._recv.isSelected() + "/" + cfg.getPlotReceiveTime() + "] lost [" +
            //           _options[i]._lost.isSelected() + "/" + cfg.getPlotLostMessages() + "]");
            _options[i]._send.setSelected(cfg.getPlotSendTime());
            _options[i]._recv.setSelected(cfg.getPlotReceiveTime());
            _options[i]._lost.setSelected(cfg.getPlotLostMessages());
            if (cfg.getPlotLineColor() != null)
                _options[i]._color.setBackground(cfg.getPlotLineColor());
        }
    }
    
    /** 
     * find the right config for the given period
     * @param minutes the minutes to locate the config by
     * @return the config for the given period, or null
     */
    private PeerPlotConfig.PlotSeriesConfig getConfig(int minutes) {
        if (minutes <= 0)
            return _config.getCurrentSeriesConfig();
        
        List configs = _config.getAverageSeriesConfigs();
        for (int i = 0; i < configs.size(); i++) {
            PeerPlotConfig.PlotSeriesConfig cfg = (PeerPlotConfig.PlotSeriesConfig)configs.get(i);
            if (cfg.getPeriod() == minutes * 60*1000)
                return cfg;
        }
        return null;
    }
    
    /** 
     * notified that the config has been updated 
     * @param config the config that was been updated
     */
    public void configUpdated(PeerPlotConfig config) { refreshView(); }
    
    private class ChooseColor implements ActionListener {
        private int _minutes;
        private JButton _button;
        
        /**
         * @param minutes the minutes (line) to change the color of...
         * @param button the associated button
         */
        public ChooseColor(int minutes, JButton button) { 
            _minutes = minutes; 
            _button = button;
        }

        /* (non-Javadoc)
         * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
         */
        public void actionPerformed(ActionEvent evt) { 
            PeerPlotConfig.PlotSeriesConfig cfg = getConfig(_minutes);
            Color origColor = null;
            if (cfg != null)
                origColor = cfg.getPlotLineColor();
            Color color = JColorChooser.showDialog(PeerPlotConfigPane.this, "What color should this line be?", origColor);
            if (color != null) {
                if (cfg != null)
                    cfg.setPlotLineColor(color);
                _button.setBackground(color);
            }
        }
    }
    
    private class OptionLine {
        int _durationMinutes;
        JCheckBox _send;
        JCheckBox _recv;
        JCheckBox _lost;
        JCheckBox _all;
        JButton _color;
        
        /**
         * Creates an OptionLine.  
         * @param durationMinutes the minutes =)
         */
        public OptionLine(int durationMinutes) {
            _durationMinutes = durationMinutes;
            _send = new JCheckBox("send time");
            _send.setBackground(_background);
            _recv = new JCheckBox("receive time");
            _recv.setBackground(_background);
            _lost = new JCheckBox("lost messages");
            _lost.setBackground(_background);
            _all = new JCheckBox("all");
            _all.setBackground(_background);
            _color = new JButton("color");
            int r = _rnd.nextInt(255);
            if (r < 0) r = -r;
            int g = _rnd.nextInt(255);
            if (g < 0) g = -g;
            int b = _rnd.nextInt(255);
            if (b < 0) b = -b;
            //_color.setBackground(new Color(r, g, b));
            _color.setBackground(_background);
            
            _send.addActionListener(new UpdateListener(OptionLine.this, _durationMinutes));
            _recv.addActionListener(new UpdateListener(OptionLine.this, _durationMinutes));
            _lost.addActionListener(new UpdateListener(OptionLine.this, _durationMinutes));
            _all.addActionListener(new UpdateListener(OptionLine.this, _durationMinutes));
            _color.addActionListener(new ChooseColor(durationMinutes, _color));
            _color.setEnabled(false);
        }
    }
    
    private class UpdateListener implements ActionListener {
        private OptionLine _line;
        private int _minutes;

        /**
         * Update Listener constructor . . . 
         * @param line the line
         * @param minutes the minutes
         */
        public UpdateListener(OptionLine line, int minutes) {
            _line = line;
            _minutes = minutes;
        }
        
        /* (non-Javadoc)
         * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
         */
        public void actionPerformed(ActionEvent evt) { 
            PeerPlotConfig.PlotSeriesConfig cfg = getConfig(_minutes);
            
            cfg.getPlotConfig().disableEvents();
            _log.debug("Updating data for minutes ["+ _line._durationMinutes + "]: send [" + 
                       _line._send.isSelected() + "/" + cfg.getPlotSendTime() + "] recv [" + 
                       _line._recv.isSelected() + "/" + cfg.getPlotReceiveTime() + "] lost [" +
                       _line._lost.isSelected() + "/" + cfg.getPlotLostMessages() + "]: config = " + cfg);
            
            boolean force = _line._all.isSelected();
            cfg.setPlotSendTime(_line._send.isSelected() || force);
            cfg.setPlotReceiveTime(_line._recv.isSelected() || force);
            cfg.setPlotLostMessages(_line._lost.isSelected() || force);
            cfg.getPlotConfig().enableEvents();
            cfg.getPlotConfig().fireUpdate();
        } 
    }
    
    /**
     * Unit test stuff
     * @param args da arsg
     */
    public final static void main(String args[]) {
        Test t = new Test();
        t.runTest();
    }
    
    private final static class Test implements PeerPlotStateFetcher.FetchStateReceptor {
        /**
         * Runs da test
         */
        public void runTest() {
            PeerPlotConfig cfg = new PeerPlotConfig("C:\\testnet\\r2\\heartbeatStat_10s_30kb.txt");
            PeerPlotState state = new PeerPlotState(cfg);
            PeerPlotStateFetcher.fetchPeerPlotState(this, state);
            try { Thread.sleep(60*1000); } catch (InterruptedException ie) {}
            System.exit(-1);
        }
        
        /* (non-Javadoc)
         * @see net.i2p.heartbeat.gui.PeerPlotStateFetcher.FetchStateReceptor#peerPlotStateFetched(net.i2p.heartbeat.gui.PeerPlotState)
         */
        public void peerPlotStateFetched(PeerPlotState state) {
            javax.swing.JFrame f = new javax.swing.JFrame("Test");
            f.getContentPane().add(new JScrollPane(new PeerPlotConfigPane(state.getPlotConfig(), null)));
            f.pack();
            f.setVisible(true);
        }
    }
}