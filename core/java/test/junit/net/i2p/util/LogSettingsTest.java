package net.i2p.util;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.Properties;

import junit.framework.TestCase;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;


/**
 * @author Comwiz
 */
public class LogSettingsTest extends TestCase {


    private Properties p;
    private Log log;
    private I2PAppContext _context;
    private File f;

    private String origMinimumOnScreenLevel;
    private String origLogSettings;
    

    /**
     * Sets up the test fixture.
     *
     * Called before every test case method.
     */
    protected void setUp() throws IOException {

        _context = I2PAppContext.getGlobalContext();
        log = _context.logManager().getLog(LogSettingsTest.class);
        p = new Properties();
        f = new File("logger.config");
        if(!f.exists()){
            FileWriter temp = new FileWriter(f);
            temp.close();
        }
        DataHelper.loadProps(p, f);
        origMinimumOnScreenLevel = p.getProperty("logger.record.net.i2p.util.LogSettings", Log.STR_ERROR);
        origLogSettings = p.getProperty("logger.minimumOnScreenLevel", Log.STR_CRIT);
    }

    protected void tearDown() throws IOException{
	    p.setProperty("logger.record.net.i2p.util.LogSettings", origMinimumOnScreenLevel);
        p.setProperty("logger.minimumOnScreenLevel", origLogSettings);
        DataHelper.storeProps(p, f);
        
        System.gc();
    }

    public void testDebug() throws IOException {
        p.setProperty("logger.record.net.i2p.util.LogSettings", Log.toLevelString(Log.DEBUG));
        p.setProperty("logger.minimumOnScreenLevel", Log.toLevelString(Log.DEBUG));
        
        DataHelper.storeProps(p, f);
        
        _context.logManager().rereadConfig();
        
        PipedInputStream pin = new PipedInputStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(pin));
        
        PrintStream systemOut = System.out;
        PrintStream pout = new PrintStream(new PipedOutputStream(pin));
        
        System.setOut(pout);

