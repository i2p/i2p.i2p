/*
 * Test for an echo server. This test is intended to be used via an
 * I2PTunnel, but should work as well on other networks that provide
 * TCP tunneling and an echo server.
 *
 * Copyright (c) 2004 Michael Schierl
 *
 * Licensed unter GNU General Public License.
 */

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketException;

/**
 * The main engine for the EchoTester.
 */
public class EchoTester extends Thread {

    /**
     * How long to wait between packets. Default is 6 seconds.
     */
    private static long PACKET_DELAY= 6000;

    /**
     * How many packets may be on the way before the connection is
     * seen as "broken" and disconnected.
     */
    private static final long MAX_PACKETS_QUEUED=50; // unused
    
    
    private EchoTestAnalyzer eta;
    private String host;
    private int port;

    // the following vars are synchronized via the lock.
    private Object lock = new Object();
    private long nextPacket=0;
    private long nextUnreceived=0;
    private boolean readerRunning=false;

    public static void main(String[] args) {
	if (args.length == 3)
	    PACKET_DELAY = Long.parseLong(args[2]);
	new EchoTester(args[0], Integer.parseInt(args[1]),
		       new BasicEchoTestAnalyzer());
    }
    
    public EchoTester(String host, int port, EchoTestAnalyzer eta) {
	this.eta=eta;
	this.host=host;
	this.port=port;
	start();
    }

    public void run() {
	try {
	    while (true) {
		Socket s;
		try {
		    s = new Socket(host, port);
		} catch (ConnectException ex) {
		    eta.disconnected(true);
		    Thread.sleep(PACKET_DELAY);
		    continue;
		}
		System.out.println("41: Connected to "+host+":"+port);
		synchronized(lock) {
		    nextUnreceived=nextPacket;
		}
		Thread t = new ResponseReaderThread(s);
		Writer w = new BufferedWriter(new OutputStreamWriter
					      (s.getOutputStream()));
		while (true) {
		    long no;
		    synchronized(lock) {
			no = nextPacket++;
		    }
		    try {
			w.write(no+" "+System.currentTimeMillis()+"\n");
			w.flush();
		    } catch (SocketException ex) {
			break;
		    }
		    Thread.sleep(PACKET_DELAY);
		}
		s.close();
		t.join();
		synchronized(lock) {
		    if (readerRunning) {
			System.out.println("*** WHY IS THIS THREAD STILL"+
					   " RUNNING?");
		    }
		    while (nextUnreceived < nextPacket) {
			nextUnreceived++;
			eta.packetLossOccurred(true);
		    }
		    if (nextUnreceived > nextPacket) {
			System.out.println("*** WTF? "+nextUnreceived+" > "+
					   nextPacket);
		    }
		}
		eta.disconnected(false);
	    }
	} catch (InterruptedException ex) {
	    ex.printStackTrace(); 
	    System.exit(1); // treat these errors as fatal	    
	} catch (IOException ex) {
	    ex.printStackTrace(); 
	    System.exit(1); // treat these errors as fatal
	}

    }

    private class ResponseReaderThread extends Thread {

	private Socket s;

	public ResponseReaderThread(Socket s) {
	    this.s=s;
	    synchronized(lock) {
		readerRunning=true;
	    }
	    start();
	}

	public void run() {
	    try {
		BufferedReader br = new BufferedReader(new InputStreamReader
						       (s.getInputStream()));
		String line;
		int index;
		while ((line=br.readLine()) != null) {
		    if ((index=line.indexOf(" ")) == -1)
			continue;
		    long now, packetNumber, packetTime;
		    now = System.currentTimeMillis();
		    try {
			packetNumber = Long.parseLong
			    (line.substring(0,index));
			packetTime = Long.parseLong
			    (line.substring(index+1));
		    } catch (NumberFormatException ex) {
			System.out.println(ex.toString());
			continue;
		    }
		    synchronized (lock) {
			while (packetNumber > nextUnreceived) {
			    nextUnreceived++;
			    eta.packetLossOccurred(false);
			}
			if (nextUnreceived > packetNumber) {
			    System.out.println("*** DOUBLE PACKET!");
			} else {
			    nextUnreceived++;
			}
		    }
		    eta.successOccurred(now-packetTime);
		}
	    } catch (SocketException ex) {
		// ignore
	    } catch (IOException ex) {
		ex.printStackTrace();
		System.exit(0);
	    }
	    synchronized(lock) {
		readerRunning=false;
	    }
	}
    }
}
