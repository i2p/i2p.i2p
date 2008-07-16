package net.i2p.stat;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;


/**
 * Simple CLI to splot the stat logs into per-stat files containing
 * #seconds since beginning and the value (ready for loading into your
 * favorite plotting tool)
 */
public class StatLogSplitter {
    private static final String DATE_FORMAT = "yyyyMMdd HH:mm:ss.SSS";
    private static SimpleDateFormat _fmt = new SimpleDateFormat(DATE_FORMAT);
    public static void main(String args[]) {
        if (args.length != 1) {
            System.err.println("Usage: StatLogSplitter filename");
            return;
        }
        splitLog(args[0]);
    }
    
    private static void splitLog(String filename) {
        Map outputFiles = new HashMap(4);
        try {
            BufferedReader in = new BufferedReader(new FileReader(filename));
            String line;
            long first = 0;
            while ( (line = in.readLine()) != null) {
                String date = line.substring(0, DATE_FORMAT.length()).trim();
                int endGroup = line.indexOf(' ', DATE_FORMAT.length()+1);
                int endStat = line.indexOf(' ', endGroup+1);
                int endValue = line.indexOf(' ', endStat+1);
                String group = line.substring(DATE_FORMAT.length()+1, endGroup).trim();
                String stat = line.substring(endGroup, endStat).trim();
                String value = line.substring(endStat, endValue).trim();
                String duration = line.substring(endValue).trim();
                //System.out.println(date + " " + group + " " + stat + " " + value + " " + duration);
                
                try {
                    Date when = _fmt.parse(date);
                    if (first <= 0) first = when.getTime();
                    long val = Long.parseLong(value);
                    long time = Long.parseLong(duration);
                    if (!outputFiles.containsKey(stat)) {
                        outputFiles.put(stat, new FileWriter(stat + ".dat"));
                        System.out.println("Including data to " + stat + ".dat");
                    }
                    FileWriter out = (FileWriter)outputFiles.get(stat);
                    double s = (when.getTime()-first)/1000.0;
                    //long s = when.getTime();
                    out.write(s + " " + val + " [" + line + "]\n");
                    out.flush();
                } catch (ParseException pe) {
                    continue;
                } catch (NumberFormatException nfe){
                    continue;
                }
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        for (Iterator iter = outputFiles.values().iterator(); iter.hasNext(); ) {
            FileWriter out = (FileWriter)iter.next();
            try { out.close(); } catch (IOException ioe) {}
        }
    }
}
