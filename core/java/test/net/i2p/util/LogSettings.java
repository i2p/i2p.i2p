package net.i2p.util;

import java.util.Properties;
import java.io.File;
import java.io.FileOutputStream;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;

/**
 * Check to make sure the updates to the logger.config are 
 * honored.
 *
 */
public class LogSettings {
    private static I2PAppContext _context;
    
    public static void main(String args[]) {
        _context = I2PAppContext.getGlobalContext();
        Log log = _context.logManager().getLog(LogSettings.class);
        for (int i = 0; i < 2; i++) {
            setLevel(Log.DEBUG);
            test("DEBUG", log);
            setLevel(Log.INFO);
            test("INFO", log);
            setLevel(Log.WARN);
            test("WARN", log);
            setLevel(Log.ERROR);
            test("ERROR", log);
            setLevel(Log.CRIT);
            test("CRIT", log);
        }
    }
    
    private static void setLevel(int level) {
        try {
            Properties p = new Properties();
            File f = new File("logger.config");
            DataHelper.loadProps(p, f);
            p.setProperty("logger.record.net.i2p.util.LogSettings", Log.toLevelString(level));
            DataHelper.storeProps(p, f);
            try { Thread.sleep(90*1000); } catch (InterruptedException ie) {}
            //_context.logManager().rereadConfig();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void test(String setting, Log log) {
        log.debug(setting + ": debug");
        log.info(setting + ": info");
        log.warn(setting + ": warn");
        log.error(setting + ": error");
        log.log(Log.CRIT, setting + ": crit");
    }
}
