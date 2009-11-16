package i2p.bote.service;

import net.i2p.util.I2PAppThread;

public class I2PBoteThread extends I2PAppThread {

    protected I2PBoteThread(String name) {
        super(name);
    }
    
    private volatile boolean shutdown;
    
    public void requestShutdown() {
        shutdown = true;
    }
    
    protected boolean shutdownRequested() {
        return shutdown;
    }
}