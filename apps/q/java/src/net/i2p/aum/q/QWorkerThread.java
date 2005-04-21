/*
 * QWorkerThread.java
 *
 * Created on April 17, 2005, 2:44 PM
 */

package net.i2p.aum.q;

import java.util.*;
import java.io.*;

import net.i2p.aum.*;

/**
 * Thread which performs a single background job for a nod
 */

class QWorkerThread extends Thread {

    QNode node;
    Hashtable job;
    String jobTime;
    String peerId;
    String jobDesc;
    
    /*
     * Creates this thread for executing a background job for the node
     * @param node the node for which this job is to run
     * @param jobTime unixtime-milliseconds at which job is to run,
     * represented as string because it denotes a file in the node's jobs dir
     */
    public QWorkerThread(QNode node, String jobTime) {
        this.node = node;
        this.jobTime = jobTime;
    }
    
    public void run() {
        try {
            node.log.info("worker: executing job: "+jobTime);

            // reconstitute the job from its serialisation in jobs directory
            job = node.loadJob(jobTime);
            jobDesc = node.loadJobDescription(jobTime);

            // a couple of details
            String cmd = (String)job.get("cmd");
            peerId = (String)job.get("peerId");

            // dispatch off to required handler routine
            if (cmd.equals("getUpdate")) {
                doGetUpdate();
            }
            else if (cmd.equals("hello")) {
                doHello();
            }
            else if (cmd.equals("localPutItem")) {
                doLocalPutItem();
            }
            else if (cmd.equals("uploadItem")) {
                doUploadItem();
            }
            else if (cmd.equals("test")) {
                doTest();
            }
            else if (cmd.equals("shutdown")) {
                doShutdown();
            }
            else {
                node.log.error("workerthread.run: unrecognised command '"+cmd+"'");
                System.out.println("workerthread.run: unrecognised command '"+cmd+"'");
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            node.log.warn("worker thread crashed");
        }
        
        // finished (or failed), so replenish the jobs pool
        node.threadPool.release();
        
        // and remove the job record and description
        try {
            new File(node.jobsDir + node.sep + jobTime).delete();
            new File(node.jobsDir + node.sep + jobTime + ".desc").delete();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public void doTest() throws Exception {
        
        String msg = (String)job.get("msg");
        System.out.println("TESTJOB: msg='"+msg+"'");
    }
    
    public void doShutdown() throws Exception {
        
        try {
            new File(node.jobsDir + node.sep + jobTime).delete();
            new File(node.jobsDir + node.sep + jobTime + ".desc").delete();
        } catch (Exception e) {
            e.printStackTrace();
        }

        SimpleFile f = new SimpleFile("/tmp/eeee", "rws");
        f.write("xxx");
        node.isRunning = false;
        Runtime.getRuntime().halt(0);
    }
    
    public void doLocalPutItem() throws Exception {
        Hashtable metadata = (Hashtable)job.get("metadata");
        String path = (String)job.get("localDataFilePath");
        SimpleFile f = new SimpleFile(path, "r");
        byte [] data = f.readBytes();
        
        System.out.println("doLocalPutItem: path='"+path+"' dataLen="+data.length+" metadata="+metadata);
        node.putItem(metadata, data);
    }
    
    /**
     * <p>Upload a locally-inserted data item to n remote hubs.</p>
     * <p>This is one intricate algorithm. The aim is to upload the content
     *    item to the 3 peers which are closest (Kademlia-wise) to the item's URI.
     *    Some requirements include:
     *    <ul>
     *     <li>If we discover new peers over time, we have to consider these peers
     *         as upload targets</li>
     *     <li>If upload to an individual peer fails, we have to retry a few times</li>
     *     <li>If there aren't enough viable peers yet, we need to keep rescheduling this
     *         job till enough peers come online</li>
     *     <li>Don't hog a thread slot on the jobs queue, give other jobs a chance to run</li>
     *    </ul>
     * </p>
     *
     */
    public void doUploadItem() throws QException {
        QDataItem item = (QDataItem)job.get("item");
        String uri = (String)item.get("uri");
        String desc = "uploadItem:uri="+uri;
        byte [] data = item._data;
        
        Hashtable peersUploaded = (Hashtable)job.get("peersUploaded");
        Hashtable peersPending = (Hashtable)job.get("peersPending");
        Hashtable peersFailed = (Hashtable)job.get("peersFailed");
        Hashtable peersNumTries = (Hashtable)job.get("peersNumTries");
        
        String itemHash = item.getStoreFilename();
        QPeer peerRec;
        
        // get current list of up to 100 closest peers to item's URI
        Vector cPeers = node.peersClosestTo(uri, 100);

        // loop on this list, try to upload item to n of them
        for (Enumeration en = cPeers.elements(); en.hasMoreElements();) {
            QPeer peer = (QPeer)en.nextElement();
            String peerId = peer.getId();
            
            // skip this peer if we've already succeeded or failed with it
            if (peersFailed.containsKey(peerId) || peersUploaded.containsKey(peerId)) {
                continue;
            }
            
            // if there are less than 3 or more pending peers, add this peer to
            // pending list, otherwise skip it
            if (!peersPending.containsKey(peerId)) {
                if (peersPending.size() < 3) {
                    peersPending.put(peerId, "");
                } else {
                    continue;
                }
            }

            // try to insert item to this peer
            boolean uploadedOk;
            try {
                Hashtable res = node.peerPutItem(peerId, item, item._data);
                if (res.containsKey("status") && ((String)res.get("status")).equals("ok")) {
                    // successful upload
                    uploadedOk = true;
                } else {
                    // upload failed for some reason
                    uploadedOk = false;
                    System.out.println("upload failure:"+res);
                }
            } catch (Exception e) {
                // possibly because peer is offline or presently unreachable
                uploadedOk = false;
                e.printStackTrace();
                System.out.println("upload failure");
            }
            
            // how'd the upload go?
            if (uploadedOk) {
                // successful - remove from pending list, add to success list
                peersPending.remove(peerId);
                peersNumTries.remove(peerId);
                peersUploaded.put(peerId, "");
                
                // have we successfully uploaded to 3 or more peers yet?
                if (peersUploaded.size() >= 3) {
                    // yep, this job has now run its course and can expire
                    return;
                } else {
                    // bust out so we don't hog a scheduler slot
                    node.runAfter(5000, job, desc);
                    return;
                }
                
            } else {
                // insert failed
                // increment retry count, fail this peer if retries maxed out
                int numTries = ((Integer)peersNumTries.get(peerId)).intValue() + 1;
                if (numTries > 4) {
                    // move peer from pending list to failed list
                    peersPending.remove(peerId);
                    peersNumTries.remove(peerId);
                    peersFailed.put(peerId, "");
                }
                
                // bust out so we don't hog a scheduler slot
                node.runAfter(30000, job, desc);
                return;
            }
        }
        
        // we'return out of peers, reschedule this job to retry in an hour's time
        node.runAfter(3600000, job, desc);
    }
    
    public void doHello() {
        QPeer peerRec = (QPeer)node.peers.get(peerId);
        
        node.log.debug("doHello: "+node.id+" -> "+peerId);
        
        try {
            // execute peers list req on peer
            Hashtable result = node.peerHello(peerId, node.destStr);
            
            // see what happened
            String status = (String)result.get("status");
            if (status.equals("ok")) {
                peerRec.markAsGreeted();
                
                // and, schedule in regular peersList updates
                node.schedulePeerUpdateJob(peerRec);
            }
        } catch (Exception e) {
            node.log.warn("Got an xmlrpc client failure, trying again in 1 hour", e);
            
            // schedule another attempt in 2 hours
            Hashtable job = new Hashtable();
            job.put("cmd", "hello");
            job.put("peerId", peerId);
            node.runAfter(3600000, job, "hello:peerId="+peerId);
        }
    }
    
    public void doGetUpdate() {
        QPeer peerRec = (QPeer)node.peers.get(peerId);
        int timeLastPeersUpdate = peerRec.getTimeLastUpdate();
        int timeNextContact;
        int doCatalog = ((Integer)(job.get("includeCatalog"))).intValue();
        int doPeers = ((Integer)(job.get("includePeers"))).intValue();
        Vector peers;
        Vector items;
        
        node.log.info("doGetUpdate: "+node.id+" -> "+peerId);

        try {
            // execute peers list req on peer
            Hashtable result = node.peerGetUpdate(
            peerId, timeLastPeersUpdate, doPeers, doCatalog);
            
            // see what happened
            String status = (String)result.get("status");
            if (status.equals("ok")) {
                
                node.log.debug("doGetUpdate: successful, result="+result);
                
                int i;
                
                // success - add all new peers
                peers = (Vector)result.get("peers");
                int npeers = peers.size();
                for (i=0; i<npeers; i++) {
                    String destStr = (String)peers.get(i);
                    String destId = node.destToId(destStr);
                    // test if this is a new dest
                    if (!node.peers.containsKey(destId)) {
                        node.log.debug("doGetPeerList: adding new peer "+destId);
                        node.newPeer(destStr);
                    }
                    else {
                        node.log.debug("doGetPeerList: we already know peer "+destId);
                    }
                }
                
                // also add all new items
                items = (Vector)result.get("items");
                int nitems = items.size();
                for (i=0; i<nitems; i++) {
                    Hashtable metadata = (Hashtable)(items.get(i));
                    node.addMetadataToCatalog(metadata);
                    String key = (String)metadata.get("key");
                    node.addItemLocation(key, peerId);
                }
                
                // get time of next update
                int nextTime = ((Integer)result.get("timeNextContact")).intValue();
                int lastTime = ((Integer)result.get("timeUpdateEnds")).intValue();
                peerRec.setTimeNextContact(nextTime);
                peerRec.setTimeLastUpdate(lastTime);
                
                //System.out.println("doGetUpdate: timeNextContact="+nextTime);
                
                // schedule another job at recommended time
                Hashtable job = new Hashtable();
                job.put("cmd", "getUpdate");
                job.put("peerId", peerId);
                job.put("includePeers", new Integer(doPeers));
                job.put("includeCatalog", new Integer(doCatalog));
                node.runAt(((long)nextTime)*1000, job, "getUpdate:peerId="+peerId);
            }
        } catch (Exception e) {
            node.log.warn("xmlrpc client failure, rescheduling 180 secs from now", e);
            
            // schedule another attempt in 30 secs
            Hashtable job = new Hashtable();
            job.put("cmd", "getUpdate");
            job.put("peerId", peerId);
            job.put("includePeers", new Integer(doPeers));
            job.put("includeCatalog", new Integer(doCatalog));
            node.runAfter(180000, job, "getUpdate:peerId="+peerId);
        }
        
        //peerRec.setProperty("timeLastPeersUpdate", "0");
        //peerRec.setProperty("timeLastContentUpdate", "0");
        //peerRec.setProperty("timeLastContact", "0");
        //peerRec.setProperty("timeNextContact", "0");
        
    }
}
    