        try {
            log.debug("DEBUG" + ": debug");
            log.info("DEBUG" + ": info");
            log.warn("DEBUG" + ": warn");
            log.error("DEBUG" + ": error");
            log.log(Log.CRIT, "DEBUG" + ": crit");

            // Wait for the LogWriter to flush, then write extra stuff so
            // the test doesn't hang on failure
            try { Thread.sleep(12*1000); } catch (InterruptedException ie) {}
            for (int i = 0; i < 5; i++)
                 pout.println("");
            pout.flush();
            String l1 = in.readLine();
            String l2 = in.readLine();
            String l3 = in.readLine();
            String l4 = in.readLine();
            String l5 = in.readLine();
        
            assertTrue(
                l1.matches(".*DEBUG: debug") &&
                l2.matches(".*DEBUG: info") &&
                l3.matches(".*DEBUG: warn") &&
                l4.matches(".*DEBUG: error") &&
                l5.matches(".*DEBUG: crit")
            );    
        } finally {
            System.setOut(systemOut);
            pout.close();
        }

        
    }

    public void testInfo() throws IOException {
        p.setProperty("logger.record.net.i2p.util.LogSettings", Log.toLevelString(Log.INFO));
        p.setProperty("logger.minimumOnScreenLevel", Log.toLevelString(Log.DEBUG));
        
    	DataHelper.storeProps(p, f);
        _context.logManager().rereadConfig();
        
        PipedInputStream pin = new PipedInputStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(pin));
        
        PrintStream systemOut = System.out;
        PrintStream pout = new PrintStream(new PipedOutputStream(pin));
        
        System.setOut(pout);
        
        try {
            log.debug("INFO" + ": debug");
            log.info("INFO" + ": info");
            log.warn("INFO" + ": warn");
            log.error("INFO" + ": error");
            log.log(Log.CRIT, "INFO" + ": crit");

            // Wait for the LogWriter to flush, then write extra stuff so
            // the test doesn't hang on failure
            try { Thread.sleep(12*1000); } catch (InterruptedException ie) {}
            for (int i = 0; i < 4; i++)
                 pout.println("");
            pout.flush();
            String l1 = in.readLine();
            String l2 = in.readLine();
            String l3 = in.readLine();
            String l4 = in.readLine();
        
            assertTrue(
                l1.matches(".*INFO: info") &&
                l2.matches(".*INFO: warn") &&
                l3.matches(".*INFO: error") &&
                l4.matches(".*INFO: crit")
            );
        } finally {
            System.setOut(systemOut);
            pout.close();
        }


    }

    public void testWarn() throws IOException {
        p.setProperty("logger.record.net.i2p.util.LogSettings", Log.toLevelString(Log.WARN));
        p.setProperty("logger.minimumOnScreenLevel", Log.toLevelString(Log.DEBUG));
        
    	DataHelper.storeProps(p, f);
        _context.logManager().rereadConfig();
        
        PipedInputStream pin = new PipedInputStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(pin));
        
        PrintStream systemOut = System.out;
        PrintStream pout = new PrintStream(new PipedOutputStream(pin));
        
        System.setOut(pout);
        
        try {
            log.debug("WARN" + ": debug");
            log.info("WARN" + ": info");
            log.warn("WARN" + ": warn");
            log.error("WARN" + ": error");
            log.log(Log.CRIT, "WARN" + ": crit");

            // Wait for the LogWriter to flush, then write extra stuff so
            // the test doesn't hang on failure
            try { Thread.sleep(12*1000); } catch (InterruptedException ie) {}
            for (int i = 0; i < 3; i++)
                 pout.println("");
            pout.flush();
            String l1 = in.readLine();
            String l2 = in.readLine();
            String l3 = in.readLine();
        
            assertTrue(
                l1.matches(".*WARN: warn") &&
                l2.matches(".*WARN: error") &&
                l3.matches(".*WARN: crit")
            );
        } finally {
            System.setOut(systemOut);
            pout.close();
        }

    }

    public void testError() throws IOException{
        p.setProperty("logger.record.net.i2p.util.LogSettings", Log.toLevelString(Log.ERROR));
        p.setProperty("logger.minimumOnScreenLevel", Log.toLevelString(Log.DEBUG));
        
    	DataHelper.storeProps(p, f);
        _context.logManager().rereadConfig();
        
        PipedInputStream pin = new PipedInputStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(pin));
        
        PrintStream systemOut = System.out;
        PrintStream pout = new PrintStream(new PipedOutputStream(pin));
        
        System.setOut(pout);
        
        try {
            log.debug("ERROR" + ": debug");
            log.info("ERROR" + ": info");
            log.warn("ERROR" + ": warn");
            log.error("ERROR" + ": error");
            log.log(Log.CRIT, "ERROR" + ": crit");

            // Wait for the LogWriter to flush, then write extra stuff so
            // the test doesn't hang on failure
            try { Thread.sleep(12*1000); } catch (InterruptedException ie) {}
            for (int i = 0; i < 2; i++)
                 pout.println("");
            pout.flush();
            String l1 = in.readLine();
            String l2 = in.readLine();
        
            assertTrue(
                l1.matches(".*ERROR: error") &&
                l2.matches(".*ERROR: crit")
            );
        } finally {
            System.setOut(systemOut);
            pout.close();
        }

    }

    public void testCrit() throws IOException {
        p.setProperty("logger.record.net.i2p.util.LogSettings", Log.toLevelString(Log.CRIT));
        p.setProperty("logger.minimumOnScreenLevel", Log.toLevelString(Log.DEBUG));
        
    	DataHelper.storeProps(p, f);
        _context.logManager().rereadConfig();
        
        PipedInputStream pin = new PipedInputStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(pin));
        
        PrintStream systemOut = System.out;
        PrintStream pout = new PrintStream(new PipedOutputStream(pin));
        
        System.setOut(pout);
        
        try {
            log.debug("CRIT" + ": debug");
            log.info("CRIT" + ": info");
            log.warn("CRIT" + ": warn");
            log.error("CRIT" + ": error");
            log.log(Log.CRIT, "CRIT" + ": crit");

            // Wait for the LogWriter to flush, then write extra stuff so
            // the test doesn't hang on failure
            try { Thread.sleep(12*1000); } catch (InterruptedException ie) {}
            pout.println("");
            pout.flush();
            String l1 = in.readLine();
        
            assertTrue(
                l1.matches(".*CRIT: crit")
            );
        } finally {
            System.setOut(systemOut);
            pout.close();
        }

    }

    
}
