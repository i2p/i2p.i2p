package net.i2p.netmonitor;


import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import net.i2p.util.Log;

/**
 * Load up the StatGroups from the location specified to configure the data harvester.
 * The stat groups are formatted in a simple properties file style, e.g.: <pre>
 * # dropped jobs
 * statGroup.0.name=droppedJobs
 * statGroup.0.detail.0.name=num dropped jobs (minute)
 * statGroup.0.detail.0.option=stat_jobQueue.droppedJobs.60m
 * statGroup.0.detail.0.field=3
 * statGroup.0.detail.1.name=num dropped jobs (hour)
 * statGroup.0.detail.1.option=stat_jobQueue.droppedJobs.60h
 * statGroup.0.detail.1.field=3
 * # 
 * statGroup.1.name=encryptTime
 * statGroup.1.detail.0.name=encryption time avg ms (minute)
 * statGroup.1.detail.0.option=stat_crypto.elGamal.encrypt.60s
 * statGroup.1.detail.0.field=0
 * statGroup.1.detail.1.name=num encryptions (minute)
 * statGroup.1.detail.1.option=stat_crypto.elGamal.encrypt.60s
 * statGroup.1.detail.1.field=7
 * statGroup.1.detail.2.name=encryption time avg ms (hour)
 * statGroup.1.detail.2.option=stat_crypto.elGamal.encrypt.60s
 * statGroup.1.detail.2.field=0
 * statGroup.1.detail.3.name=num encryptions (hour)
 * statGroup.1.detail.3.option=stat_crypto.elGamal.encrypt.60s
 * statGroup.1.detail.3.field=7
 * </pre>
 */
class StatGroupLoader {
    private static final Log _log = new Log(StatGroupLoader.class);
    /**
     * Load a list of stat  groups from the file specified
     *
     * @return list of StatGroup objects
     */
    public static List loadStatGroups(String filename) {
        _log.debug("Loading stat groups from " + filename);
        FileInputStream fis = null;
        File f = new File(filename);
        try {
            fis = new FileInputStream(f);
            Properties p = new Properties();
            p.load(fis);
            _log.debug("Config loaded from " + filename);
            return loadStatGroups(p);
        } catch (IOException ioe) {
            _log.error("Error loading the stat groups from " + f.getAbsolutePath(), ioe);
            return new ArrayList();
        }
    }
    
    private static List loadStatGroups(Properties props) {
        List rv = new ArrayList(8);
        int groupNum = 0;
        while (true) {
            String description = props.getProperty("statGroup." + groupNum + ".name");
            if (description == null) break;
            int detailNum = 0;
            StatGroup group = new StatGroup(description);
            while (true) {
                String detailPrefix = "statGroup." + groupNum + ".detail." + detailNum + '.';
                String name = props.getProperty(detailPrefix + "name");
                if (name == null) break;
                String option = props.getProperty(detailPrefix + "option");
                if (option == null) break;
                String field = props.getProperty(detailPrefix + "field");
                if (field == null) break;
                try {
                    int fieldNum = Integer.parseInt(field);
                    group.addStat(name, option, fieldNum);
                } catch (NumberFormatException nfe) {
                    _log.warn("Unable to parse the field number from [" + field + "]", nfe);
                    break;
                }
                detailNum++;
            }
            rv.add(group);
            groupNum++;
        }
        return rv;
    }
}