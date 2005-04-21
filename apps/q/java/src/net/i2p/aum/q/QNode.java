/*
 * QNode.java
 *
 * Created on 20 March 2005, 23:27
 */

package net.i2p.aum.q;

import java.lang.*;
import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.net.*;

import org.apache.xmlrpc.*;

import net.i2p.*;
import net.i2p.client.*;
import net.i2p.client.streaming.*;
import net.i2p.data.*;
import net.i2p.crypto.*;

import net.i2p.aum.*;

//import gnu.crypto.hash.*;


/**
 * Base class for Quartermaster nodes. Contains mechanisms for local datastore
 * and 
 * 
 */
public abstract class QNode extends Thread
{

    /** get an i2p context */
    public I2PAppContext i2p;
    
    // XML-RPC service name base
    public static String baseXmlRpcServiceName = "i2p.q";

    // generator of XML-RPC client objects
    public I2PXmlRpcClientFactory peerInterfaceGen;

    // directory requirements
    public static String [] coreSubDirs = { "peers", "content", "locations", "catalog", "jobs"};
    public static String [] extraSubDirs = {};

    // thread pooling
    public static int defaultMaxThreads = 3;
    protected SimpleSemaphore threadPool;
    protected EmbargoedQueue jobQueue;

    // directory paths of this node

    /** base path of our datastore directory */
    public String dataDir;
    
    /** subdirectory of peers records */
    public String peersDir;
    
    /** index file of peers */
    public QIndexFile peersIdx;
    
    /** subdirectory of catalog records */
    public String catalogDir;
    
    /** subdirectory of catalog location records */
    public String locationDir;
    
    /** index file of peers */
    public QIndexFile catalogIdx;
    
    /** subdirectory of content and metadata items */
    public String contentDir;
    
    /** directory where resources live */
    public String resourcesDir;

    /** index file of peers */
    public QIndexFile contentIdx;
    
    /** subdirectory of job records */
    public String jobsDir;

    /** index file of jobs */
    public QIndexFile jobsIdx;

    /** private key, as base64 string */
    public String privKeyStr;
    
    /** public dest, as base64 string */
    public String destStr;

    /** our own node ID - SHA1(dest) */
    public String id;

    /** our own node private key */
    public PrivDestination privKey;
    
    /** our own destination */
    public Destination dest;

    /** general node config properties */
    public PropertiesFile conf;
    
    /** path of node's config file */
    public String configPath;

    /** convenience */
    public static String sep = File.separator;

    public I2PXmlRpcServer xmlRpcServer;

    /** map of all known peers */
    public Hashtable peers;

    /**
     * override in subclass
     */
    public static String defaultStoreDir = ".quartermaster";

    // status attributes
    /** time node got online */
    public Date nodeStartTime;
    
    // logging file
    public RandomAccessFile logFile;
    public net.i2p.util.Log log;

    public static int updateCatalogFromPeers = 0;
    
    public boolean isClient = false;

    public double load_yPrev = 0.0;
    public long load_tPrev = 0;
    public double load_kRise = 10.0;
    public double load_kFall = 800000.0;

    public int load_backoffMin = 180;
    public int load_backoffBits = 13;
    public double load_backoffBase = 3.0;

    // client only
    public String xmlRpcServerHost = "";
    public int xmlRpcServerPort = 7651;
    public static int defaultXmlRpcServerPort = 7651;
    
    /** Number of pending content uploads. You should never shut down a
     * node while this value is non-zero. You can get the current value
     * of this via a node 'ping' command
     */
    public int numPendingUploads = 0;
    
    /** unixtime in millisecs of last incoming xml-rpc hit to this node, used
     * in calculating node load
     */

    public String nodeType = "(base)";
    
    public boolean isRunning;

    // ----------------------------------------------------------
    // CONSTRUCTORS
    // ----------------------------------------------------------

    /** 
     * Creates a new QNode instance, with store tree located
     * at default location
     */
    public QNode() throws IOException, DataFormatException, I2PException
    {
        this(System.getProperties().getProperty("user.home") + sep + defaultStoreDir);
        log.info("Constructor finished");
    }

    /**
     * Creates a Q node, using specified datastore directory
     * @param dataDir absolute pathname where this server's datastore tree is
     * located. If tree doesn't exist, it will be created along with new keys
     */
    public QNode(String dataDir) throws IOException, DataFormatException, I2PException
    {
        // establish ourself as a thread
        super();

        setupStoreTree(dataDir);
        getConfig();
        peerInterfaceGen = new I2PXmlRpcClientFactory();

        // determine threads limit
        int maxThreads = defaultMaxThreads;
        String maxThreadsStr = System.getProperty("qnode.maxthreads");
        if (maxThreadsStr != null)
        {
            try {
                maxThreads = Integer.getInteger(maxThreadsStr).intValue();
            } catch (Exception e) {
                e.printStackTrace();
                log.error("Invalid qnode.maxThreads setting '"+maxThreadsStr+"'");
            }
        }
        
        // set up thread pool and job queue
        threadPool = new SimpleSemaphore(maxThreads);
        jobQueue = new EmbargoedQueue();

        // load all known peers into peers table
        loadPeers();

        // for benefit of subclasses
        //System.out.println("Invoking setup, isClient="+isClient);
        setup();
        System.out.println("after setup, isClient="+isClient);
        
        // queue up the first lot of jobs
        scheduleStartupJobs();

        // now launch our background
        //log.info("launching background engine");
        //start();

    }

    public void loadPeers() throws IOException
    {
        // populate job queue with jobs for all known servers
        // man, doesn't it feel good to eat up memory by the gigabyte!! :P
        Iterator peerIds = peersIdx.getItemsSince(0);
        QPeer peerRec;
        peers = new Hashtable();
        while (peerIds.hasNext())
        {
            String peerId = (String)peerIds.next();
            try {
                peerRec = getPeerRecord(peerId);
            } catch (Exception e) {
                log.error("Failed to load peer '"+peerId+"'", e);
                continue;
            }
            peers.put(peerId, peerRec);
        }
    }

    // --------------------------------------------
    // XML-RPC FRONT-END
    // --------------------------------------------

    /**
     * <p>Sets up and launches an xml-rpc server for servicing requests
     * to this node.</p>
     * <p>For server nodes, the xml-rpc server listens within I2P on the
     * node's destination.</p>
     * <p>For client nodes, the xml-rpc server listens on a local TCP
     * port (according to attributes xmlRpcServerHost and xmlRpcServerPort)</p>
     */
    public abstract void startExternalInterfaces(QServerMethods methods)
            throws Exception;


    // --------------------------------------------
    // XML-RPC BACKEND
    // --------------------------------------------

