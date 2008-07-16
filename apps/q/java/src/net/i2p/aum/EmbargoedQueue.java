/*
 * SimpleScheduler.java
 *
 * Created on March 24, 2005, 11:14 PM
 */

package net.i2p.aum;

import java.util.Date;
import java.util.Random;
import java.util.Vector;

/**
 * <p>Implements a queue of objects, where each object is 'embargoed'
 * against release until a given time. Threads which attempt to .get
 * items from this queue will block if the queue is empty, or if the
 * first item of the queue has a 'release time' which has not yet passed.</p>
 *
 * <p>Think of it like a news desk which receives media releases which are
 * 'embargoed' till a certain time. These releases sit in a queue, and when
 * their embargo expires, they are actioned and go to print or broadcast.
 * The reporters at this news desk are the 'threads', which get blocked
 * until the next item's embargo expires.</p>
 *
 * <p>Purpose of implementing this is to provide a mechanism for scheduling
 * background jobs to be executed at precise times</p>.
 */
public class EmbargoedQueue extends Thread {

    /**
     * items which are waiting for dispatch - stored as 2-element vectors,
     * where elem 0 is Integer dispatch time, and elem 1 is the object;
     * note that this list is kept in strict ascending order of time.
     * Whenever an object becomes ready, it is removed from this queue
     * and appended to readyItems
     */
    public Vector waitingItems;

    /**
     * items which are ready for dispatch (their time has come). 
     */
    public SimpleQueue readyItems;

    /** set this true to enable verbose debug messages */
    public boolean debug = false;

    /** Creates a new embargoed queue */
    public EmbargoedQueue() {
        waitingItems = new Vector();
        readyItems = new SimpleQueue();
        
        // fire up scheduler thread
        start();
    }

    /**
     * fetches the item at head of queue, blocking if queue is empty
     */
    public Object get()
    {
        return readyItems.get();
    }

    /**
     * adds a new object to queue without any embargo (or, an embargo that expires
     * immediately)
     * @param item the object to be added
     */
    public synchronized void putNow(Object item)
    {
        putAfter(0, item);
    }

    /**
     * adds a new object to queue, embargoed until given number of milliseconds
     * have elapsed
     * @param delay number of milliseconds from now when embargo expires
     * @param item the object to be added
     */
    public synchronized void putAfter(long delay, Object item)
    {
        long now = new Date().getTime();
        putAt(now+delay, item);
    }
    
    /**
     * adds a new object to the queue, embargoed until given time
     * @param time the unixtime in milliseconds when the object's embargo expires,
     * and the object is to be made available
     * @param item the object to be added
     */
    public synchronized void putAt(long time, Object item)
    {
        Vector elem = new Vector();
        elem.addElement(new Long(time));
        elem.addElement(item);

        long now = new Date().getTime();
        long future = time - now;
        //System.out.println("putAt: time="+time+" ("+future+"ms from now), job="+item);

        // find where to insert
        int i;
        int nitems = waitingItems.size();
        for (i = 0; i < nitems; i++)
        {
            // get item i
            Vector itemI = (Vector)waitingItems.get(i);
            long timeI = ((Long)(itemI.get(0))).longValue();
            if (time < timeI)
            {
                // new item earlier than item i, insert here and bust out
                waitingItems.insertElementAt(elem, i);
                break;
            }
        }
                
        // did we insert?
        if (i == nitems)
        {
           // no - gotta append
           waitingItems.addElement(elem);
        }
        
        // debugging
        if (debug) {
            printWaiting();
        }
                
        // awaken this scheduler object's thread, so it can
        // see if any jobs are ready
        //notify();
        interrupt();
    }

    /**
     * for debugging - prints out a list of waiting items
     */
    public synchronized void printWaiting()
    {
        int i;
        long now = new Date().getTime();

        System.out.println("EmbargoedQueue dump:");

        System.out.println("  Waiting items:");
        int nwaiting = waitingItems.size();
        for (i = 0; i < nwaiting; i++)
        {
            Vector item = (Vector)waitingItems.get(i);
            long when = ((Long)item.get(0)).longValue();
            Object job = item.get(1);
            int delay = (int)(when - now)/1000;
            System.out.println("    "+delay+"s, t="+when+", job="+job);
        }

        System.out.println("  Ready items:");
        int nready = readyItems.items.size();
        for (i = 0; i < nready; i++)
        {
            //Vector item = (Vector)readyItems.items.get(i);
            Object item = readyItems.items.get(i);
            System.out.println("    job="+item);
        }

    }
    
    /**
     * scheduling thread, which wakes up every time a new job is queued, and
     * if any jobs are ready, transfers them to the readyQueue and notifies
     * any waiting client threads
     */
    public void run()
    {
        // monitor the waiting queue, waiting till one becomes ready
        while (true)
        {
            try {
                if (waitingItems.size() > 0)
                {
                    // at least 1 waiting item
                    Vector item = (Vector)(waitingItems.get(0));
                    long now = new Date().getTime();
                    long then = ((Long)item.get(0)).longValue();
                    long delay = then - now;
                    
                    // ready?
                    if (delay <= 0)
                    {
                        // yep, ready, remove job and stick on waiting queue
                        waitingItems.remove(0);         // ditch from waiting
                        Object elem = item.get(1);
                        readyItems.put(elem);    // and add to ready

                        if (debug)
                        {
                            System.out.println("embargo expired on "+elem);
                            printWaiting();
                        }
                    }
                    else
                    {
                        // not ready, hang about till we get woken, or the
                        // job becomes ready
                        if (debug)
                        {
                            System.out.println("waiting for "+delay+"ms");
                        }
                        Thread.sleep(delay);
                    }
                }
                else
                {
                    // no items yet, hang out for an interrupt
                    if (debug)
                    {
                        System.out.println("queue is empty");
                    }
                    synchronized (this) {
                        wait();
                    }
                }
            } catch (Exception e) {
                //System.out.println("exception");
                if (debug)
                {
                    System.out.println("exception ("+e.getClass().getName()+") "+e.getMessage());
                }
            }
        }
    }
    
    private static class TestThread extends Thread {
        
        String id;
        
        EmbargoedQueue q;
        
        public TestThread(String id, EmbargoedQueue q) {
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

        EmbargoedQueue q = new EmbargoedQueue();
        SimpleSemaphore threadPool = new SimpleSemaphore(nthreads);

        // populate the queue with some stuff
        q.putAfter(10000, "red");
        q.putAfter(3000, "orange");
        q.putAfter(6000, "yellow");
        
        // populate threads array
        for (i = 0; i < nthreads; i++) {
            threads[i] = new TestThread("thread"+i, q);
        }
        
        // and launch the threads
        for (i = 0; i < nthreads; i++) {
            threads[i].start();
        }

        // wait, presumably till all these elements are actioned
        try {
            Thread.sleep(12000);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        // add some more shit to the queue, randomly scheduled
        Random r = new Random();
        String [] items = {"green", "blue", "indigo", "violet", "black", "white", "brown"};
        for (i = 0; i < items.length; i++) {
            String item = items[i];
            int delay = 2000 + r.nextInt(8000);
            System.out.println("main: adding '"+item+"' after "+delay+"ms ...");
            q.putAfter(delay, item);
        }

        // wait, presumably for all jobs to finish
        try {
            Thread.sleep(12000);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        System.out.println("main: terminating");

    }
    
}
