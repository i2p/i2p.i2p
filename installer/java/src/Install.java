/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

/**
 * Please could someone write a real installer?  Please.
 * I should be shot for doing this.
 *
 * Note: this has dependencies upon how i2p/build.xml executes the "installer"
 * task - namely that this class is in the jar "install.jar", and in that jar
 * are the files COPYING, i2p.jar, i2ptunnel.jar, phttprelay.war, readme.txt,
 * hosts.txt, and router.jar.
 *
 * Can you say "fragile code"?  
 * (oh well, enough caveats.  commit)
 */
public abstract class Install {
    private File _installDir;
    private String _externalAddress;
    private boolean _externalAddressIsReachable;
    private int _inTCP;
    private int _i2cpPort;
    private String _phttpRegister;
    private String _phttpSend;
    private int _inBPS;
    private int _outBPS;
    private HashMap _answers;
    private Properties _p;
    
    private boolean _isWindows;

    /**
     * Show some installation status.
     */
    public abstract void showStatus(String s);
    
    /**
     * Show an error when setting an option.
     */
    public abstract void showOptError(String s);

    /**
     * Show some information about the following/preceding options
     */
    public abstract void handleOptInfo(String s);

    /**
     * Start a new Option category
     */
    public abstract void startOptCategory(String s);


    /**
     * Handle an option.
     */
    public abstract void handleOption(int number, String question,
				      String def, String type);
    /**
     * Tell the installer that all options have been given.
     * Install will not continue until this method has ended.
     * When this method has ended, all options must have been set.
     */
    public abstract void finishOptions();

    /**
     * Confirm an option. This can occur both while verifying options
     * and later in the install process.
     */
    public abstract boolean confirmOption(String question, boolean defaultYes);
    
    public static void main(String args[]) {
        Install install = new CliInstall();
        install.runInstall();
    }
    
    public Install() {
        _inTCP = -2;
        _i2cpPort = -2;
        _phttpRegister = null;
        _phttpSend = null;
        _inBPS = -2;
        _outBPS = -2;
        _externalAddressIsReachable = false;
    }
    
    public void runInstall() {
        askQuestions();
        detectOS();
        configureAll();
        createConfigFile();
        createLoggerConfig();
        createStartScript();
        createReseedScript();
        //createScripts("startIrcProxy.sh", "startIrcProxy.bat", 6668, "irc.duck.i2p", "log-irc-#.txt", "IRC Proxy", "IRC proxying scripts written to startIrcProxy", "Starting IRC proxy (when you see Ready! you can connect your IRC client to localhost:6668)");
        //createScripts("startI2PCVSProxy.sh", "startI2PCVSProxy.bat", 2401, "i2pcvs.i2p", "log-i2pcvs-#.txt", "CVS Proxy", "Proxying scripts for I2P's CVS server written to startCVSProxy");
        // only pulling them temporarily, duck, until the network is
        // reliable enough
        //createScripts("startJabber.sh", "startJabber.bat", 5222, "jabber.duck.i2p", "log-jabber-#.txt", "Jabber Proxy", "Squid proxying scripts written to startSquid");
        //createScripts("startNntpProxy.sh", "startNntpProxy.bat", 1119, "nntp.duck.i2p", "log-nntp-#.txt", "NNTP Proxy","NNTP proxying scripts written to startNntpProxy");
        createSeedNodes();
        copyLibraries();
        if (_isWindows) {
            showStatus("To run the router, please run startRouter.bat in " + _installDir.getAbsolutePath());
        } else {
            showStatus("To run the router, please run startRouter.sh in " + _installDir.getAbsolutePath());
        }
        showStatus("");
    }

    private String numberTo4Digits(int number) {
        String res = "0000"+number; // use four digit indices
        return res.substring(res.length()-4);
    }
    
