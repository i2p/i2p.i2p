package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashMap;
import java.util.Iterator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;

import net.i2p.util.Log;

/**
 * Test harness for loading / storing data structures
 *
 * @author jrandom
 */
public class TestData {
    private final static Log _log = new Log(TestData.class);
    private final static String HELP = "\nUsage: TestData generate objectType outFile\n" +
                                       "       TestData display objectType inFile\n" +
                                       "       TestData test objectType tempFile\n" +
                                       "Known types: ";
    
    private final static String OP_GENERATE = "generate";
    private final static String OP_DISPLAY = "display";
    private final static String OP_TEST = "test";
    
    private final static HashMap _generators;
    private final static HashMap _printers;
    static {
        _generators = new HashMap();
        _generators.put("NullType", new TestDataGenerator() { public byte[] getData() { return new byte[1]; } });
        
        _printers = new HashMap();
        _printers.put("NullType", new TestDataPrinter() { public String testData(InputStream in) { return "Null data read successfully"; } });
    }
    
    static void registerTest(StructureTest test, String name) {
        registerGenerator(test, name);
        registerPrinter(test, name);
    }
    static void registerGenerator(TestDataGenerator test, String name) {
        _generators.put(name, test);
    }
    static void registerPrinter(TestDataPrinter test, String name) {
        _printers.put(name, test);
    }
    
    public static void main(String args[]) {
        if (args.length < 1) {
            showHelp();
            return;
        }
        
        if (OP_GENERATE.equalsIgnoreCase(args[0])) {
            validateTest(args[1]);
            if (args.length != 3) {
                showHelp();
                return;
            }
            generate(args[1], args[2]);
            return;
        } else if (OP_DISPLAY.equalsIgnoreCase(args[0])) {
            validateTest(args[1]);
            if (args.length != 3) {
                showHelp();
                return;
            }
            display(args[1], args[2]);
        } else if (OP_TEST.equalsIgnoreCase(args[0])) {
            validateTest(args[1]);
            if (args.length != 3) {
                showHelp();
                return;
            }
            generate(args[1], args[2]);
            display(args[1], args[2]);
        } else {
            showHelp();
        }
	try { Thread.sleep(2000); } catch (InterruptedException ie) {}
    }
    
    private static void validateTest(String objectType) {
        try {
            String clsName = TestData.class.getPackage().getName() + "." + objectType + "Test";
            Class.forName(clsName);
        } catch (Throwable t) {
            _log.error("Error validating the object type", t);
        }
    }
    
    public static void generate(String objectType, String outFile) {
        TestDataGenerator gen = (TestDataGenerator)_generators.get(objectType);
        byte[] data = gen.getData();
	if (data == null) {
	    _log.error("Error generating the data.  fail");
	    return;
	}
        try {
            File f = new File(outFile);
            FileOutputStream out = new FileOutputStream(f);
            out.write(data);
            out.flush();
            out.close();
            _log.debug("Wrote the file out to " + f.getAbsolutePath());
        } catch (IOException ioe) {
            _log.error("Error writing out the object", ioe);
        }
    }
    
    public static void display(String type, String inFile) {
        try {
            File f = new File(inFile);
            FileInputStream in = new FileInputStream(f);
            TestDataPrinter printer = (TestDataPrinter)_printers.get(type);
            String display = printer.testData(in);
            in.close();
            _log.info("Displaying " + inFile + " of type: " + type);
            _log.info(display);
        } catch (IOException ioe) {
            _log.error("Error reading the file to display", ioe);
        }
    }
        
    private static String listTypes() {
        StringBuffer buf = new StringBuffer();
        for (Iterator iter = _generators.keySet().iterator(); iter.hasNext(); ) {
            String type = (String)iter.next();
            buf.append(type);
            if (iter.hasNext())
                buf.append(", ");
        }
        return buf.toString();
    }
    
    public static void showHelp() {
        _log.info(HELP+listTypes());
    }
}
