package net.i2p.netmonitor.gui;

import java.awt.Color;
import java.awt.Font;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.Plot;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.XYItemRenderer;
import org.jfree.chart.renderer.XYLineAndShapeRenderer;
import org.jfree.data.XYSeries;
import org.jfree.data.XYSeriesCollection;

import net.i2p.netmonitor.PeerStat;
import net.i2p.util.Log;

class JFreeChartAdapter {
    private final static Log _log = new Log(JFreeChartAdapter.class);
    private final static Color WHITE = new Color(255, 255, 255);
    
    ChartPanel createPanel(NetViewer state) {
        ChartPanel panel = new ChartPanel(createChart(state));
        panel.setDisplayToolTips(true);
        panel.setEnforceFileExtensions(true);
        panel.setHorizontalZoom(true);
        panel.setVerticalZoom(true);
        panel.setMouseZoomable(true, true);
        panel.getChart().setBackgroundPaint(WHITE);
        return panel;
    }
    
    JFreeChart createChart(NetViewer state) {
        Plot plot = createPlot(state);
        JFreeChart chart = new JFreeChart("I2P network performance", Font.getFont("arial"), plot, true);
        return chart;
    }
    
    void updateChart(ChartPanel panel, NetViewer state) {
        XYPlot plot = (XYPlot)panel.getChart().getPlot();
        plot.setDataset(getCollection(state));
    }
    
    Plot createPlot(NetViewer state) {
        XYItemRenderer renderer = new XYLineAndShapeRenderer(); // new XYDotRenderer(); // 
        XYPlot plot = new XYPlot(getCollection(state), new DateAxis(), new NumberAxis("val"), renderer);
        return plot;
    }
    
    XYSeriesCollection getCollection(NetViewer state) {
        XYSeriesCollection col = new XYSeriesCollection();
        if (state != null) {
            List names = state.getConfigNames();
            for (int i = 0; i < names.size(); i++) {
                addPeer(col, state.getConfig((String)names.get(i)));
            }
        } else {
            XYSeries series = new XYSeries("latency", false, false);
            series.add(System.currentTimeMillis(), 0);
            col.addSeries(series);
        }
        return col;
    }
    
    void addPeer(XYSeriesCollection col, PeerPlotConfig config) {
        Set statNames = config.getSummary().getStatNames();
        for (Iterator iter = statNames.iterator(); iter.hasNext(); ) {
            String statName = (String)iter.next();
            List statData = config.getSummary().getData(statName);
            if (statData.size() > 0) {
                PeerStat data = (PeerStat)statData.get(0);
                String args[] = data.getValueDescriptions();
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        if (config.getSeriesConfig().getShouldPlotValue(statName, args[i], false))
                            addLine(col, config.getPeerName(), statName, args[i], statData);
                    }
                }
            }
        }
    }
    
    /**
     */
    void addLine(XYSeriesCollection col, String peer, String statName, String argName, List dataPoints) {
        XYSeries series = new XYSeries(peer.substring(0, 4) + ": " + argName, false, false);
        for (int i = 0; i < dataPoints.size(); i++) {
            PeerStat data = (PeerStat)dataPoints.get(i);
            String argNames[] = data.getValueDescriptions();
            int argIndex = -1;
            for (int j = 0; j < argNames.length; j++) {
                if (argNames[j].equals(argName)) {
                    argIndex = j;
                    break;
                }
            }
            if (data.getIsDouble())
                series.add(data.getSampleDate(), data.getDoubleValues()[argIndex]);
            else
                series.add(data.getSampleDate(), data.getLongValues()[argIndex]);
        }
        col.addSeries(series);
    }
}