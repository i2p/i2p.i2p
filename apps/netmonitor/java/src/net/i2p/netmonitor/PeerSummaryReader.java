package net.i2p.netmonitor;

import net.i2p.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.Set;
import java.util.Locale;
import java.util.Date;
import java.util.StringTokenizer;
import java.text.SimpleDateFormat;
import java.text.ParseException;

/**
 * Load up the peer summary 
 *
 */
class PeerSummaryReader {
    private static final Log _log = new Log(PeerSummaryReader.class);
    private static final PeerSummaryReader _instance = new PeerSummaryReader();
    public static final PeerSummaryReader getInstance() { return _instance; }
    private PeerSummaryReader() {}
    
    /**  */
    public void read(NetMonitor monitor, InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String line = null;
        PeerSummary summary = null;
        String curDescription = null;
        List curArgs = null;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("peer\t")) {
                String name = line.substring("peer\t".length()).trim();
                summary = monitor.getSummary(name);
                if (summary == null)
                    summary = new PeerSummary(name);
            } else if (line.startsWith("## ")) {
                curDescription = line.substring("## ".length()).trim();
                curArgs = new ArrayList(4);
            } else if (line.startsWith("# param ")) {
                int start = line.indexOf(':');
                String arg = line.substring(start+1).trim();
                curArgs.add(arg);
            } else {
                StringTokenizer tok = new StringTokenizer(line);
                String name = tok.nextToken();
                try {
                    long when = getTime(tok.nextToken());
                    boolean isDouble = false;
                    List argVals = new ArrayList(curArgs.size());
                    while (tok.hasMoreTokens()) {
                        String val = (String)tok.nextToken();
                        if (val.indexOf('.') >= 0) {
                            argVals.add(new Double(val));
                            isDouble = true;
                        } else {
                            argVals.add(new Long(val));
                        }
                    }
                    String valDescriptions[] = new String[curArgs.size()];
                    for (int i = 0; i < curArgs.size(); i++)
                        valDescriptions[i] = (String)curArgs.get(i);
                    if (isDouble) {
                        double values[] = new double[argVals.size()];
                        for (int i = 0; i < argVals.size(); i++) 
                            values[i] = ((Double)argVals.get(i)).doubleValue();
                        summary.addData(name, curDescription, valDescriptions, when, values);
                    } else {
                        long values[] = new long[argVals.size()];
                        for (int i = 0; i < argVals.size(); i++) 
                            values[i] = ((Long)argVals.get(i)).longValue();
                        summary.addData(name, curDescription, valDescriptions, when, values);
                    }
                } catch (ParseException pe) {
                    _log.error("Error parsing the data line [" + line + "]", pe);
                } catch (NumberFormatException nfe) {
                    _log.error("Error parsing the data line [" + line + "]", nfe);
                }
            }
        }
        
        if (summary == null)
            return;
        summary.coallesceData(monitor.getSummaryDurationHours() * 60*60*1000);
        monitor.addSummary(summary);
    }
    
    private static final SimpleDateFormat _fmt = new SimpleDateFormat("yyyyMMdd.HH:mm:ss.SSS", Locale.UK);

    /**
     * Converts a time (long) to text
     * @param when the time to convert
     * @return the textual representation
     */
    public long getTime(String when) throws ParseException {
        synchronized (_fmt) {
            return _fmt.parse(when).getTime();
        }
    }
}