    private void askQuestions() {
        try {
            InputStream in = Install.class.getResourceAsStream("/install.config");
            _p = new Properties();
            _p.load(in);
            in.close();
        } catch (IOException ex) {
            ex.printStackTrace();
            System.exit(1);
        }
        int count = Integer.parseInt(_p.getProperty("qs.count"));
        _answers = new HashMap(count+count); // load factor is 0.75, so
                                             // there is some room left
        for (int i=1;i<=count;i++) {
            String ii = numberTo4Digits(i); 
            String question = _p.getProperty("qs."+ii+".question"),
            param = _p.getProperty("qs."+ii+".param"),
            type = _p.getProperty("qs."+ii+".type"),
            def = _p.getProperty("qs."+ii+".default");
            if (question == null) continue;
            if (type == null || "info".equals(type)) {
                // just print some text
                handleOptInfo(question);
            } else if ("info_spliced".equals(type)) {
                // splice in some params already queried
                handleOptInfo(handleSpliceParams(question));
            } else if ("category".equals(type)) {
                startOptCategory(question);
            } else if ("skip".equals(type)) {
                i = Integer.parseInt(question)-1;
            } else { // a real question
                if ("<none>".equals(question)) {
                    if (!setOption(i, def))
                        throw new RuntimeException("Fixed and invalid value");
                } else {
                    handleOption(i,question,def,type);
                }
            }
        }
        finishOptions();
    }

    public /* overridable */ String handleSpliceParams(String s) {
        return spliceParams(s);
    }

    public boolean setOption(int number, String answer) {
        String ii = numberTo4Digits(number);
        String param = _p.getProperty("qs."+ii+".param"),
        type = _p.getProperty("qs."+ii+".type");
        Object value = getOptionValue(answer, type);
        if (value == null) {
            return false;
        } else {
            if (param == null || value == null)
                throw new NullPointerException();
            _answers.put(param,value);
            return true;
        }
    }	

    private Object getOptionValue(String answer, String type) {
        if ("string".equals(type)) {
            // everything's okay till the very end
            return answer;
        } else if ("string>0".equals(type)) {
            if (answer.length() > 0) {
                return answer;
            } else {
                showOptError("Empty answers are not allowed.");
            }
        } else if ("directory".equals(type)) {
            File f = new File(answer);
            if (f.exists()) {
                if (f.isDirectory()) {
                    showOptError("Using existing target directory " + f.getAbsolutePath());
                    return f;
                } else {
                    showOptError("Location " + f.getAbsolutePath()+
                                 " is not a directory.  "+
                                 "Lets try again");
                }
            } else {
                boolean create = confirmOption("Target directory " + f.getAbsolutePath() +
                                               " does not exist - create? ", false);
                if (!create) {
                    showOptError("Lets try that again");
                } else {
                    boolean created = f.mkdirs();
                    if (created) {
                        showOptError("Target directory " + f.getAbsolutePath() +
                                     " created");
                        return f;
                    } else {
                        showOptError("Failed to create the "+
                                     "directory.  Lets choose another.");
                    }
                }
            }
        } else if ("boolean".equals(type)) {
            answer=answer.toLowerCase();
            if ("yes".equals(answer) || "y".equals(answer))
                answer="true";
            if ("no".equals(answer) || "n".equals(answer))
                answer="false";
            if ("true".equals(answer) || "false".equals(answer)) {
                return new Boolean("true".equals(answer));
            }
            showOptError("Incorrect boolean value, try `yes' òr `no'");
        } else if ("numeric".equals(type) || "port".equals(type)) {
            try {
                int num = Integer.parseInt(answer);
                if ("numeric".equals(type) ||
                    (num >0 && num < 65536)) {
                    return new Integer(num);
                }
                showOptError("Port number must be from 1 to 65535");
            } catch (NumberFormatException ex) {
                showOptError("Incorrect value: "+ex.getMessage());
            }
        } else if ("bandwidth".equals(type)) {
            try {
                answer = answer.toLowerCase();
                int factor = 1;
                // first check to see if it ends with m, k, or g
                if (answer.endsWith("g"))
                    factor = 1024*1024*1024;
                if (answer.endsWith("m"))
                    factor = 1024*1024;
                if (answer.endsWith("k"))
                    factor = 1024;
                if (factor > 1)
                    answer = answer.substring(0, answer.length()-1);
                int val = factor * Integer.parseInt(answer);
                if (val == -1 || val >0 ) {
                    return new Integer(val);
                }
                showOptError("Value must be -1 or positive.");
            } catch (NumberFormatException ex) {
                showOptError("Invalid number [" + answer + "].  Valid numbers are of the form -1, 42, 68k, 7m, 9g");
            }
        } else {
            throw new RuntimeException ("cannot read installer option: " + type);
        }
        return null;
    }

