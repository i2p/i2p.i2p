package net.i2p.heartbeat.gui;

import java.awt.Color;
import java.awt.Font;
import java.util.List;

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

import net.i2p.heartbeat.PeerData;
import net.i2p.util.Log;

class JFreeChartAdapter {
    private final static Log _log = new Log(JFreeChartAdapter.class);
    private final static Color WHITE = new Color(255, 255, 255);
    
    ChartPanel createPanel(HeartbeatMonitorState state) {
        ChartPanel panel = new ChartPanel(createChart(state));
        panel.setDisplayToolTips(true);
        panel.setEnforceFileExtensions(true);
        panel.setHorizontalZoom(true);
        panel.setVerticalZoom(true);
        panel.setMouseZoomable(true, true);
        panel.getChart().setBackgroundPaint(WHITE);
        return panel;
    }
    
    JFreeChart createChart(HeartbeatMonitorState state) {
        Plot plot = createPlot(state);
        JFreeChart chart = new JFreeChart("I2P Heartbeat performance", Font.getFont("arial"), plot, true);
        return chart;
    }
    
    void updateChart(ChartPanel panel, HeartbeatMonitorState state) {
        XYPlot plot = (XYPlot)panel.getChart().getPlot();
        plot.setDataset(getCollection(state));
        updateLines(plot, state);
    }
    
    private long getFirst(HeartbeatMonitorState state) {
        long first = -1;
        for (int i = 0; i < state.getTestCount(); i++) {
            List dataPoints = state.getTest(i).getCurrentData().getDataPoints();
            if ( (dataPoints != null) && (dataPoints.size() > 0) ) {
                PeerData.EventDataPoint data = (PeerData.EventDataPoint)dataPoints.get(0);
                if ( (first < 0) || (first > data.getPingSent()) )
                    first = data.getPingSent();
            }
        }
        return first;
    }
    
    Plot createPlot(HeartbeatMonitorState state) {
        XYItemRenderer renderer = new XYLineAndShapeRenderer(); // new XYDotRenderer(); // 
        XYPlot plot = new XYPlot(getCollection(state), new DateAxis(), new NumberAxis("ms"), renderer);
        updateLines(plot, state);
        return plot;
    }
    
    private void updateLines(XYPlot plot, HeartbeatMonitorState state) {
        if (true) return;
        if (state == null) return;
        for (int i = 0; i < state.getTestCount(); i++) {
            PeerPlotConfig config = state.getTest(i).getPlotConfig();
            PeerPlotConfig.PlotSeriesConfig curConfig = config.getCurrentSeriesConfig();
            XYSeriesCollection col = ((XYSeriesCollection)plot.getDataset());
            for (int j = 0; j < col.getSeriesCount(); j++) {
                //XYItemRenderer renderer = plot.getRendererForDataset(col.getSeries(j));
                XYItemRenderer renderer = plot.getRendererForDataset(col);
                if (col.getSeriesName(j).startsWith(config.getTitle() + " send")) {
                    if (curConfig.getPlotSendTime()) {
                        //renderer.setPaint(curConfig.getPlotLineColor());
                    }
                }
                if (col.getSeriesName(j).startsWith(config.getTitle() + " receive")) {
                    if (curConfig.getPlotReceiveTime()) {
                        //renderer.setPaint(curConfig.getPlotLineColor());
                    }
                }
                if (col.getSeriesName(j).startsWith(config.getTitle() + " lost")) {
                    if (curConfig.getPlotLostMessages()) {
                        //renderer.setPaint(curConfig.getPlotLineColor());
                    }
                }
            }
        }
    }
    
    XYSeriesCollection getCollection(HeartbeatMonitorState state) {
        XYSeriesCollection col = new XYSeriesCollection();
        if (state != null) {
            for (int i = 0; i < state.getTestCount(); i++) {
                addTest(col, state.getTest(i));
            }
        } else {
            XYSeries series = new XYSeries("latency", false, false);
            series.add(System.currentTimeMillis(), 0);
            col.addSeries(series);
        }
        return col;
    }
    
    void addTest(XYSeriesCollection col, PeerPlotState state) {
        PeerPlotConfig config = state.getPlotConfig();
        PeerPlotConfig.PlotSeriesConfig curConfig = config.getCurrentSeriesConfig();
        addLines(col, curConfig, config.getTitle(), state.getCurrentData().getDataPoints());
        addAverageLines(col, config, config.getTitle(), state.getCurrentData());
    }
    
