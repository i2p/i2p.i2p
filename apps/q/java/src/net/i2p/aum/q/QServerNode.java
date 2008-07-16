/*
 * QServer.java
 *
 * Created on 20 March 2005, 23:23
 */

package net.i2p.aum.q;

import java.io.IOException;
import java.util.Properties;

import net.i2p.I2PException;
import net.i2p.aum.I2PXmlRpcServerFactory;
import net.i2p.aum.http.I2PHttpServer;
import net.i2p.aum.http.MiniHttpServer;
import net.i2p.data.DataFormatException;

/**
 *
 * Implements Q Server nodes.
 */
public class QServerNode extends QNode {

    /**
     * default datastore directory
     */
    public static String defaultStoreDir = ".quartermaster_server";

    /**
     * can set this to 0 before instantiating servers, to set tunnel length
     * for debugging purposes
     **/
    public static int tunLength = 2;

    public I2PXmlRpcServerFactory xmlRpcServerFactory;

    public String nodeType = "Server";

    /** Creates a new instance of QServer */
    public QServerNode() throws IOException, DataFormatException, I2PException
    {
        super(System.getProperties().getProperty("user.home") + sep + defaultStoreDir);
    }
    
    /**
     * Creates a Q node in server mode, using specified datastore directory
     * @param dataDir absolute pathname where this server's datastore tree is
     * located. If tree doesn't exist, it will be created along with new keys
     */
    public QServerNode(String dataDir) throws IOException, DataFormatException, I2PException
    {
        super(dataDir);
    }

    /**
     * performs mode-specific node setup
     */
    public void setup() throws DataFormatException, I2PException
    {
    }

    /**
     * <p>Sets up and launches an xml-rpc server for servicing requests
     * to this node.</p>
     * <p>For server nodes, the xml-rpc server listens within I2P on the
     * node's destination.</p>
     * <p>For client nodes, the xml-rpc server listens on a local TCP
     * port (according to attributes xmlRpcServerHost and xmlRpcServerPort)</p>
     */
    public void startExternalInterfaces(QServerMethods methods) throws Exception {
        /**
         * // get a server factory if none already existing
         * if (xmlRpcServerFactory == null) {
         * getTunnelLength();
         * log.info("Creating an xml-rpc server factory with tunnel length "+tunLength);
         * xmlRpcServerFactory = new I2PXmlRpcServerFactory(
         * tunLength, tunLength, tunLength, tunLength, i2p);
         * }
         *
         * log.info("Creating XML-RPC server listening within i2p");
         * xmlRpcServer = xmlRpcServerFactory.newServer(privKey);
         *
         * // bind in our interface class
         * log.info("Binding XML-RPC interface object");
         * xmlRpcServer.addHandler(baseXmlRpcServiceName, methods);
         *
         * // and fire it up
         * log.info("Launching XML-RPC server");
         * xmlRpcServer.start();
         **/

        Properties httpProps = new Properties();

        httpProps = new Properties();
        Properties sysProps = System.getProperties();
        String i2cpHost = sysProps.getProperty("i2cp.tcp.host", "127.0.0.1");
        String i2cpPort = sysProps.getProperty("i2cp.tcp.port", "7654");
        httpProps.setProperty("i2cp.tcp.host", i2cpHost);
        httpProps.setProperty("i2cp.tcp.port", i2cpPort);

        // create in-i2p http server for xmlrpc and browser access
        MiniHttpServer webServer = new I2PHttpServer(privKey, QClientWebInterface.class, this, httpProps);
        webServer.addXmlRpcHandler(baseXmlRpcServiceName, methods);
        webServer.start();
        System.out.println("Started in-i2p http/xmlrpc server listening on dest:");
        String dest = privKey.getDestination().toBase64();
        System.out.println(dest);
        
    }
    
    public void getTunnelLength()
    {
        String tunLenStr = System.getProperty("quartermaster.tunnelLength");
        if (tunLenStr == null)
        {
            return;
        }

        tunLength = new Integer(tunLenStr).intValue();
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
    
        QServerNode node;

        try {
            if (args.length > 0) {
                node = new QServerNode(args[0]);
            }
            else {
                node = new QServerNode();
            }
            node.log.info("QServerNode: entering endless loop...");
            while (true) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}