    /**
     * Dispatches a XML-RPC call to remote peer
     */
    public Hashtable peerExecute(String peerId, String name, Vector args) 
        throws XmlRpcException, IOException, DataFormatException
    {
        // get peer record
        QPeer peerRec = getPeerRecord(peerId);
        
        // need peer's dest
        String dest64 = peerRec.destStr;

        // need xmlrpc client obj
        log.debug("peerExecute: name="+name+", id="+peerId+", dest="+dest64);

        I2PXmlRpcClient client = peerInterfaceGen.newClient(dest64);
        
        // execute the request
        Object result = client.execute(baseXmlRpcServiceName+"."+name, args);
        
        // ensure it's a hashtable
        if (!result.getClass().isAssignableFrom(Hashtable.class)) {
            throw new XmlRpcException(0, "Expected Hashtable in peer reply");
        }

        // all ok
        return (Hashtable)result;
    }

    // --------------------------------------
    // METHODS - initialisation
    // --------------------------------------

    /** perform mode-specific setup - overridden in subclasses */
    public void setup() throws DataFormatException, I2PException
    {
    }

    /**
     * Checks the store directory tree, creating any missing
     * directories
     */
    public void setupStoreTree(String dataDir) throws IOException
    {
        this.dataDir = dataDir;
        int i;
        File rootDir = new File(dataDir);

        // ensure parent exists
        if (!rootDir.isDirectory()) {
            rootDir.mkdirs();
        }
        String logPath = dataDir + sep + "node.log";

        // set up node-specific logger
        Properties envProps = new Properties();
        envProps.setProperty("loggerFilenameOverride", logPath);

        //i2p = new I2PAppContext(envProps);
        i2p = I2PAppContext.getGlobalContext();

        log = i2p.logManager().getLog(this.getClass());
        
        //System.out.println("HASHTEST1: "+sha256Base64("hello, one, two three".getBytes()));
        //System.out.println("BASE64TEST1: "+base64Enc("hello, one two three"));
        //byte [] shit = {39,-20,54,-93,-19,-33,-61,65,-91,-85,
        //            -19,25,-31,-81,20,-125,26,92,-51,-100,83,43,38,58,77,72,3,40,-78,-62,79,0,
        //};
        //System.out.println("BASE64TEST2: "+base64Enc(shit));

        log.setMinimumPriority(log.DEBUG);
        
        log.info("creating server at directory "+dataDir);

        /**
        if (!logFileObj.isFile()) {
            logFileObj.createNewFile();
        }
        System.out.println("Created logfile at "+logPath);
        logFile = new RandomAccessFile(logFileObj, "rws");
        */

        // create core subdirectories
        for (i = 0; i < coreSubDirs.length; i++)
        {
            String subdir = dataDir + sep + coreSubDirs[i];
            File d = new File(subdir);
            if (!d.isDirectory())
            {
                log.info("Creating datastore subdirectory '"+subdir+"'");
                if (!d.mkdirs())
                {
                    throw new IOException("Failed to create directory "+subdir);
                }
            }
        }

        // create supplementary subdirectories
        for (i = 0; i < extraSubDirs.length; i++)
        {
            String subdir = dataDir + sep + extraSubDirs[i];
            File d = new File(subdir);
            if (!d.isDirectory())
            {
                log.info("Creating supplementary datastore subdir '"+subdir+"'");
                if (!d.mkdirs())
                {
                    throw new IOException("Failed to create directory "+subdir);
                }
            }
        }

        // store pathnames of core subdirectories
        peersDir = dataDir + sep + "peers";
        peersIdx = new QIndexFile(peersDir + sep + "index.dat");

        catalogDir = dataDir + sep + "catalog";
        catalogIdx = new QIndexFile(catalogDir + sep + "index.dat");
        locationDir = dataDir + sep + "locations";
        
        contentDir = dataDir + sep + "content";
        contentIdx = new QIndexFile(contentDir + sep + "index.dat");
        
        jobsDir = dataDir + sep + "jobs";
        jobsIdx = new QIndexFile(jobsDir + sep + "index.dat");

        // extract resources directory from jarfile (or wherever)
        getResources();

    }

    public void getConfig() throws IOException, DataFormatException, I2PException
    {
        // create a config object, and stick in any missing defaults
        String confPath = dataDir + sep + "node.conf";
        conf = new PropertiesFile(confPath);

        // generate a new dest, if one doesn't already exist
        privKeyStr = conf.getProperty("privKey");
        if (privKeyStr == null)
        {
            // need to generate whole new config
            log.info("No private key found, generating new one");
                
            ByteArrayOutputStream privBytes = new ByteArrayOutputStream();
            I2PClient client = I2PClientFactory.createClient();

            // save attributes
            dest = client.createDestination(privBytes);
            privKey = new PrivDestination(privBytes.toByteArray());
            privKeyStr = privKey.toBase64();
            destStr = dest.toBase64();

            // save out keys to files
            String privKeyPath = dataDir + sep + "nodeKey.priv";
            SimpleFile.write(privKeyPath, privKey.toBase64());
            String destPath = dataDir + sep + "nodeKey.pub";
            SimpleFile.write(destPath, dest.toBase64());

            // now we can figure out our own node ID
            id = destToId(dest);

            // and populate our stored config
            conf.setProperty("dest", dest.toBase64());
            conf.setProperty("privKey", privKey.toBase64());
            conf.setProperty("id", id);
            conf.setProperty("numPeers", "0");
            conf.setDoubleProperty("loadDampRise", load_kRise);
            conf.setDoubleProperty("loadDampFall", load_kFall);
            conf.setIntProperty("loadBackoffMin", load_backoffMin);
            conf.setIntProperty("loadBackoffBits", load_backoffBits);

            // these items only relevant to client nodes
            conf.setIntProperty("xmlRpcServerPort", xmlRpcServerPort);
            
            log.info("Saved new keys, and nodeID " + id);
        }
        else
        {
            // already got a config, load it
            //System.out.println("loading config");
            dest = new Destination();
            dest.fromBase64(conf.getProperty("dest"));
            destStr = dest.toBase64();
            privKey = PrivDestination.fromBase64String(conf.getProperty("privKey"));
            privKeyStr = privKey.toBase64();
            id = conf.getProperty("id");
            load_kRise = conf.getDoubleProperty("loadDampRise", load_kRise);
            load_kFall = conf.getDoubleProperty("loadDampFall", load_kFall);
            load_backoffMin = conf.getIntProperty("loadBackoffMin", load_backoffMin);
            load_backoffBits = conf.getIntProperty("loadBackoffBits", load_backoffBits);

            // these items only relevant to client nodes
            xmlRpcServerPort = conf.getIntProperty("xmlRpcServerPort", xmlRpcServerPort);

            //System.out.println("our privkey="+privKeyStr);
            if (privKeyStr == null) {
                privKeyStr = conf.getProperty("privKey");
                //System.out.println("our privkey="+privKeyStr);
            }
        }
    }

