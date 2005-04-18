package net.i2p.i2ptunnel;

import org.apache.xmlrpc.*;
import java.lang.*;
import java.util.*;
import java.io.*;
import java.lang.reflect.*;


import net.i2p.*;
import net.i2p.client.*;
import net.i2p.client.streaming.*;
import net.i2p.data.Base64;
import net.i2p.util.*;
import net.i2p.util.*;
import net.i2p.data.*;
import net.i2p.i2ptunnel.*;


/**
 * subclasses I2PTunnel, adding some methods to facilitate
 * the I2PTunnel XML-RPC server. We have to put this class
 * into net.i2p.i2ptunnel, because there are some non-public
 * methods we need to access. For doco on the methods in
 * this class refer to the corresponding doco in I2PTunnelXMLObject
 */
public class I2PTunnelXMLWrapper extends I2PTunnel
{
    public Hashtable xmlrpcLookup(String hostname)
    {
        String cmdOut;
        String [] args;
        BufferLogger log = new BufferLogger();
        Hashtable result = new Hashtable();
    
        System.out.println("xmlrpcLookup: hostname='" + hostname + "'");
    
        args = new String[1];
        args[0] = hostname;
    
        //System.out.println("Invoking runLookup");    
    
        runLookup(args, log);
        //System.out.println("Back from runLookup");
    
        cmdOut = log.getBuffer().trim();
        //System.out.println("cmdOut=" + cmdOut);
    
        if (cmdOut.equals("Unknown host"))
        {
            result.put("status", "fail");
        }
        else
        {
            result.put("status", "ok");
            result.put("dest", cmdOut);
        }
    
        return result;
    }
    
    public Hashtable xmlrpcList()
    {
        Hashtable result = new Hashtable();
        BufferLogger log = new BufferLogger();
        Vector jobs = new Vector();
    
        // run the listing
        runList(log);
    
        String raw = log.getBuffer();
        //System.out.println("list: raw='"+raw+"'");
        if (raw.equals(""))
        {
            System.out.println("list: no jobs");
        }
        else
        {
            // got 1 or more job lines, parse them and assemble into jobs array
            String [] rawlines = raw.trim().split("\\n+");
        
            int numLines = Array.getLength(rawlines);
            int i;
        
            System.out.println("list: numLines="+numLines);
        
            for (i = 0; i < numLines; i++)
            {
                String line = rawlines[i];
        
                System.out.println("list: line["+i+"]="+line);
        
                // carve up each job line into constituent fields
                String [] flds = line.split("\\s+");
                
                String jobFld = flds[0];
                String tcpFld = flds[1];
                String typeFld = flds[2];
                String i2pFld = flds[3];
        
                Integer portInt;
                int port;
        
                Hashtable job = new Hashtable();
        
                // stick in jobnumber, as int
                Integer jobNumInt = new Integer(jobFld.substring(1, jobFld.length()-1));
                int jobNum = jobNumInt.intValue();
                job.put("job", jobNumInt);
        
                // rest is type-dependent
                if (typeFld.equals("<-"))
                {
                    //System.out.println("server");
        
                    // fill out dict for a 'server' result
                    job.put("type", "server");
        
                    // tcp side is in form "hostname/ipaddr:port", carve up
                    String hostsStr;
                    String hostStr;
                    String ipStr;
                    String portStr;
                    
                    String [] tmpFlds = tcpFld.split(":");
        
                    //System.out.println("tmpFlds="+tmpFlds);
        
                    hostsStr = tmpFlds[0];
                    portStr = tmpFlds[1];
        
                    //System.out.println("hostsStr="+hostsStr+" portStr="+portStr);
        
                    portInt = new Integer(portStr);
                    port = portInt.intValue();
        
                    tmpFlds = hostsStr.split("/");
                    hostStr = tmpFlds[0];
                    ipStr = tmpFlds[1];
        
                    //System.out.println("hostStr="+hostStr+" ipStr="+ipStr);
        
                    job.put("host", hostStr);
                    job.put("ip", ipStr);
                    job.put("port", portInt);
                }
                else
                {
                    //System.out.println("client");
        
                    // fill out dict for a 'client' result
                    job.put("type", "client");
        
                    portInt = new Integer(tcpFld);
                    port = portInt.intValue();
        
                    job.put("dest", i2pFld);
                    job.put("port", portInt);
                }
        
                jobs.add(job);
            }
        }
    
        result.put("status", "ok");
        //result.put("rawlist", raw);
        //result.put("rawlines", rawlines);
        result.put("jobs", jobs);
    
        return result;
    }
    
    public Hashtable xmlrpcClient(int portNum, String dest)
    {
        Hashtable result = new Hashtable();
        BufferLogger log = new BufferLogger();
    
        String [] args = new String[2];
    
        Integer portNumInt = new Integer(portNum);
        args[0] = portNumInt.toString();
        args[1] = dest;
        runClient(args, log);
    
        String reply = log.getBuffer();
    
        result.put("status", "ok");
        result.put("result", reply);
    
        return result;
    }
    
