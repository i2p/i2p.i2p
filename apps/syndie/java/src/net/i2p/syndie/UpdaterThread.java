package net.i2p.syndie;

/**
 * A thread that runs the updater.  
 * 
 * @author Ragnarok
 *
 */
public class UpdaterThread extends Thread {

    /**
     * Construct an UpdaterThread.
     */
    public UpdaterThread() {
    }

    /* (non-Javadoc)
     * @see java.lang.Runnable#run()
     */
    public void run() {
        //try {
        //    Thread.sleep(5 * 60 * 1000);
        //} catch (InterruptedException exp) {
        //}
        Updater.main();
    }
}