    private String spliceParams(String s) {
        StringBuffer txt = new StringBuffer(s.length()+100);
        int ind;
        while((ind = s.indexOf("##")) != -1) {
            txt.append(s.substring(0,ind));
            String temp = s.substring(ind+2);
            ind = temp.indexOf("##");
            if (ind == -1) throw new RuntimeException("Incorrect info_spliced param");
            s=temp.substring(ind+2);
            Object value = _answers.get(temp.substring(0,ind));
            if (value == null) {
                System.err.println("ERROR: Could not insert parameter "+temp.substring(0,ind));
                System.exit(1);
            } else {
                txt.append(value.toString());
            }
        }
        txt.append(s);
        return txt.toString();
    }
    
    private void detectOS() {
        String os = System.getProperty("os.name");
        if (os.toLowerCase().indexOf("win") != -1)
            _isWindows = true;
        else
            _isWindows = false;
        // yes, this treats pre-os-x macs as unix, and perhaps some
        // windows-esque OSes don't have "win" in their name, or some
        // unix-esque OS does.  fix when it occurs.
    }

    private void configureAll() {
        _installDir = (File) _answers.get("installDir");
        _externalAddress = _answers.get("externalAddress").toString();
        _externalAddressIsReachable = ((Boolean)_answers.get("externalAddressIsReachable")).booleanValue(); 

        _inTCP=((Integer)_answers.get("inTCP")).intValue();
        _phttpRegister = _answers.get("phttpRegister").toString();
        _phttpSend = _answers.get("phttpSend").toString();
        _i2cpPort = ((Integer)_answers.get("i2cpPort")).intValue();
        _inBPS = ((Integer)_answers.get("inBPS")).intValue();
        _outBPS = ((Integer)_answers.get("outBPS")).intValue();
        long num = new java.util.Random().nextLong();
        if (num < 0)
            num = 0 - num;
        _answers.put("timestamperPassword", new Long(num));
    }

