package net.i2p.router.web;

import java.io.*;
import java.util.*;

import net.i2p.stat.*;
import net.i2p.router.*;

/**
 *
 */
public class StatSummarizer implements Runnable {
    private RouterContext _context;
    /** list of SummaryListener instances */
    private List _listeners;
    private static StatSummarizer _instance;
    
    public StatSummarizer() {
        _context = (RouterContext)RouterContext.listContexts().get(0); // fuck it, only summarize one per jvm
        _listeners = new ArrayList(16);
        _instance = this;
    }
    
    public static StatSummarizer instance() { return _instance; }
    
    public void run() {
        String specs = "";
        while (_context.router().isAlive()) {
            specs = adjustDatabases(specs);
            try { Thread.sleep(60*1000); } catch (InterruptedException ie) {}
        }
    }
    
    /** list of SummaryListener instances */
    List getListeners() { return _listeners; }
    
    private static final String DEFAULT_DATABASES = "bw.sendRate.60000" +
                                                    ",bw.recvRate.60000" +
                                                    ",tunnel.testSuccessTime.60000" +
                                                    ",udp.outboundActiveCount.60000" +
                                                    ",udp.receivePacketSize.60000" +
                                                    ",udp.receivePacketSkew.60000" +
                                                    ",udp.sendConfirmTime.60000" +
                                                    ",udp.sendPacketSize.60000" +
                                                    ",router.activePeers.60000" +
                                                    ",router.activeSendPeers.60000" +
                                                    ",tunnel.acceptLoad.60000" +
                                                    ",tunnel.dropLoadProactive.60000" +
                                                    ",tunnel.buildExploratorySuccess.60000" +
                                                    ",tunnel.buildExploratoryReject.60000" +
                                                    ",tunnel.buildExploratoryExpire.60000" +
                                                    ",client.sendAckTime.60000" +
                                                    ",client.dispatchNoACK.60000" +
                                                    ",transport.sendMessageFailureLifetime.60000" +
                                                    ",transport.sendProcessingTime.60000";
    
    private String adjustDatabases(String oldSpecs) {
        String spec = _context.getProperty("stat.summaries", DEFAULT_DATABASES);
        if ( ( (spec == null) && (oldSpecs == null) ) ||
             ( (spec != null) && (oldSpecs != null) && (oldSpecs.equals(spec))) )
            return oldSpecs;
        
        List old = parseSpecs(oldSpecs);
        List newSpecs = parseSpecs(spec);
        
        // remove old ones
        for (int i = 0; i < old.size(); i++) {
            Rate r = (Rate)old.get(i);
            if (!newSpecs.contains(r))
                removeDb(r);
        }
        // add new ones
        StringBuffer buf = new StringBuffer();
        for (int i = 0; i < newSpecs.size(); i++) {
            Rate r = (Rate)newSpecs.get(i);
            if (!old.contains(r))
                addDb(r);
            buf.append(r.getRateStat().getName()).append(".").append(r.getPeriod());
            if (i + 1 < newSpecs.size())
                buf.append(',');
        }
        return buf.toString();
    }
    
    private void removeDb(Rate r) {
        for (int i = 0; i < _listeners.size(); i++) {
            SummaryListener lsnr = (SummaryListener)_listeners.get(i);
            if (lsnr.getRate().equals(r)) {
                _listeners.remove(i);
                lsnr.stopListening();
                return;
            }
        }
    }
    private void addDb(Rate r) {
        SummaryListener lsnr = new SummaryListener(r);
        _listeners.add(lsnr);
        lsnr.startListening();
        //System.out.println("Start listening for " + r.getRateStat().getName() + ": " + r.getPeriod());
    }
    public boolean renderPng(Rate rate, OutputStream out) throws IOException { 
        return renderPng(rate, out, -1, -1, false, false, false, false, -1, true); 
    }
    public boolean renderPng(Rate rate, OutputStream out, int width, int height, boolean hideLegend, boolean hideGrid, boolean hideTitle, boolean showEvents, int periodCount, boolean showCredit) throws IOException {
        for (int i = 0; i < _listeners.size(); i++) {
            SummaryListener lsnr = (SummaryListener)_listeners.get(i);
            if (lsnr.getRate().equals(rate)) {
                lsnr.renderPng(out, width, height, hideLegend, hideGrid, hideTitle, showEvents, periodCount, showCredit);
                return true;
            }
        }
        return false;
    }
    public boolean renderPng(OutputStream out, String templateFilename) throws IOException {
        SummaryRenderer.render(_context, out, templateFilename);
        return true;
    }
    public boolean getXML(Rate rate, OutputStream out) throws IOException {
        for (int i = 0; i < _listeners.size(); i++) {
            SummaryListener lsnr = (SummaryListener)_listeners.get(i);
            if (lsnr.getRate().equals(rate)) {
                lsnr.getData().exportXml(out);
                out.write(("<!-- Rate: " + lsnr.getRate().getRateStat().getName() + " for period " + lsnr.getRate().getPeriod() + " -->\n").getBytes());
                out.write(("<!-- Average data soure name: " + lsnr.getName() + " event count data source name: " + lsnr.getEventName() + " -->\n").getBytes());
                return true;
            }
        }
        return false;
    }
    
    /**
     * @param specs statName.period,statName.period,statName.period
     * @return list of Rate objects
     */
    private List parseSpecs(String specs) {
        StringTokenizer tok = new StringTokenizer(specs, ",");
        List rv = new ArrayList();
        while (tok.hasMoreTokens()) {
            String spec = tok.nextToken();
            int split = spec.lastIndexOf('.');
            if ( (split <= 0) || (split + 1 >= spec.length()) )
                continue;
            String name = spec.substring(0, split);
            String per = spec.substring(split+1);
            long period = -1;
            try { 
                period = Long.parseLong(per); 
                RateStat rs = _context.statManager().getRate(name);
                if (rs != null) {
                    Rate r = rs.getRate(period);
                    if (r != null)
                        rv.add(r);
                }
            } catch (NumberFormatException nfe) {}
        }
        return rv;
    }
}