    /**
     * @param config preferences for how to display this test
     * @param lineName minimal name of the test (e.g. "jxHa.32KB.60s")
     * @param data List of PeerData.EventDataPoint describing all of the events in the test
     */
    void addLines(XYSeriesCollection col, PeerPlotConfig.PlotSeriesConfig config, String lineName, List data) {
        if (config.getPlotSendTime()) {
            XYSeries sendSeries = getSendSeries(data);
            sendSeries.setName(lineName + " send");
            sendSeries.setDescription("milliseconds for the ping to reach the peer");
            col.addSeries(sendSeries);
        }
        if (config.getPlotReceiveTime()) {
            XYSeries recvSeries = getReceiveSeries(data);
            recvSeries.setName(lineName + " receive");
            recvSeries.setDescription("milliseconds for the peer's pong to reach the sender");
            col.addSeries(recvSeries);
        }
        if (config.getPlotLostMessages()) {
            XYSeries lostSeries = getLostSeries(data);
            lostSeries.setName(lineName + " lost");
            lostSeries.setDescription("number of ping/pong messages lost");
            col.addSeries(lostSeries);
        }
    }
    
    /**
     * Add a data series for each average that we're configured to render
     *
     * @param config preferences for how to display this test
     * @param lineName minimal name of the test (e.g. "jxHa.32KB.60s")
     * @param data List of PeerData.EventDataPoint describing all of the events in the test
     */
    void addAverageLines(XYSeriesCollection col, PeerPlotConfig config, String lineName, StaticPeerData data) {
        if (data.getDataPointCount() <= 0) return;
        PeerData.EventDataPoint start = (PeerData.EventDataPoint)data.getDataPoints().get(0);
        PeerData.EventDataPoint finish = (PeerData.EventDataPoint)data.getDataPoints().get(data.getDataPointCount()-1);

        List configs = config.getAverageSeriesConfigs();

        for (int i = 0; i < configs.size(); i++) {
            PeerPlotConfig.PlotSeriesConfig cfg = (PeerPlotConfig.PlotSeriesConfig)configs.get(i);
            int minutes = (int)cfg.getPeriod()/(60*1000);
            if (cfg.getPlotSendTime()) {
                double time = data.getAverageSendTime(minutes);
                if (time > 0) {
                    XYSeries series = new XYSeries(lineName + " send " + minutes + "m avg [" + time + "]", false, false);
                    series.add(start.getPingSent(), time);
                    series.add(finish.getPingSent(), time);
                    series.setDescription("send time, averaged over the last " + minutes + " minutes");
                    col.addSeries(series);
                }
            }
            if (cfg.getPlotReceiveTime()) {
                double time = data.getAverageReceiveTime(minutes);
                if (time > 0) {
                    XYSeries series = new XYSeries(lineName + " receive " + minutes + "m avg[" + time + "]", false, false);
                    series.add(start.getPingSent(), time);
                    series.add(finish.getPingSent(), time);
                    series.setDescription("receive time, averaged over the last " + minutes + " minutes");
                    col.addSeries(series);
                }
            }
            if (cfg.getPlotLostMessages()) {
                double num = data.getLostMessages(minutes);
                if (num > 0) {
                    XYSeries series = new XYSeries(lineName + " lost messages (" + num + " in " + minutes + "m)", false, false);
                    series.add(start.getPingSent(), num);
                    series.add(finish.getPingSent(), num);
                    series.setDescription("number of messages lost in the last " + minutes + " minutes");
                    col.addSeries(series);
                }
            }
        }
    }
    
    XYSeries getSendSeries(List data) {
        XYSeries series = new XYSeries("sent", false, false);
        for (int i = 0; i < data.size(); i++) {
            PeerData.EventDataPoint point = (PeerData.EventDataPoint)data.get(i);
            if (point.getWasPonged()) {
                series.add(point.getPingSent(), point.getPongSent()-point.getPingSent());
            } else {
                // series.add(data.getPingSent(), 0);
            }
        }
        return series;
    }
    
    XYSeries getReceiveSeries(List data) {
        XYSeries series = new XYSeries("receive", false, false);
        for (int i = 0; i < data.size(); i++) {
            PeerData.EventDataPoint point = (PeerData.EventDataPoint)data.get(i);
            if (point.getWasPonged()) {
                series.add(point.getPingSent(), point.getPongReceived()-point.getPongSent());
            } else {
                // series.add(data.getPingSent(), 0);
            }
        }
        return series;
    }
    XYSeries getLostSeries(List data) {
        XYSeries series = new XYSeries("lost", false, false);
        for (int i = 0; i < data.size(); i++) {
            PeerData.EventDataPoint point = (PeerData.EventDataPoint)data.get(i);
            if (point.getWasPonged()) {
                //series.add(point.getPingSent(), 0);
            } else {
                series.add(point.getPingSent(), 1);
            }
        }
        return series;
    }
}