/*
 * QTest.java
 *
 * Created on March 23, 2005, 11:34 PM
 */

package net.i2p.aum.q;

import java.*;
import java.lang.*;
import java.io.*;
import java.util.*;

import net.i2p.*;
import net.i2p.data.*;

import net.i2p.aum.*;


/**
 *
 * @author  david
 */
public class QTest {

    QServerNode server;
    
    QClientNode client;

    /** Creates a new instance of QTest */
    public QTest() {
    }

    /**
     * performs a series of tests on client node
     */
    public void testClientNode()
        throws IOException, DataFormatException, I2PException, QException
    {
        print("Creating new client node");
        QClientNode node = new QClientNode();
        
        print("Starting node background stuff");
        node.start();
        
        print("Inserting new plain hash data item");
        byte [] data = "Hello, world".getBytes();
        Hashtable meta = new Hashtable();
        meta.put("title", "simple test");
        meta.put("type", "text");
        meta.put("path", "/test.txt");
        Hashtable res = node.putItem(meta, data);
        print("putItem result="+res);
        if (!res.get("status").equals("ok")) {
            print("putItem fail: error="+res.get("error"));
            node.interrupt();
            return;
        }

        String uri = (String)res.get("uri");
        print("putItem successful: uri="+uri);
        
        print("now attempting to retrieve");
        Hashtable res1 = node.getItem(uri);
        print("getItem: result="+res1);
        if (!res1.get("status").equals("ok")) {
            print("getItem fail: error="+res.get("error"));
            node.interrupt();
            return;
        }
        byte [] data1 = (byte [])res1.get("data");
        String dataStr = new String(data1);
        print("getItem: success, data="+dataStr);

        print("now searching for what we just inserted");
        Hashtable crit = new Hashtable();
        crit.put("type", "text");
        Hashtable res1a = node.search(crit);
        print("After search: res="+res1a);
        
        print("now creating a keypair");
        Hashtable keys = node.newKeys();
        String pub = (String)keys.get("publicKey");
        String priv = (String)keys.get("privateKey");
        print("public="+pub);
        print("private="+priv);
        
        print("Inserting new secure space data item");
        byte [] data2 = "The quick brown fox".getBytes();
        Hashtable meta2 = new Hashtable();
        meta2.put("title", "simple test 2");
        meta2.put("type", "text");
        meta2.put("path", "/test.txt");
        meta2.put("privateKey", priv);
        Hashtable res2 = node.putItem(meta2, data2);
        print("putItem result="+res2);
        if (!res2.get("status").equals("ok")) {
            print("putItem fail: error="+res2.get("error"));
            node.interrupt();
            return;
        }

        String uri2 = (String)res2.get("uri");
        print("putItem successful: uri="+uri2);
        
        print("now attempting to retrieve");
        Hashtable res2a = node.getItem(uri2);
        print("getItem: result="+res2a);
        if (!res2a.get("status").equals("ok")) {
            print("getItem fail: error="+res.get("error"));
            node.interrupt();
            return;
        }
        byte [] data2a = (byte [])res2a.get("data");
        String dataStr2a = new String(data2a);
        print("getItem: success, data="+dataStr2a);
        
    }

    public void print(String msg) {
        System.out.println(msg);
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        QTest test = new QTest();
        try {
            test.testClientNode();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

