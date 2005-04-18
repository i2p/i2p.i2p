
package net.i2p.aum;

import java.lang.*;
import java.io.*;
import java.util.*;
import java.net.*;

import org.apache.xmlrpc.*;

import net.i2p.*;
import net.i2p.client.*;
import net.i2p.client.streaming.*;
import net.i2p.data.Base64;
import net.i2p.util.*;
import net.i2p.data.*;

/**
 * A simple class providing callable xmlrpc server methods, gets linked in to
 * the server demo.
 */
public class I2PXmlRpcDemoClass
{
    public int add1(int n) {
        return n + 1;
    }
    
    public String bar(String arg1, String arg2) {
        System.out.println("Demo: got hit to bar: arg1='"+arg1+"', arg2='"+arg2+"'");
        return "I2P demo xmlrpc server(foo.bar): arg1='"+arg1+"', arg2='"+arg2+"'";
    }

}

