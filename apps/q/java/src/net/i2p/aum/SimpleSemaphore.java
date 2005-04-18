/*
 * SimpleSemaphore.java
 *
 * Created on March 24, 2005, 11:51 PM
 */

package net.i2p.aum;

/**
 * Simple implementation of semaphores
 */
public class SimpleSemaphore {

    protected int count;

    /** Creates a new instance of SimpleSemaphore */
    public SimpleSemaphore(int size) {
        count = size;
    }

    public synchronized void acquire() throws InterruptedException
    {
        if (count == 0)
        {
            wait();
        }
        count -= 1;
    }

    public synchronized void release()
    {
        count += 1;
        notify();
    }
    
    private static class TestThread extends Thread
    {
        String id;
        SimpleSemaphore sem;
        
        public TestThread(String id, SimpleSemaphore sem)
        {
            this.id = id;
            this.sem = sem;
        }
        
        public void run()
        {
            try {
                print("waiting for semaphore");
                sem.acquire();

                print("got semaphore");

                Thread.sleep(1000);
            
                print("releasing semaphore");
            
                sem.release();

                print("terminating");

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
        
        Thread [] threads = new Thread[10];

        SimpleSemaphore sem = new SimpleSemaphore(3);
        
        // populate threads array
        for (i = 0; i < 10; i++) {
            threads[i] = new TestThread("thread"+i, sem);
        }
        
        // and launch the threads
        for (i = 0; i < 10; i++) {
            threads[i].start();
        }

        // wait a bit and see what happens
        System.out.println("main: threads launched, waiting 20 secs");
        
        try {
            Thread.sleep(20000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("main: terminating");

    }
    
}
