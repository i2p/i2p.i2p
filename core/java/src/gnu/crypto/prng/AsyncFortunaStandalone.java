package gnu.crypto.prng;

import java.util.*;

/**
 * fortuna instance that tries to avoid blocking if at all possible by using separate
 * filled buffer segments rather than one buffer (and blocking when that buffer's data
 * has been eaten)
 */
public class AsyncFortunaStandalone extends FortunaStandalone implements Runnable {
    private static final int BUFFERS = 16;
    private static final int BUFSIZE = 256*1024;
    private final byte asyncBuffers[][] = new byte[BUFFERS][BUFSIZE];
    private final int status[] = new int[BUFFERS];
    private int nextBuf = 0;

    private static final int STATUS_NEED_FILL = 0;
    private static final int STATUS_FILLING = 1;
    private static final int STATUS_FILLED = 2;
    private static final int STATUS_LIVE = 3;
    
    public AsyncFortunaStandalone() {
        super();
        for (int i = 0; i < BUFFERS; i++)
            status[i] = STATUS_NEED_FILL;
    }
    
    public void startup() {
        Thread refillThread = new Thread(this, "PRNG");
        refillThread.setDaemon(true);
        refillThread.setPriority(Thread.MIN_PRIORITY+1);
        refillThread.start();
    }

    /** the seed is only propogated once the prng is started with startup() */
    public void seed(byte val[]) {
        Map props = new HashMap(1);
        props.put(SEED, (Object)val);
        init(props);
        //fillBlock();
    }
  
    protected void allocBuffer() {}
    
    /**
     * make the next available filled buffer current, scheduling any unfilled
     * buffers for refill, and blocking until at least one buffer is ready
     */
    protected void rotateBuffer() {
        synchronized (asyncBuffers) {
            // wait until we get some filled
            long before = System.currentTimeMillis();
            long waited = 0;
            while (status[nextBuf] != STATUS_FILLED) {
                System.out.println(Thread.currentThread().getName() + ": Next PRNG buffer "
                                   + nextBuf + " isn't ready (" + status[nextBuf] + ")");
                //new Exception("source").printStackTrace();
                asyncBuffers.notifyAll();
                try {
                    asyncBuffers.wait();
                } catch (InterruptedException ie) {}
                waited = System.currentTimeMillis()-before;
            }
            if (waited > 0)
                System.out.println(Thread.currentThread().getName() + ": Took " + waited
                                   + "ms for a full PRNG buffer to be found");
            //System.out.println(Thread.currentThread().getName() + ": Switching to prng buffer " + nextBuf);
            buffer = asyncBuffers[nextBuf];
            status[nextBuf] = STATUS_LIVE;
            int prev=nextBuf-1;
            if (prev<0)
                prev = BUFFERS-1;
            if (status[prev] == STATUS_LIVE)
                status[prev] = STATUS_NEED_FILL;
            nextBuf++;
            if (nextBuf >= BUFFERS)
                nextBuf = 0;
            asyncBuffers.notify();
        }
    }
    
    public void run() {
        while (true) {
            int toFill = -1;
            try {
                synchronized (asyncBuffers) {
                    for (int i = 0; i < BUFFERS; i++) {
                        if (status[i] == STATUS_NEED_FILL) {
                            status[i] = STATUS_FILLING;
                            toFill = i;
                            break;
                        }
                    }
                    if (toFill == -1) {
                        //System.out.println(Thread.currentThread().getName() + ": All pending buffers full");
                        asyncBuffers.wait();
                    }
                }
            } catch (InterruptedException ie) {}
            
            if (toFill != -1) {
                //System.out.println(Thread.currentThread().getName() + ": Filling prng buffer " + toFill);
                long before = System.currentTimeMillis();
                doFill(asyncBuffers[toFill]);
                long after = System.currentTimeMillis();
                synchronized (asyncBuffers) {
                    status[toFill] = STATUS_FILLED;
                    //System.out.println(Thread.currentThread().getName() + ": Prng buffer " + toFill + " filled after " + (after-before));
                    asyncBuffers.notifyAll();
                }
                Thread.yield();
                long waitTime = (after-before)*5;
                if (waitTime <= 0) // somehow postman saw waitTime show up as negative
                    waitTime = 50;
                try { Thread.sleep(waitTime); } catch (InterruptedException ie) {}
            }
        }
    }

    public void fillBlock()
    {
        rotateBuffer();
    }
    
    private void doFill(byte buf[]) {
        long start = System.currentTimeMillis();
        if (pool0Count >= MIN_POOL_SIZE
            && System.currentTimeMillis() - lastReseed > 100)
          {
            reseedCount++;
            //byte[] seed = new byte[0];
            for (int i = 0; i < NUM_POOLS; i++)
              {
                if (reseedCount % (1 << i) == 0) {
                  generator.addRandomBytes(pools[i].digest());
                }
              }
            lastReseed = System.currentTimeMillis();
          }
        generator.nextBytes(buf);
        long now = System.currentTimeMillis();
        long diff = now-lastRefill;
        lastRefill = now;
        long refillTime = now-start;
        //System.out.println("Refilling " + (++refillCount) + " after " + diff + " for the PRNG took " + refillTime);
    }
    
    public static void main(String args[]) {
        try {
            AsyncFortunaStandalone rand = new AsyncFortunaStandalone();
            
            byte seed[] = new byte[1024];
            rand.seed(seed);
            System.out.println("Before starting prng");
            rand.startup();
            System.out.println("Starting prng, waiting 1 minute");
            try { Thread.sleep(60*1000); } catch (InterruptedException ie) {}
            System.out.println("PRNG started, beginning test");

            long before = System.currentTimeMillis();
            byte buf[] = new byte[1024];
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.util.zip.GZIPOutputStream gos = new java.util.zip.GZIPOutputStream(baos);
            for (int i = 0; i < 1024; i++) {
                rand.nextBytes(buf);
                gos.write(buf);
            }
            long after = System.currentTimeMillis();
            gos.finish();
            byte compressed[] = baos.toByteArray();
            System.out.println("Compressed size of 1MB: " + compressed.length + " took " + (after-before));
        } catch (Exception e) { e.printStackTrace(); }
        try { Thread.sleep(5*60*1000); } catch (InterruptedException ie) {}
    }
}
