package net.i2p.netmonitor;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import net.i2p.util.Log;

/**
 * Dump various peer summaries to disk (so external apps (or good ol' vi) can
 * peek into what we're harvesting
 *
 */
class PeerSummaryWriter {
    private static final Log _log = new Log(PeerSummaryWriter.class);
    private static final PeerSummaryWriter _instance = new PeerSummaryWriter();
    public static final PeerSummaryWriter getInstance() { return _instance; }
    private PeerSummaryWriter() {}
    
    /** write out the peer summary to the stream specified */
    public void write(PeerSummary summary, OutputStream out) throws IOException {
        StringBuffer buf = new StringBuffer(4*1024);
        buf.append("peer\t").append(summary.getPeer()).append('\n');
        TreeSet names = new TreeSet(summary.getStatNames());
        for (Iterator iter = names.iterator(); iter.hasNext(); ) {
            String statName = (String)iter.next();
            List stats = summary.getData(statName);
            for (int i = 0; i < stats.size(); i++) {
                PeerStat stat = (PeerStat)stats.get(i);
                if (i == 0) {
                    buf.append("## ").append(stat.getDescription()).append('\n');
                    String descr[] = stat.getValueDescriptions();
                    if (descr != null) {
                        for (int j = 0; j < descr.length; j++)
                            buf.append("# param ").append(j).append(": ").append(descr[j]).append('\n');
                    }
                }
                buf.append(statName).append('\t');
                buf.append(getTime(stat.getSampleDate())).append('\t');
                
                if (stat.getIsDouble()) {
                    double vals[] = stat.getDoubleValues();
                    if (vals != null) {
                        for (int j = 0; j < vals.length; j++)
                            buf.append(vals[j]).append('\t');
                    }
                } else {
                    long vals[] = stat.getLongValues();
                    if (vals != null) {
                        for (int j = 0; j < vals.length; j++)
                            buf.append(vals[j]).append('\t');
                    }
                }
                buf.append('\n');
            }
        }
        String data = buf.toString();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Stat: \n" + data);
        out.write(data.getBytes());
    }
    
    private static final SimpleDateFormat _fmt = new SimpleDateFormat("yyyyMMdd.HH:mm:ss.SSS", Locale.UK);

    /**
     * Converts a time (long) to text
     * @param when the time to convert
     * @return the textual representation
     */
    public String getTime(long when) {
        synchronized (_fmt) {
            return _fmt.format(new Date(when));
        }
    }
}