/*
 * SimpleQueue.java
 *
 * Created on March 24, 2005, 11:14 PM
 */

package net.i2p.aum;

import java.util.Vector;

/**
 * Implements simething similar to python's 'Queue' class
 */
public class SimpleQueue {

    public Vector items;

    /** Creates a new instance of SimpleQueue */
    public SimpleQueue() {
        items = new Vector();
    }

    /**
     * fetches the item at head of queue, blocking if queue is empty
     */
    public synchronized Object get()
    {
        while (true)
        {
            try {
                if (items.size() == 0)
                wait();
        
                // someone has added
                Object item = items.get(0);
                items.remove(0);
                return item;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * adds a new object to the queue
     */
    public synchronized void put(Object item)
    {
        items.addElement(item);
        notify();
    }

    private static class TestThread extends Thread {
        
        String id;
        
        SimpleQueue q;
        
        public TestThread(String id, SimpleQueue q) {
            this.id = id;
            this.q = q;
        }
        
        public void run() {
            try {
                print("waiting for queue");

                Object item = q.get();
                
                print("got item: '"+item+"'");
                
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }
        }
        
        public void print(String msg) {
            System.out.println("thread '"+id+"': "+msg);
        }
        
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {

        int i;
        int nthreads = 7;

        Thread [] threads = new Thread[nthreads];

        SimpleQueue q = new SimpleQueue();

        // populate the queue with some stuff
        q.put("red");
        q.put("orange");
        q.put("yellow");
        
        // populate threads array
        for (i = 0; i < nthreads; i++) {
            threads[i] = new TestThread("thread"+i, q);
        }
        
        // and launch the threads
        for (i = 0; i < nthreads; i++) {
            threads[i].start();
        }

        try {
            Thread.sleep(3000);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        // wait a bit and see what happens
        String [] items = {"green", "blue", "indigo", "violet", "black", "white", "brown"};
        for (i = 0; i < items.length; i++) {
            String item = items[i];
            System.out.println("main: adding '"+item+"'...");
            q.put(item);
            try {
                Thread.sleep(3000);
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }
        }
            
        System.out.println("main: terminating");

    }
    
}
