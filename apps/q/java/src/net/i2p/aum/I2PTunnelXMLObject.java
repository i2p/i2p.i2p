package net.i2p.aum;

import java.util.Hashtable;

import net.i2p.i2ptunnel.I2PTunnelXMLWrapper;

/**
 * Defines the I2P tunnel management methods which will be
 * exposed to XML-RPC clients
 * Methods in this class are forwarded to an I2PTunnelXMLWrapper object
 */
public class I2PTunnelXMLObject
{
    protected I2PTunnelXMLWrapper tunmgr;

    /**
     * Builds the interface object. You normally shouldn't have to
     * instantiate this directly - leave it to I2PTunnelXMLServer
     */
    public I2PTunnelXMLObject()
    {
        tunmgr = new I2PTunnelXMLWrapper();
    }

    /**
     * Generates an I2P keypair, returning a dict with keys 'result' (usually 'ok'),
     * priv' (private key as base64) and 'dest' (destination as base64)
     */
    public Hashtable genkeys()
    {
        return tunmgr.xmlrpcGenkeys();
    }

    /**
     * Get a list of active TCP tunnels currently being managed by this
     * tunnel manager.
     * @return a dict with keys 'status' (usually 'ok'),
     * 'jobs' (a list of dicts representing each job, each with keys 'job' (int, job
     * number), 'type' (string, 'server' or 'client'), port' (int, the port number).
     * Also for server, keys 'host' (hostname, string) and 'ip' (IP address, string).
     * For clients, key 'dest' (string, remote destination as base64).
     */
    public Hashtable list()
    {
        return tunmgr.xmlrpcList();
    }

    /**
     * Attempts to find I2P hostname in hosts.txt.
     * @param hostname string, I2P hostname
     * @return dict with keys 'status' ('ok' or 'fail'),
     * and if successful lookup, 'dest' (base64 destination).
     */
    public Hashtable lookup(String hostname)
    {
        return tunmgr.xmlrpcLookup(hostname);
    }

    /**
     * Attempt to open client tunnel
     * @param port local port to listen on, int
     * @param dest remote dest to tunnel to, base64 string
     * @return dict with keys 'status' (string - 'ok' or 'fail').
     * If 'ok', also key 'result' with text output from tunnelmgr
     */
    public Hashtable client(int port, String dest)
    {
        return tunmgr.xmlrpcClient(port, dest);
    }

    /**
     * Attempts to open server tunnel
     * @param host TCP hostname of TCP server to tunnel to
     * @param port number of TCP server
     * @param key - base64 private key to receive I2P connections on
     * @return dict with keys 'status' (string, 'ok' or 'fail').
     * if 'fail', also a key 'error' with explanatory text.
     */
    public Hashtable server(String host, int port, String key)
    {
        return tunmgr.xmlrpcServer(host, port, key);
    }

    /**
     * Close an existing tunnel
     * @param jobnum (int) job number of connection to close
     * @return dict with keys 'status' (string, 'ok' or 'fail')
     */
    public Hashtable close(int jobnum)
    {
        return tunmgr.xmlrpcClose(jobnum);
    }

    /**
     * Close an existing tunnel
     * @param jobnum (string) job number of connection to close as string,
     * 'all' to close all jobs.
     * @return dict with keys 'status' (string, 'ok' or 'fail')
     */
    public Hashtable close(String job)
    {
        return tunmgr.xmlrpcClose(job);
    }

    /**
     * Close zero or more tunnels matching given criteria
     * @param criteria A dict containing zero or more of the keys:
     * 'job' (job number), 'type' (string, 'server' or 'client'),
     * 'host' (hostname), 'port' (port number),
     * 'ip' (IP address), 'dest' (string, remote dest)
     */
    public Hashtable close(Hashtable criteria)
    {
        return tunmgr.xmlrpcClose(criteria);
    }

    /**
     * simple method to help with debugging your client prog
     * @param x an int
     * @return x + 1
     */
    public int bar(int x)
    {
        System.out.println("foo invoked");
        return x + 1;
    }

    /**
     * as for bar(int), but returns zero if no arg given
     */
    public int bar()
    {
        return bar(0);
    }
    
}


