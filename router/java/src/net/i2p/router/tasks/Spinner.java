package net.i2p.router.tasks;

/**
 *  A non-daemon thread to let
 *  the shutdown task get all the way to the end
 *
 *  @since 0.8.12 moved from Router.java
 */
public class Spinner extends Thread {

    public Spinner() {
        super();
        setName("Shutdown Spinner");
        setDaemon(false);
    }

    @Override
    public void run() {
        try {
            sleep(5*60*1000);
        } catch (InterruptedException ie) {}
    }
}