    public Hashtable xmlrpcServer(String host, int port, String key)
    {
        Hashtable result = new Hashtable();
        BufferLogger log = new BufferLogger();
    
        File keyBinFile;
    
        // create array for cli args
        String [] args = new String[3];
    
        // arg0 is host - easy
        args[0] = host;
    
        // arg1 is port, convert from int to string
        Integer portInt = new Integer(port);
        args[1] = portInt.toString();
    
        // arg2 is key filename
        // gotta convert base64 string to bin, write to
        // a temporary file, and extract the file path
        String keyBin = new String(net.i2p.data.Base64.decode(key));
        try
        {
            keyBinFile = File.createTempFile("xmlrpc_", ".priv");
            keyBinFile.createNewFile();
            FileWriter keyBinFileWriter = new FileWriter(keyBinFile);
            keyBinFileWriter.write(keyBin, 0, keyBin.length());
            keyBinFileWriter.flush();
            keyBinFileWriter.close();
        }
        catch (IOException e)
        {
            result.put("status", "fail");
            result.put("error", "IOException");
            return result;
        }
        args[2] = keyBinFile.getAbsolutePath();
    
        // do the run cmd
        runServer(args, log);
    
        // finished with privkey bin file, delete it
        keyBinFile.delete();
        
        String reply = log.getBuffer().trim();
        
        // Replies with 'Ready!' if ok, something else if not
        if (reply.equals("Ready!"))
        {
            result.put("status", "ok");
        }
        else
        {
            result.put("status", "fail");
            result.put("error", reply);
        }
    
        return result;
    }
    
    //
    // generate a new keypair
    //
    
    public Hashtable xmlrpcGenkeys()
    {
        Hashtable result = new Hashtable();
        BufferLogger log = new BufferLogger();
        String reply = "";
    
        // need a couple of output streams to collect the keys
        ByteArrayOutputStream privStream = new ByteArrayOutputStream();
        ByteArrayOutputStream destStream = new ByteArrayOutputStream();
    
        // generate the keys
        makeKey(privStream, destStream, log);
    
        // extract keys from output streams and convert into base64 strings
        String priv64 = net.i2p.data.Base64.encode(privStream.toByteArray());
        String dest64 = net.i2p.data.Base64.encode(destStream.toByteArray());
    
        // build up result map and bail
        result.put("status", "ok");
        result.put("priv", priv64);
        result.put("dest", dest64);
    
        return result;
    }
    
    // various front-ends for close
    
    public Hashtable xmlrpcClose(int jobnum)
    {
        Hashtable result = new Hashtable();
        BufferLogger log = new BufferLogger();
        String [] args = new String[1];
    
        String jobStr = String.valueOf(jobnum);
        args[0] = jobStr;
    
        runClose(args, log);
    
        String logOut = log.getBuffer();
    
        //System.out.println("close(int): got "+logOut);
    
        result.put("status", "ok");
        return result;
    }
    
    public Hashtable xmlrpcClose(Hashtable criteria)
    {
        Hashtable result = new Hashtable();
        BufferLogger log = new BufferLogger();
        String [] args = new String[1];
    
        // try special case, job==-1 means close all jobs
        if (criteria.containsKey("job"))
        {
            Object j = criteria.get("job");
            //System.out.println("job="+j);
        }
            
    
        if (criteria.containsKey("job") && criteria.get("job").equals("all"))
        {
            // easy - just fire a 'close all'
            //System.out.println("close: job=all");
            return xmlrpcClose("all");
        }
    
        // harder - get joblist and match criteria
        // get a list of running jobs
        Hashtable jobs = xmlrpcList();
        Vector jobsList = (Vector)(jobs.get("jobs"));
        //int numJobs = Array.length(jobsList);
        int numJobs = jobsList.size();
    
        int i;
        for (i = 0; i < numJobs; i++)
        {
            boolean closing = false;
        
            // fetch each job, and test if it matches our criteria
            Hashtable job = (Hashtable)jobsList.get(i);
            //System.out.println("close: existing job: "+job);
    
            if (criteria.containsKey("job") && criteria.get("job").equals(job.get("job")))
            {
                //System.out.println("close: matched jobnum");
                closing = true;
            }
            if (criteria.containsKey("type") && criteria.get("type").equals(job.get("type")))
            {
                //System.out.println("close: matched type");
                closing = true;
            }
            if (criteria.containsKey("port") && criteria.get("port").equals(job.get("port")))
            {
                //System.out.println("close: matched port");
                closing = true;
            }
            if (criteria.containsKey("host") && job.containsKey("host")
                && (criteria.get("host").equals(job.get("host"))))
            {
                //System.out.println("close: matched host="+criteria.get("host"));
                closing = true;
            }
            if (criteria.containsKey("ip") && job.containsKey("ip")
                && (criteria.get("ip").equals(job.get("ip"))))
            {
                //System.out.println("close: matched ip="+criteria.get("ip"));
                closing = true;
            }
    
            if (closing)
            {
                String jobStr = String.valueOf(job.get("job"));
                //System.out.println("close: matched criteria - closing job "+jobStr);
                args[0] = jobStr;
                runClose(args, log);
                result.put("status", "ok");
                return result;
            }
        }
    
        result.put("status", "ok");
        return result;
    }
    
    public Hashtable xmlrpcClose(String job)
    {
        Hashtable result = new Hashtable();
        BufferLogger log = new BufferLogger();
        String [] args = new String[1];
    
        // easy 'close all' case
        if (job.equals("all"))
        {
            args[0] = "all";
            runClose(args, log);
            result.put("status", "ok");
            return result;
        }
    
        // try to convert job to an int
        try
        {
            int jobnum = new Integer(job).intValue();
            return xmlrpcClose(jobnum);
        }
        catch (NumberFormatException e)
        {
            result.put("status", "error");
            result.put("error", "cannot convert job number arg to an int");
            return result;
        }
    }
    
}


