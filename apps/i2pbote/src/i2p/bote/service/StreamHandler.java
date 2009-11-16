package i2p.bote.service;

import java.util.EventListener;

public interface StreamHandler extends EventListener {

    void streamReceived(StreamReceivedEvent event);
}