    /**
     * Copies resources from jarfile (or wherever) into datastore dir.
     * Somwhat of a kludge which determines if the needed resources
     * reside within a jarfile or on the host filesystem.
     * If the resources live in a jarfile, we extract them and
     * copy them into the 'resources' subdirectory of our datastore
     * directory. If they live in a directory on the host filesystem,
     * we configure the node to access the resources directly from that
     * directory instead.
     */
    public void getResources() throws IOException {

        String resPath = dataDir + sep + "resources";
        File resDir = new File(resPath);
        ClassLoader cl = this.getClass().getClassLoader();
        String jarPath = cl.getResource("qresources").getPath();
        System.out.println("jarPath='"+jarPath+"'");
        if (jarPath.startsWith("jar:")) {
            jarPath = jarPath.split("jar:")[1];
        }
        
        if (jarPath.startsWith("file:")) {
            jarPath = jarPath.split("file:")[1];
        }
        int bangIdx = jarPath.indexOf("!");
        //System.out.println("jarPath='"+jarPath+"' bangIdx="+bangIdx);
        if (bangIdx > 0) {
            jarPath = jarPath.substring(0, bangIdx);
        }
        
        if (!jarPath.endsWith(".jar")) {
            
            // easy - found a directory with our resources
            resourcesDir = jarPath;
            System.out.println("Found physical resources dir: '"+resourcesDir+"'");
            return;
        }
        System.out.println("jarPath='"+jarPath+"'");

        // harder case - create resources dir, copy across resources
        if (!resDir.isDirectory()) {
            resDir.mkdirs();
        }
        resourcesDir = resDir.getPath();
        
        JarFile jf = new JarFile(jarPath);
        Enumeration jfe = jf.entries();
        Vector entlist = new Vector();
        while (jfe.hasMoreElements()) {
            JarEntry ent = (JarEntry)jfe.nextElement();
            String name = ent.getName();
            if (name.startsWith("qresources") && !ent.isDirectory()) {
                entlist.addElement(name);
                System.out.println("Need to extract resource: "+name);
                String absPath = resDir.getPath() + sep + name.split("qresources/")[1];
                File absFile = new File(absPath);
                File parent = absFile.getParentFile();
                if (!parent.isDirectory()) {
                    parent.mkdirs();
                }
                // finally, can create and copy the file
                FileWriter fw = new FileWriter(absFile);
                InputStream is = cl.getResourceAsStream(name);
                int c;
                while ((c = is.read()) >= 0) {
                    fw.write(c);
                }
                fw.close();
            }
        }
    }

    /**
     * given a 'logical resource path', such as 'html/page.html',
     * returns an absolute pathname on the host filesystem of
     * the needed file
     */
    public String getResourcePath(String name) {
        return resourcesDir + sep + name;
    }

    // --------------------------------------
    // METHODS - scheduling and traffic control
    //
    // Background processing depends on node type:
    //  - all nodes:
    //     - peer list synchronisation
    //  - client nodes
    //     - catalog synchronisation
    //     - content insertion, triggered by local
    //       insertion
    //  - server nodes
    //     - content insertion, triggered by above-threshold
    //       demand from clients
    //
    // All background jobs are scheduled on a queue of
    // timed jobs (using an EmbargoedQueue), and picked off
    // and passed to background threads.
    // --------------------------------------

    // --------------------------------------------
    // HIGH-LEVEL TASK-SPECIFIC JOB SCHEDULING METHODS
    // --------------------------------------------

    public void scheduleStartupJobs()
    {
        Iterator peerRecs = peers.values().iterator();
        while (peerRecs.hasNext()) {
            QPeer peerRec = (QPeer)peerRecs.next();

            // also, while we're here, schedule a 'getUpdate' update job
            schedulePeerUpdateJob(peerRec);
        }
        
        System.out.println("scheduleStartupJobs: c<p="+updateCatalogFromPeers+", isClient="+isClient);
    }

    public void scheduleShutdown()
    {
        Hashtable job = new Hashtable();
        job.put("cmd", "shutdown");
        runAfter(1000, job, "shutdown");
    }

    public void schedulePeerUploadJob(QDataItem item)
    {
        String uri = (String)item.get("uri");
        Hashtable job = new Hashtable();
        job.put("cmd", "uploadItem");
        job.put("item", item);
        job.put("uri", uri);
        job.put("peersUploaded", new Hashtable()); // peerIds of peers we've uploaded to
        job.put("peersPending", new Hashtable());  // peers we're trying to upload to
        job.put("peersFailed", new Hashtable());   // peers which blew retry count
        job.put("peersNumTries", new Hashtable()); // number of times we've tried given peer
        String desc = "uploadItem:uri="+uri;
        runNow(job, desc);
    }

    /**
     * creates a job for inserting a single file locally
     */
    public void scheduleLocalInsertJob(String localPath, Hashtable metadata)
    {
        //System.out.println("sched: path='"+localPath+"' metadata="+metadata);
        Hashtable job = new Hashtable();
        job.put("cmd", "localPutItem");
        job.put("metadata", metadata);
        job.put("localDataFilePath", localPath);
        //jobQueue.putNow(job);
        String desc = "localPutItem:file="+localPath;
        runNow(job, desc);
    }

    public void schedulePeerUpdateJob(QPeer peerRec)
    {
        long nextUpdateTime = peerRec.getTimeNextContact();
        Hashtable job = new Hashtable();
        String peerId = peerRec.getId();
        job.put("cmd", "getUpdate");
        job.put("peerId", peerId);
        job.put("includeCatalog", new Integer(updateCatalogFromPeers));
        job.put("includePeers", new Integer(1));
        //jobQueue.putAt(nextUpdateTime, job);
        long when = nextUpdateTime - new Date().getTime();
        String desc = "getUpdate:peerID="+peerId+":when="+when;
        runAt(nextUpdateTime, job, desc);
    }

    public void schedulePeerGreetingJob(QPeer peerRec)
    {
        String peerId = peerRec.getId();
        if (peerRec.hasBeenGreeted() || peerId.equals(id)) {
            return;
        }

        Hashtable job = new Hashtable();
        job.put("cmd", "hello");
        job.put("peerId", peerId);
        //jobQueue.putNow(job);
        String desc = "peerHello:peerID="+peerId;
        runNow(job, desc);
    }

    public void scheduleTestJob(long delay, String msg)
    {
        Hashtable job = new Hashtable();
        msg = msg + ":" + (new Date()).getTime();
        job.put("cmd", "test");
        job.put("msg", msg);
        runAfter(delay, job, "test: "+msg);
    }

    // --------------------------------------------
    // LOW-LEVEL JOB SCHEDULING METHODS
    // --------------------------------------------

    /**
     * Add a job to the queue, to be executed immediately (or
     * when a thread becomes available).
     * Should make this protected at some time I guess.
     * @param job a Hashtable containing job details
     */
    public void runNow(Hashtable job, String description)
    {
        runAfter(0, job, description);
    }

    /**
     * Add a job to the queue, to be executed a given amount of time
     * from now. Should make this protected at some time I guess.
     * @param time number of milliseconds from now at which to run job
     * @param job a Hashtable containing job details
     */
    public void runAfter(long time, Hashtable job, String description)
    {
        runAt(new Date().getTime()+time, job, description);
    }