    private void useTemplate(String templateName, File destFile) {
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(destFile));
            BufferedReader br = new BufferedReader(new InputStreamReader(Install.class.getResourceAsStream(templateName)));
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("####")) {
                    bw.write(spliceParams(line));
                    bw.newLine();
                }
            }
            br.close();
            bw.close();
        } catch (IOException ioe) {
            ioe.printStackTrace();
            System.exit(0);
        }
    }
        
    private void createLoggerConfig() {
      boolean verbose = ((Boolean)_answers.get("verboseLogs")).booleanValue();
      createLogConfigOptions(verbose);
      File logCfgFile = new File(_installDir, "logger.config");
      useTemplate("logger.config.template",logCfgFile);
    }
    
    private void createSeedNodes() {
        showStatus("To connect to I2P, you will need a reference to at least one other I2P router");
        showStatus("Rather than bundle some (soon to be out of date) references with the software, ");
        showStatus("you can either run the included reseed script or get get your own references ");
        showStatus("from some out of band location.  ");
        showStatus("");
        showStatus("The reseed script simply connects to http://i2p.net/i2pdb/ and downloads all");
        showStatus("of the routerInfo-*.dat files and save them into " + (new File(_installDir, "i2pdb")).getAbsolutePath());
        showStatus("That ../i2pdb/ directory is simply a mirror of one router's netDb directory, so those files");
        showStatus("can come from anyone else too");
        showStatus("");
        showStatus("You can run the reseed script or download references (from your friends, etc) as often");
        showStatus("as you like without restarting your router.  If you find your netDb directory to have ");
        showStatus("only one file in it (thats your router info), you will need more peers to get anything done.");
        showStatus("");
        boolean reseed = confirmOption("Do you want to run the reseed script now? ", true);
        if (reseed) {
            reseed();
        } else {
            showStatus("Ok ok, not reseeding - but please reseed before running the router");
        }
    }
    
    private void reseed() {
        try {
            URL dir = new URL("http://i2p.net/i2pdb/");
            String content = new String(readURL(dir));
            Set urls = new HashSet();
            int cur = 0;
            while (true) {
                int start = content.indexOf("href=\"routerInfo-", cur);
                if (start < 0)
                    break;

                int end = content.indexOf(".dat\">", start);
                String name = content.substring(start+"href=\"routerInfo-".length(), end);
                urls.add(name);
                cur = end + 1;
            }

            for (Iterator iter = urls.iterator(); iter.hasNext(); ) {
                fetchSeed((String)iter.next());
            }
        } catch (Throwable t) {
            t.printStackTrace();
            showStatus("Error reseeding - " + t.getMessage());
        }
    }
    
    private void fetchSeed(String peer) throws Exception {
        URL url = new URL("http://i2p.net/i2pdb/routerInfo-" + peer + ".dat");
        showStatus("Fetching seed from " + url.toExternalForm());

        byte data[] = readURL(url);
        writeSeed(peer, data);
    }
    
    private byte[] readURL(URL url) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        URLConnection con = url.openConnection();
        InputStream in = con.getInputStream();
        byte buf[] = new byte[1024];
        while (true) {
            int read = in.read(buf);
            if (read < 0)
                break;
            baos.write(buf, 0, read);
        }
        in.close();
        return baos.toByteArray();
    }
    
    private void writeSeed(String name, byte data[]) throws Exception {
        File netDbDir = new File(_installDir, "netDb");
        if (!netDbDir.exists())
            netDbDir.mkdirs();
        FileOutputStream fos = new FileOutputStream(new File(netDbDir, "routerInfo-" + name + ".dat"));
        fos.write(data);
        fos.close();
    }
    
    private void copyLibraries() {
        File libDir = new File(_installDir, "lib");
        if (!libDir.exists()) {
            boolean libCreated = libDir.mkdirs();
            if (!libCreated) {
                showStatus("Error creating library directory " + libDir.getAbsolutePath());
                return;
            }
        }
        showStatus("Installing the libraries into " + libDir.getAbsolutePath());
        int cnt = Integer.parseInt(_p.getProperty("libs.count"));
        try {
            for (int i=1;i<=cnt;i++) {
                String ii = numberTo4Digits(i),
                file = _p.getProperty("libs."+ii+".name");
                boolean isLib = "true".equals(_p.getProperty("libs."+ii+".islib"));
                InputStream is = Install.class.getResourceAsStream("/"+file);
                if (is == null) throw new IOException("Resource /"+file+" not found");
                copyFile(is, file, isLib?libDir:_installDir);
            }
        } catch (IOException ioe) {
            showStatus("Error extracting the libraries: " + ioe.getMessage());
        }
        File dbDir = new File(_installDir, "netDb");
        dbDir.mkdirs();
        File logDir = new File(_installDir, "logs");
        logDir.mkdirs();
    }
    
    private void copyFile(InputStream in, String name, File destDir) {
        File destFile = new File(destDir, name);
        try {
            byte buf[] = new byte[16*1024];
            FileOutputStream out = new FileOutputStream(destFile);
            while (true) {
                int read = in.read(buf);
                if (read == -1)
                    break;
                out.write(buf, 0, read);
            }
            in.close();
            out.close();
            showStatus("Installed file " + destFile.getName() + " in " + destFile.getParent());
        } catch (IOException ioe) {
            showStatus("Error saving " + name + " to " + destFile.getAbsolutePath() 
                       + ": " + ioe.getMessage());
        }
    }
    
    private void createLogConfigOptions(boolean verbose) {
        _answers.put("_logger_level", verbose?"DEBUG":"INFO");
        _answers.put("_logger_level2", verbose?"WARN":"ERROR");
        StringBuffer buf = new StringBuffer();
        if (!verbose) {
            // overrides for particularly chatty classes
            _answers.put("_logger_notverbose", "logger.record.net.i2p.router.transport.Triv=ERROR"+NL+
                                               "logger.record.net.i2p.router.transport.Band=ERROR"+NL+
                                               "logger.record.net.i2p.crypto=ERROR" +NL+
                                               "logger.record.net.i2p.crypto.DH=ERROR");
        } else {
            _answers.put("_logger_notverbose","");
        }
    }

    private void createScripts(String unixName, String windowsName, int listenPort, 
                               String targetDest, String logfilePattern, String windowTitle, 
                               String message, String scriptMessage) {
        createScripts(unixName, windowsName, "client "+listenPort+" "+targetDest, 
                      logfilePattern, windowTitle, message, scriptMessage);
    }
    
    private void createScripts(String unixName, String windowsName, String command, 
                               String logfilePattern, String windowTitle, String message, 
                               String scriptMessage) {
        _answers.put("_scripts_port", ""+_i2cpPort);
        _answers.put("_scripts_cmd", command);
        _answers.put("_scripts_logname", logfilePattern);
        _answers.put("_scripts_winttl", windowTitle);
        _answers.put("_scripts_message", scriptMessage);
        if (_isWindows) {
            File windowsFile = new File(_installDir, windowsName);
            useTemplate("startFoo.bat.template", windowsFile);
        } else {
            File unixFile = new File(_installDir, unixName);
            useTemplate("startFoo.sh.template", unixFile);
            chmodaplusx(unixFile);
        }
        showStatus(message);
    }
        
    private void createReseedScript() {
        if (_isWindows) {
            File windowsFile = new File(_installDir, "reseed.bat");
            useTemplate("reseed.bat.template", windowsFile);
        } else {
            File unixFile = new File(_installDir, "reseed.sh");
            useTemplate("reseed.sh.template", unixFile);
            chmodaplusx(unixFile);
        }
    }

    private void chmodaplusx(File f) {
        try {
            Runtime.getRuntime().exec("chmod a+x " + f.getAbsolutePath());
        } catch (IOException ioe) {
            showStatus("Error setting "+f.getName()+" as executable");
        }
    }

    private void createStartScript() {
        _answers.put("_scripts_installdir", _installDir.getAbsolutePath());
        if (_isWindows) {
            File windowsFile = new File(_installDir, "startRouter.bat");
            useTemplate("startRouter.bat.template", windowsFile);
        } else {
            File unixFile = new File(_installDir, "startRouter.sh");
            useTemplate("startRouter.sh.template", unixFile);
            File unixStopFile = new File(_installDir, "stopRouter.sh");
            useTemplate("stopRouter.sh.template", unixStopFile);
            chmodaplusx(unixFile);
            chmodaplusx(unixStopFile);
        }
    }
    
    private void createConfigFile() {
        File configFile = new File(_installDir, "router.config");
        setConfigFileOptions();
        useTemplate("router.config.template", configFile);
        showStatus("Router configuration file written to " + configFile.getAbsolutePath());
        showStatus("");
    }
    
    private final static String NL = System.getProperty("line.separator");

    private void setConfigFileOptions() {
        // set fields needed for the config template
        _answers.put("NOW", new Date().toString());
        if (_inTCP <= 0) {
            _answers.put("_router_hn", "#i2np.tcp.hostname=[externally reachable hostname or IP address goes here]");
            _answers.put("_router_port", "#i2np.tcp.port=[TCP/IP port number]");
            _answers.put("_router_lavalid","#i2np.tcp.listenAddressIsValid=[true/false for whether your external address is locally reachable]");
            _answers.put("_router_tcpdisable","#i2np.tcp.disable=[true/false for whether you want absolutely no tcp connections to be established (forcing phttp, etc)])");
        } else {
            _answers.put("_router_hn","i2np.tcp.hostname="+_externalAddress);
            _answers.put("_router_port","i2np.tcp.port="+_inTCP);
            _answers.put("_router_lavalid","i2np.tcp.listenAddressIsValid="+_externalAddressIsReachable);
            _answers.put("_router_tcpdisable","i2np.tcp.disable=false");
        }
        if ( (_phttpRegister == null) || (_phttpSend == null) ) {
            _answers.put("_router_phttpreg","#i2np.phttp.registerURL=[full URL to a PHTTP registration server, e.g. http://someHost:8080/phttprelay/phttpRegister]");
            _answers.put("_router_phttpsend","#i2np.phttp.sendURL=[full URL to a PHTTP relay server, e.g. http://someHost:8080/phttprelay/phttpSend]");
        } else {
            _answers.put("_router_phttpreg","i2np.phttp.registerURL="+_phttpRegister);
            _answers.put("_router_phttpsend","i2np.phttp.sendURL="+_phttpSend);
        }
        _answers.put("_router_i2cp_port",""+_i2cpPort);
        _answers.put("_router_inbps",""+(_inBPS*60));
        _answers.put("_router_outbps",""+(_outBPS*60));
    }
}
