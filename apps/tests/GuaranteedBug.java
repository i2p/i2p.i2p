// compile & run this file against i2p.jar

import java.io.*;
import java.util.*;
import net.i2p.*;
import net.i2p.client.*;
import net.i2p.data.*;

public class GuaranteedBug {


    public void reproduce() {
        try {
            Destination d1 = null;
            // first client (receiver)
            if (true) { // smaller scope for variables ...
                I2PClient client = I2PClientFactory.createClient();
                ByteArrayOutputStream keyStream =
                    new ByteArrayOutputStream(512);
                d1 = client.createDestination(keyStream);
                ByteArrayInputStream in =
                    new ByteArrayInputStream(keyStream.toByteArray());
                Properties opts = new Properties();
                opts.setProperty(I2PClient.PROP_RELIABILITY,
                                 I2PClient.PROP_RELIABILITY_GUARANTEED);
                opts.setProperty(I2PClient.PROP_TCP_HOST, "127.0.0.1");
                opts.setProperty(I2PClient.PROP_TCP_PORT, "7654");
                I2PSession session = client.createSession(in, opts);
                session.connect();
                session.setSessionListener(new PacketCounter());
            }
            // second client (sender)
            I2PClient client = I2PClientFactory.createClient();
            ByteArrayOutputStream keyStream = new ByteArrayOutputStream(512);
            Destination d2 = client.createDestination(keyStream);
            ByteArrayInputStream in =
                new ByteArrayInputStream(keyStream.toByteArray());
            Properties opts = new Properties();
            opts.setProperty(I2PClient.PROP_RELIABILITY,
                             I2PClient.PROP_RELIABILITY_GUARANTEED);
            opts.setProperty(I2PClient.PROP_TCP_HOST, "127.0.0.1");
            opts.setProperty(I2PClient.PROP_TCP_PORT, "7654");
            I2PSession session = client.createSession(in, opts);
            session.connect();
            session.setSessionListener(new DummyListener());
            for (int i=0;i<1000; i++) {
                byte[] msg = (""+i).getBytes("ISO-8859-1");
                session.sendMessage(d1,msg);
                System.out.println(">>"+i);
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        } catch (I2PException ex) {
            ex.printStackTrace();
        }
    }

    public static void main(String[] args) {
        new GuaranteedBug().reproduce();
    }
    
    // -------------------------------------------------------
    public class DummyListener implements I2PSessionListener {
        public void disconnected(I2PSession session) {
            System.err.println("Disconnected: "+session);
        }
    
        public void errorOccurred(I2PSession session, String message,
                                  Throwable error) {
            System.err.println("Error: "+session+"/"+message);
            error.printStackTrace();
        }
    
        public void messageAvailable(I2PSession session, int msgId,
                                     long size) {
            System.err.println("Message here? "+session);
        }
    
        public void reportAbuse(I2PSession session, int severity) {
            System.err.println("Abuse: "+severity+"/"+session);
        }
    }

    public class PacketCounter extends DummyListener {
        private int lastPacket = -1;
        public void messageAvailable(I2PSession session, int msgId,
                                     long size) {
            try {
                byte msg[] = session.receiveMessage(msgId);
                String m = new String(msg, "ISO-8859-1");
                int no = Integer.parseInt(m);
                if (no != ++lastPacket) {
                    System.out.println("ERROR: <<"+no);
                } else {
                    System.out.println("<<"+no);
                }
            } catch (NumberFormatException ex) {
                ex.printStackTrace();
            } catch (I2PException ex) {
                ex.printStackTrace();
            } catch (IOException ex) {
                ex.printStackTrace();
            }    
        }
    }
}