    /**
     * Add a job to the queue, to be executed at an absolute time.
     * Should make this protected at some time I guess.
     * @param time unixtime in milliseconds at which to run job
     * @param job a Hashtable containing job details
     */
    public void runAt(long time, Hashtable job, String description)
    {
        String timeStr;
        File jobFile;
        String jobFilePath;

        // serialise this job out to jobs dir
        try {
            // find unique jobtime
            while (true) {
                jobFilePath = jobsDir + sep + String.valueOf(time);
                jobFile = new File(jobFilePath);
                if (!jobFile.isFile()) {
                    // got unique name
                    break;
                }
                time += 1;
            }

            // got unique time - serialise our job to this time
            timeStr = String.valueOf(time);
            FileOutputStream out = new FileOutputStream(jobFilePath);
            ObjectOutputStream objOut = new ObjectOutputStream(out);
            objOut.writeObject(job);
            objOut.close();

            // also, write out the job description
            SimpleFile sf = new SimpleFile(jobFilePath + ".desc", "rws");
            sf.write(description);
            
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        jobQueue.putAt(time, timeStr);
    }
    
    /**
     * Given a jobTimeString, reconstitutes a job from its serialisation
     * in the node's jobs file directory.
     * @param jobTime effectively serves as a jobId, since it's guaranteed to be unique
     * @return the reconstituted job object, as a Hashtable
     */
    public Hashtable loadJob(String jobTime) throws Exception {

        String jobFilePath = jobsDir + sep + jobTime;
        File jobFile = new File(jobFilePath);
        FileInputStream in = new FileInputStream(jobFilePath);
        ObjectInputStream objIn = new ObjectInputStream(in);
        Hashtable job = (Hashtable)objIn.readObject();
        return job;
    }

    /**
     * returns a Vector of currently queued jobs, with each element being
     * a Hashtable containing keys 'time' and 'description'
     */
    public Vector getJobsList() throws Exception {

        Vector jobs = new Vector();
        long now = new Date().getTime() / 1000;

        try {
            String [] jobsFiles = new File(jobsDir).list();
            for (int i=0; i<jobsFiles.length; i++) {
                String jobFileName = jobsFiles[i];
                if (!jobFileName.matches("[0-9]+\\.desc")) {
                    continue;   // not a proper filename for a job object
                }

                String timeStr = jobFileName.substring(0, jobFileName.length()-5);
                long time = new Long(timeStr).longValue() / 1000;
                
                
                Hashtable job = new Hashtable();
                String desc = new SimpleFile(jobsDir+sep+jobFileName, "r").read();
                job.put("time", String.valueOf(time));
                job.put("future", String.valueOf((time-now)));
                job.put("description", desc);
                jobs.addElement(job);
            }
        } catch (Exception e) {
            
        }

        return jobs;
    }

    /**
     * Loads the 'job description' string for a given jobTimeString
     * @param jobTime effectively serves as a jobId, since it's guaranteed to be unique
     * @return the job description, as a one-liner String
     */
    public String loadJobDescription(String jobTime) throws Exception {

        String jobFilePath = jobsDir + sep + jobTime + ".desc";
        String desc = new SimpleFile(jobFilePath, "r").read();
        return desc;
    }
    
    /**
     * Manages the scheduled background tasks of this node.
     * Do NOT invoke this directly
     */
    public void run()
    {
        log.info("Starting background tasks");
        
        isRunning = true;
        
        // mark our start time
        nodeStartTime = new Date();
        
        // fire up our xml-rpc server interface
        log.info("launching node XML-RPC server");
        QServerMethods handler = new QServerMethods(this);
        try {
            startExternalInterfaces(handler);
        } catch (Exception e) {
            log.error("Failed to start external interfaces", e);
            return;
        }

        // reconstitute the jobs queue from last run
        try {
            long now = new Date().getTime();
            String [] oldJobs = new File(jobsDir).list();
            for (int i=0; i<oldJobs.length; i++) {
                try {
                    String jobTimeStr = oldJobs[i];
                    if (!jobTimeStr.matches("[0-9]+")) {
                        continue;   // not a proper filename for a job object
                    }

                    long jobTime = new Long(jobTimeStr).longValue();

                    jobQueue.putAt(jobTime, jobTimeStr);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // a few dummy jobs to test the scheduler
        scheduleTestJob(20000, "red");
        scheduleTestJob(40000, "white");
        scheduleTestJob(30000, "blue");

        // fetch items from the job queue, and launch
        // threads to execute them
        while (isRunning)
        {
            // get a thread slot from the thread pool
            try {
                threadPool.acquire();
            } catch (Exception e) {
                e.printStackTrace();
                log.warn("QNode.run: crashed on acquiring new thread");
                continue;
            }

            // got a thread slot - now get (wait for) first available job
            String jobTime = (String)jobQueue.get();
            
            // create and launch the thread for this job
            Thread thrd = new QWorkerThread(this, jobTime);
            thrd.start();
        }
    }
    
    // ----------------------------------------------------------
    // METHODS for peer2peer communication
    //
    // These methods implement 6 basic primitives:
    //  - ping
    //  - hello
    //  - getCatalog
    //  - getPeerList
    //  - getItem
    //  - putItem
    //
    // Each of these primitives are executed as main, remote and
    // local versions. On server nodes, the main version just forwards
    // to the local version. On client nodes, the main version may
    // try the local version, then a remote version.
    // 
    // For example, 'getItem'. The main entry point, 'getItem',
    // on server nodes, just tries the local datastore.
    // On client nodes, tries the lcoal datastore, and if this
    // doesn't have the item, tries all remote peers whihc are
    // known to have the item
    // ----------------------------------------------------------

    // ---------------------------------------
    // PRIMITIVE - ping
    // ---------------------------------------

    /**
     * local implementation of the method
     */
    public Hashtable ping()
    {
        int tNext = getAdvisedNextContactTime();
        int dNext = tNext - (int)(new Date().getTime() / 1000);

        Hashtable h = new Hashtable();
        h.put("status", "ok");
        h.put("id", id);
        h.put("dest", destStr);
        h.put("uptime", new Integer(nodeUptime()));
        h.put("load", new Float(load_yPrev));
        h.put("numPeers", new Integer(peers.size()));
        h.put("numLocalItems", new Integer(localCatalogSize()));
        h.put("timeNextContact", new Integer(tNext));
        h.put("delayNextContact", new Integer(dNext));
        h.put("numPendingUploads", new Integer(numPendingUploads));
        if (isClient) {
            h.put("numRemoteItems", new Integer(remoteCatalogSize()));
        }
        return h;
    }

    /**
     * pings remote peer
     */
    public Hashtable peerPing(String peerId)
        throws XmlRpcException, IOException, DataFormatException
    {
        return peerExecute(peerId, "ping", new Vector());
    }

    // ---------------------------------------
    // PRIMITIVE - hello
    // ---------------------------------------

    /**
     * introduces a remote destination as a peer
     * @param dest destination of peer to add
     */
    public Hashtable hello(String dest64)
    {
        return localHello(dest64);
    }

    /**
     * instructs remote node to add our dest to its peers list
     */
    public Hashtable peerHello(String peerId, String dest)
        throws XmlRpcException, IOException, DataFormatException
    {
        Vector v = new Vector();
        v.add(dest);

        log.debug("peerHello: peerId="+peerId);
        return peerExecute(peerId, "hello", v);
    }

    /**
     * adds a new peer to this node's peers list
     */
     public Hashtable localHello(String destBase64)
     {
        Hashtable h = new Hashtable();

        // one dirty dangerous hack here, we're assuming that I2PXmlRpcServer
        // is not running multithreaded requests
        //I2PSocket peerSocket = ((I2PXmlRpcServer)xmlRpcServer).sessSocket;
        //Destination peerDest = peerSocket.getPeerDestination();
        //String peerDestStr = peerDest.toBase64();
        //log("localHello: peer dest="+peerDestStr);
        //log("localHello: arg  dest="+destBase64);

        try {
            newPeer(destBase64);
            h.put("status", "ok");
        } catch (Exception e) {
            e.printStackTrace();
            h.put("status", "error");
            h.put("error", e.getClass().getName());
        }
        return h;
    }
    
    // ---------------------------------------
    // PRIMITIVE - getUpdate
    // ---------------------------------------

    /**
     * retrieves a peers/catalog update
     */
    public Hashtable getUpdate(int since, int includePeers, int includeCatalog)
    {
        return localGetUpdate(since, includePeers, includeCatalog);
    }

    /**
     * gets a catalog update from remote peer
     * @param peerid peer ID
     * @param since Unixtime in seconds of last update request
     * @param includePeers whether to include peers in update
     * @param includeCatalog whether to include catalog in update
     */
    public Hashtable peerGetUpdate(String peerId, int since, int includePeers, int includeCatalog)
        throws XmlRpcException, IOException, DataFormatException
    {
        Vector v = new Vector();
        v.add(new Integer(since));
        v.add(new Integer(includePeers));
        v.add(new Integer(includeCatalog));

        return peerExecute(peerId, "getUpdate", v);
    }

    /**
     * returns portion of our catalog which is newer than
     * given time
     * @param since unixtime in seconds
     * @param includePeers whether to include updated peers list
     * @param includeCatalog whether to include updated catalog
     */
    public Hashtable localGetUpdate(int since, int includePeers, int includeCatalog)
    {
        Iterator items;
        Hashtable h = new Hashtable();
        int nowsecs;

        Vector vCat = new Vector();
        Vector vPeers = new Vector();

        log.debug("localGetUpdate: since="+since+", incpeers="+includePeers+", inccat="+includeCatalog);
        //System.out.println("localGetUpdate: since="+since+", incpeers="+includePeers+", inccat="+includeCatalog);

        if (includeCatalog != 0) {
            // get an iterator for all new catalog items since given unixtime
            try {
                items = contentIdx.getItemsSince(since);
            } catch (IOException e) {
                e.printStackTrace();
                h.put("status", "error");
                h.put("error", "IOException");
                return h;
            }

            // pick through the iterator, and fetch metadata for each item
            while (items.hasNext()) {
                String key = (String)(items.next());
                QDataItem item = getLocalMetadata(key);
                //log.error("localGetUpdate: key="+key+", pf="+pf);
                //System.out.println("localGetUpdate: key="+key+", pf="+pf);
                if (item != null) {
                    // clone this metadata, add in the key
                    //Hashtable pf1 = (Hashtable)item.clone();
                    //pf1.put("key", key);
                    vCat.addElement(item);
                }
            }
        }

        if (includePeers != 0) {
            try {
                items = peersIdx.getItemsSince(since);
            } catch (IOException e) {
                e.printStackTrace();
                h.put("status", "error");
                h.put("error", "IOException");
                return h;
            }
            while (true) {
                try {
                    String destId = (String)items.next();
                    // get its dest
                    try {
                        QPeer peerRec = getPeerRecord(destId);
                        vPeers.addElement(peerRec.destStr);
                    } catch (Exception e) {
                        log.warn("localGetPeersList", e);
                    }
                } catch (NoSuchElementException e) {
                    break;
                }
            }
        }

        // finally - add update end time, and advised next update time
        h.put("status", "ok");
        h.put("items", vCat);
        h.put("peers", vPeers);
        h.put("timeUpdateEnds", new Integer(nowSecs())); // so peer knows where to update from next time
        h.put("timeNextContact", new Integer(getAdvisedNextContactTime()));
        return h;
    }

    // ---------------------------------------
    // PRIMITIVE - getItem
    // ---------------------------------------

    /**
     * <p>Retrieve an item of content.</p>
     * <p>On server nodes this only retrieves from the local datastore.</p>
     * <p>On client nodes, this tries the local datastore first, then
     * attempts to get the data from remote servers believed to have the data</p>
     */
    public Hashtable getItem(String uri) throws IOException, QException
    {
        log.info("getItem: uri='"+uri+"'");
        return localGetItem(uri);
    }

    /**
     * retrieves an item of content from remote peer
     */
    public Hashtable peerGetItem(String peerId, String uri)
        throws XmlRpcException, IOException, DataFormatException
    {
        Vector v = new Vector();
        v.add(uri);

        return peerExecute(peerId, "getItem", v);
    }


    /** returns true if this node possesses given key, false if not */
    public boolean localHasItem(String uri) {
        if (getLocalMetadata(uri) == null) {
            return false;
        }
        else {
            return true;
        }
    }

    /** returns true if this node possesses given key, false if not */
    public boolean localHasCatalogItem(String uri) {
        if (getLocalCatalogMetadata(uri) == null) {
            return false;
        }
        else {
            return true;
        }
    }

    /**
     * returns the data stored under given key
     */
    public Hashtable localGetItem(String uri) throws IOException
    {
        log.info("localGetItem: uri='"+uri+"'");
        Hashtable h = new Hashtable();

        QDataItem item = getLocalMetadata(uri);
        if (item == null)
        {
            // Honest, officer, we don't have it, we were just
            // holding it for a friend!
            System.out.println("localGetItem: no metadata for uri "+uri);
            h.put("status", "error");
            h.put("error", "notfound");
            return h;
        }

        // locate the content
        String dataHash = (String)item.get("dataHash");
        String dataPath = makeDataPath(dataHash);
        SimpleFile dataFile = new SimpleFile(dataPath, "r");

        // barf if content missing
        if (!dataFile.isFile())
            {
                System.out.println("localGetItem: no data for uri "+uri);
                h.put("status", "error");
                h.put("error", "missingdata");
                return h;
            }

        // get data, hand it back with metadata
        byte [] dataImage = dataFile.readBytes();
        h.put("status", "ok");
        h.put("metadata", item);
        h.put("data", dataImage);
        System.out.println("localGetItem: successful get: uri "+uri);
        System.out.println("localGetItem: data hash="+sha256Base64(dataImage));
        return h;
    }

    // ---------------------------------------
    // PRIMITIVE - putItem
    // ---------------------------------------

    /**
     * Insert an item of content, with no metadata
     * @param raw data to insert
     */
    public Hashtable putItem(byte [] data) throws IOException, QException
    {
        return putItem(new Hashtable(), data);
    }

    /**
     * Insert an item of content, with metadata
     * overridden in client nodes
     * @param metadata Hashtable of item's metadata
     * @param data raw data to insert
     */
    public Hashtable putItem(Hashtable metadata, byte [] data) throws QException
    {
        Hashtable resp = new Hashtable();
        QDataItem item;
        try {
            item = new QDataItem(metadata, data);
            item.processAndValidate(false);
            localPutItem(item);
        } catch (QException e) {
            resp.put("status", "error");
            resp.put("error", "qexception");
            resp.put("summary", e.getLocalizedMessage());
            return resp;
        }
        
        // success, it seems
        resp.put("status", "ok");
        resp.put("uri", (String)item.get("uri"));
        return resp;
    }

    /**
     * inserts an item of content to remote peer
     */
    public Hashtable peerPutItem(String peerId, byte [] data)
        throws XmlRpcException, IOException, DataFormatException
    {
        Vector v = new Vector();
        v.add(data);

        return peerExecute(peerId, "putItem", v);
    }

    /**
     * inserts an item of content to remote peer
     */
    public Hashtable peerPutItem(String peerId, Hashtable metadata, byte [] data)
        throws XmlRpcException, IOException, DataFormatException
    {
        Vector v = new Vector();
        v.add(metadata);
        v.add(data);

        return peerExecute(peerId, "putItem", v);
    }

    /**
     * adds a new item of content to our local store, with given metadata
     */
    public void localPutItem(QDataItem item) throws QException
    {
        /**
        // 1) hash the data, add to metadata
        String dataHash = sha256Base64(data);
        metadata.put("dataHash", dataHash);
        System.out.println("localPutItem: dataHash="+dataHash);

        // 2) if metadata has no key 'title', use hash as data
        if (!metadata.containsKey("title"))
        {
            metadata.put("title", dataHash);
        }

        // 3) add size field to metadata
        metadata.put("size", new Integer(data.length));
        
        // 4) get deterministic hash of final metadata
        TreeSet t = new TreeSet(metadata.keySet());
        Iterator keys = t.iterator();
        int nkeys = t.size();
        int i;
        String metaStr = "";
        for (i = 0; i < nkeys; i++)
        {
            String metaKey = (String)keys.next();
            metaStr += metaKey + "=" + metadata.get(metaKey) + "\n";
        }

        // store the metadata and data
        String metaPath = makeDataPath(metaHash+".meta");
        String dataPath = makeDataPath(dataHash+".data");
        new SimpleFile(dataPath, "rws").write(data);

        PropertiesFile pf = new PropertiesFile(metaPath, metadata);

        // update index
        contentIdx.add(metaHash);

        Hashtable h = new Hashtable();
        h.put("status", "ok");
        h.put("key", metaHash);
        return h;

        */

        // work out where to store metadata and data
        String metaFilename = item.getStoreFilename();
        String metaPath = makeDataPath(metaFilename);
        String dataPath = makeDataPath((String)item.get("dataHash"));

        // store the data, if not already present
        if (!(new File(dataPath).isFile())) {
            byte [] data = item._data;
            try {
                new SimpleFile(dataPath, "rws").write(data);
            } catch (Exception e) {
                throw new QException("Error storing metadata", e);
            }
        }

        // store metadata and add to index, if not already present
        if (!(new File(metaPath).isFile())) {
            try {
                // store the metadata
                PropertiesFile pf = new PropertiesFile(metaPath, item);
            } catch (Exception e) {
                throw new QException("Error storing data", e);
            }

            try {
                // enter the metadata hash into our index
                contentIdx.add(metaFilename);
            } catch (Exception e) {
                throw new QException("Error adding metadata to index", e);
            }
        }
    }

    // ---------------------------------------
    // PRIMITIVE - newKeys
    // ---------------------------------------

    /**
     * Generates a new keypair for signed-space insertions
     * @return a struct with the keys:
     * <ul>
     *  <li>status - "ok"</li>
     *  <li>publicKey - base64-encoded signed space public key</li>
     *  <li>privateKey - base64-encoded signed space private key</li>
     * </ul>
     * When inserting an item using the privateKey, the resulting uri
     * will be <code>Q:publicKey/path</code>
     */
    public Hashtable newKeys() {
    
        String [] keys = QUtil.newKeys();
        Hashtable res = new Hashtable();
        res.put("status", "ok");
        res.put("publicKey", keys[0]);
        res.put("privateKey", keys[1]);
        return res;
    }

    // ---------------------------------------
    // PRIMITIVE - search
    // ---------------------------------------

    /**
     * Search datastore and catalog for a given item of content
     * @param criteria
     */
    public Hashtable search(Hashtable criteria)
    {
        return localSearch(criteria);
    }

    public Hashtable localSearch(Hashtable criteria)
    {
        Hashtable result = new Hashtable();
        result.put("status", "error");
        result.put("error", "notimplemented");
        return result;
    }

    public Hashtable insertQSite(String privKey64,
                                 String siteName,
                                 String rootPath,
                                 Hashtable metadata
                                 )
        throws Exception
    {
        Hashtable result = new Hashtable();
        result.put("status", "error");
        result.put("error", "notimplemented");
        return result;
    }

    /**
     * returns true if all values in a given metadata set match their respective
     * regexps in criteria.
     * @param metadata a Hashtable of metadata to test. Set the 'magic' key 'searchmode'
     * to 'or' to make this an or-based test, otherwise defaults to and-based test.
     * @param criteria a Hashbable containing zero or more matching criteria
     */
    public boolean metadataMatchesCriteria(Hashtable metadata, Hashtable criteria)
    {
        boolean is_OrMode = false;

        // search mode defaults to AND unless explicitly set to OR
        if (criteria.containsKey("searchmode")) {
            if (((String)criteria.get("searchmode")).toLowerCase().equals("or")) {
                is_OrMode = true;
            }
        }

        // test all keys and regexp values in criteria against metadata
        Enumeration cKeys = criteria.keys();
        while (cKeys.hasMoreElements()) {

            String key = (String)cKeys.nextElement();
            if (key.equals("searchmode")) {
                // this is a meta-key - skip
                continue;
            }

            String cval = (String)criteria.get(key);
            String mval = (String)metadata.get(key);
            if (mval == null) {
                mval = "";
            }
            
            //System.out.println("metadataMatchesCriteria: key='"+key+"'"
            //    +" cval='"+cval+"'"
            //    +" mval='"+mval+"'");

            // reduced xor-based comparison
            if (!(mval.matches(cval) ^ is_OrMode)) {
                return is_OrMode;
            }
        }

        // completed all
        return !is_OrMode;
    }

    // ----------------------------------------------------------
    // METHODS - datastore
    // ----------------------------------------------------------

    /**
     * returns the number of known remote catalog entries
     */
    public int remoteCatalogSize()
    {
        return this.catalogIdx.numRecs;
    }
    
    /**
     * returns the number of locally stored items
     */
    public int localCatalogSize()
    {
        return this.contentIdx.numRecs;
    }
    
    /** return a list of nodeIds containing a key, or null if none */
    public Vector getItemLocation(String key) throws IOException {

        String dir1 = key.substring(0, 1);
        String dir2 = key.substring(0, 2);
        String fullPath = locationDir + sep + dir1 + sep + dir2 + sep + key;
        File fullFile = new File(fullPath);
        File parent = fullFile.getParentFile();
        if (!parent.isDirectory()) {
            parent.mkdirs();
        }
        
        if (!fullFile.exists()) {
            return null;
        }

        String p = new SimpleFile(fullPath, "r").read().trim();
        
        String [] locs = p.split("\\s+");
        Vector v = new Vector();
        int i, nlocs=locs.length;
        if (p.length() > 0) {
            for (i=0; i<nlocs; i++) {
                v.addElement(locs[i]);
            }
        }

        return v;
    }

    /** record a peer nodeID as a source for a content key */
    public void addItemLocation(String key, String nodeId) throws IOException
    {
        String dir1 = key.substring(0, 1);
        String dir2 = key.substring(0, 2);
        String fullPath = locationDir + sep + dir1 + sep + dir2 + sep + key;
        File fullFile = new File(fullPath);
        File parent = fullFile.getParentFile();
        if (!parent.isDirectory()) {
            parent.mkdirs();
        }
        
        // got the full path - contents of file is a nodeId, one per line,
        // that purportedly possesses the data
        if (!fullFile.exists()) {
            fullFile.createNewFile();
        }
        String p = new SimpleFile(fullPath, "r").read().trim();
        
        String [] locs = p.split("\\s+");
        Vector v = new Vector();
        int i, nlocs=locs.length;
        if (p.length() > 0) {
            for (i=0; i<nlocs; i++) {
                v.addElement(locs[i]);
            }
        }
        if (!v.contains(nodeId)) {
            v.addElement(nodeId);
            RandomAccessFile f = new RandomAccessFile(fullFile, "rw");
            f.seek(f.length());
            f.write((nodeId+"\n").getBytes());
        }
    }

    /**
     * retrieves the metadata for a given key
     * Wparam key key to retrieve metadata for
     * @return metadata as a PropertiesFile object, or null if not found
     */
    public QDataItem getLocalMetadata(String uri)
    {
        String filename = QUtil.sha64(uri);
        return getLocalMetadataForHash(filename);
    }
    
    public QDataItem getLocalMetadataForHash(String filename)
    {
        String metaPath = makeDataPath(filename);
        SimpleFile f;
        try {
            f = new SimpleFile(metaPath, "r");
        } catch (FileNotFoundException e) {
            return null;
        }

        // got at least metadata
        try {
            PropertiesFile pf = new PropertiesFile(metaPath);
            QDataItem item = new QDataItem(pf);
            return item;

        } catch (IOException e) {
            return null;
        }

    }

    /**
     * retrieves the metadata for a given key in our remote catalog
     * Wparam key key to retrieve metadata for
     * @return metadata as a Hashtable object, or null if not found
     */
    public QDataItem getLocalCatalogMetadata(String uri)
    {
        String metaPath = makeCatalogPath(uri);
        return getLocalCatalogMetadataForHash(metaPath);
    }
    
    public QDataItem getLocalCatalogMetadataForHash(String metaPath)
    {
        SimpleFile f;
        try {
            f = new SimpleFile(metaPath, "r");
        } catch (FileNotFoundException e) {
            System.out.println("getLocalCatalogMetadata: no such file '"+metaPath+"'");
            return null;
        }

        // got at least metadata
        try {
            PropertiesFile pf = new PropertiesFile(metaPath);
            QDataItem item = new QDataItem(pf);
            return item;

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

    }

    /**
     * called after a peerUpdate, stores one item of metadata to
     * our local catalog
     */
    public void addMetadataToCatalog(Hashtable metadata) {

        log.error("addMetadataToCatalog: metadata="+metadata);
        //System.out.println("addMetadataToCatalog: metadata="+metadata);

        String key = (String)metadata.get("key");
        String catPath = makeCatalogPath(key);
        
        try {
            new PropertiesFile(catPath, metadata);
            catalogIdx.add(key);
        } catch (IOException e) {
            log.error("failed to add key '"+key+"' to catalog");
        }

    }

    /**
     * <p>determines an absolute pathname for storing an item of a
     * given name. Uses multi-level directories in sourceforge style</p>
     * <p>For instance, if name is 'blah', and node's data dir lives
     * at /home/qserver/content, then the path will be /home/qserver/content/b/bl/blah.</p>
     * <p>Note that directories are created as needed</p>
     * @param name the filename to store
     * @return the full pathname to write to
     */
    public String makeDataPath(String name)
    {
       String dir1 = name.substring(0, 1);
       String dir2 = name.substring(0, 2);
       String fullPath = contentDir + sep + dir1 + sep + dir2 + sep + name;
       File fullFile = new File(fullPath);
       File parent = fullFile.getParentFile();
       if (!parent.isDirectory()) {
           parent.mkdirs();
       }
       
       // all done, parent dir now exists
       return fullPath;
    }

    /**
     * <p>determines an absolute pathname for cataloging an item of a
     * given name. Uses multi-level directories in sourceforge style</p>
     * <p>For instance, if name is 'blah', and node's data dir lives
     * at /home/qserver/content, then the path will be /home/qserver/content/b/bl/blah.</p>
     * <p>Note that directories are created as needed</p>
     * @param name the filename to store
     * @return the full pathname to write to
     */
    public String makeCatalogPath(String name)
    {
       String dir1 = name.substring(0, 1);
       String dir2 = name.substring(0, 2);
       String fullPath = catalogDir + sep + dir1 + sep + dir2 + sep + name;
       File fullFile = new File(fullPath);
       File parent = fullFile.getParentFile();
       if (!parent.isDirectory()) {
           parent.mkdirs();
       }
       
       // all done, parent dir now exists
       return fullPath;
    }

    
    /**
     * returns a PropertiesFile object for given peer
     * @param peerId
     * @return PropertiesFile object representing that peer's data
     */
    public QPeer getPeerRecord(String peerId) throws IOException, DataFormatException
    {
        // return peer's property object
        return new QPeer(this, peerId);
    }

    /**
     * Creates new peer record in our datastore
     * @param dest64 String - destination in base64 format
     */
    public void newPeer(String dest64) throws IOException, DataFormatException
    {
        Destination d = new Destination();
        d.fromBase64(dest64);
        newPeer(d);
    }
    
    /**
     * Fetches/Creates new peer record in our datastore
     */
    public void newPeer(Destination peerDest) throws IOException
    {
        String peerDest64 = peerDest.toBase64();
        
        // bail if this new peer is self
        if (peerDest64.equals(destStr)) {
            return;
        }

        // determine peerID
        String peerId = destToId(peerDest);

        // bail if peer is already known
        if (peers.containsKey(peerId)) {
            log.debug("newPeer: already know peer "+peerId+" ("+peerDest64.substring(0, 12)+"...)");
            return;
        }

        // where does the peer file live?
        String peerPath = peersDir + sep + peerId;
        
        // get the record
        QPeer peerRec = new QPeer(this, peerDest);

        // and write it into index
        peersIdx.add(peerId);
        
        // and stick into our global peers map
        peers.put(peerId, peerRec);
        
        // note that we've got a new peer
        conf.incrementIntProperty("numPeers");

        // and, finally, schedule in a greeting to this peer
        if (isClient) {
            schedulePeerUpdateJob(peerRec);
        } else {
            schedulePeerGreetingJob(peerRec);
        }
    }

    /**
     * Get a list of peers, in order of their kademlia-closeness to
     * a given uri
     */
    public Vector peersClosestTo(String uri, int max) {

        String itemHash = sha256Base64(uri);

        // get our peer list as a vector
        Vector allPeers = new Vector();
        Iterator peerRecs = peers.values().iterator();
        while (peerRecs.hasNext()) {
            allPeers.addElement(peerRecs.next());
        }

        // create a comparator to find peers closest to URI
        QKademliaComparator comp = new QKademliaComparator(this, itemHash);
        
        // sort the peerlist according to k-closeness of uri
        Collections.sort(allPeers, comp);

        // get the closest (up to) n peers
        int npeers = Math.min(max, allPeers.size());
        List closestPeers = allPeers.subList(0, npeers);
        
        return new Vector(closestPeers);
    }

    // ----------------------------------------------------------
    // METHODS - node status indicators
    // ----------------------------------------------------------
    
    /** return uptime of this node, in seconds */
    public int nodeUptime()
    {
        Date now = new Date();
        return (int)((now.getTime() - nodeStartTime.getTime()) / 1000);
    }

    /** return node load, as float */
    public float nodeLoad()
    {
        long now = new Date().getTime();
        long dt = now - load_tPrev;
        load_tPrev = now;
        
        //System.out.println("nodeLoad: dt="+dt+" load_yPrev="+load_yPrev);
        
        load_yPrev = load_yPrev * Math.exp(-((double)dt) / load_kFall);
        
        //System.out.println("nodeLoad: y="+load_yPrev);
        
        return (float)load_yPrev;
    }

    public float nodeLoadAfterHit()
    {
        //System.out.println("nodeLoadAfterHit: "+load_yPrev+" before recalc");
        // update decay phase
        nodeLoad();
        
        //System.out.println("nodeLoadAfterHit: "+load_yPrev+" after recalc");
        
        // and add spike
        load_yPrev += (1.0 - load_yPrev) / load_kRise;
        
        //System.out.println("nodeLoadAfterHit: "+load_yPrev+" after hit");
        //System.out.println("-----------------------------------------");
        
        return (float)load_yPrev;
    }

    /**
     * Determine an advised time for next contact from a peer node.
     * This is based on the node's current load
     */
    public int getAdvisedNextContactTime()
    {
        //long now = new Date().getTime() / 1000;
        // fudge 30 secs from now
        //return (int)(now + 30);

        // formula here is to advise a backup delay of:
        //   loadBackoffMin + 2 ** (loadBackoffBits * currentLoad)
        return nowSecs()
            + load_backoffMin
            + (int)(Math.pow(load_backoffBase, load_backoffBits * load_yPrev));
    }


    // ----------------------------------------------------------
    // METHODS - general
    // ----------------------------------------------------------

    public String base64Enc(String raw)
    {
        return base64Enc(raw.getBytes());
    }
    
    public String base64Enc(byte[] raw)
    {
        return net.i2p.data.Base64.encode(raw);
    }

    public String base64Dec(String enc)
    {
        return new String(net.i2p.data.Base64.decode(enc));
    }

    public String sha256Base64(String raw)
    {
        return sha256Base64(raw.getBytes());
    }

    public String sha256Base64(byte [] raw)
    {
        //return base64Enc(sha256(raw));
        return base64Enc(i2p.sha().calculateHash(raw).getData()).replaceAll("[=]+", "");
    }

    /**
     * simple interface for sha256 hashing
     * @param raw a String to be hashed
     * @return the sha256 hash, as binary
     */
    public String sha256(String raw)
    {
        return sha256(raw.getBytes());
    }

    public String sha256(byte [] raw)
    {
        return new String(i2p.sha().calculateHash(raw).getData());
        
        //SHA256Generator shagen = new SHA256Generator(i2p);
        //return new String(shagen.calculateHash(raw).getData());
        //Sha256 s = new Sha256();
        //s.update(raw, 0, raw.length);
        //byte [] d = s.digest();
        //for (int i=0; i<d.length; i++) {
        //    System.out.print(d[i]+",");
        //}
        //System.out.println("");
        //return new String(d);
    }

    public int nowSecs() {
        return (int)(new Date().getTime() / 1000);
    }

    public String destToId(String destStr)
    {
        String destRaw = base64Dec(destStr);
        return sha256Base64(destRaw);
    }

    /**
     * computes the node ID of a given destination
     */
    public String destToId(Destination d)
    {
        return sha256Base64(d.toString());
    }

    /** formats a positive int n to witdh chars
     * @param n integer to format to string, should be >= 0
     * @param width minimum width of string, which will get padded
     * with leading zeroes to make up the desired width
     */
    public String intFmt(int n, int width)
    {
        String nS = String.valueOf(n);
        while (nS.length() < width)
        {
            nS = "0" + nS;
        }
        return nS;
    }

    public void log__(String msg)
    {
        System.out.println("QNode: " + msg);

        // bail if logFile not yet created, can help in avoiding npe
        if (logFile == null) {
            return;
        }

        try {
            Calendar now = Calendar.getInstance();
            String timestamp
                = intFmt(now.YEAR, 4)
                + "-"
                + intFmt(now.MONTH, 2)
                + "-"
                + intFmt(now.DAY_OF_MONTH, 2)
                + "-"
                + intFmt(now.HOUR_OF_DAY, 2)
                + ":"
                + intFmt(now.MINUTE, 2)
                + ":"
                + intFmt(now.SECOND, 2)
                + " ";

            synchronized (logFile) {
                logFile.seek(logFile.length());
                logFile.write((timestamp + msg + "\n").getBytes());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public void dj() {
        dumpjobs();
    }

    public void dumpjobs() {
        
        jobQueue.printWaiting();
    }

    public void foo() {
        System.out.println("QNode.foo: isClient="+isClient);
    }

}

