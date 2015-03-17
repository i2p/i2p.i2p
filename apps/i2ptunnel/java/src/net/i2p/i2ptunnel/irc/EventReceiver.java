package net.i2p.i2ptunnel.irc;

/*
 * free (adj.): unencumbered; not under the control of others Written
 * by human & jrandom in 2004 and released into the public domain with
 * no warranty of any kind, either expressed or implied.  It probably
 * won't make your computer catch on fire, or eat your children, but
 * it might.  Use at your own risk.
 *
 */

import java.util.Set;

import net.i2p.util.EventDispatcher;

/**
 * An implementation of the EventDispatcher interface for
 * receiving events via in-line notifyEvent() only.
 * Does not support chaining to additional dispatchers.
 * Does not support waitEventValue().
 * Does not support ignoring.
 *
 * @since 0.8.9
 */
public abstract class EventReceiver implements EventDispatcher {

    public EventDispatcher getEventDispatcher() {
        return this;
    }
    
    /**
     *  @throws UnsupportedOperationException always
     */
    public void attachEventDispatcher(EventDispatcher ev) {
        throw new UnsupportedOperationException();
    }
    
    /**
     *  @throws UnsupportedOperationException always
     */
    public void detachEventDispatcher(EventDispatcher ev) {
        throw new UnsupportedOperationException();
    }
    
    public abstract void notifyEvent(String eventName, Object args);
    
    /**
     *  @throws UnsupportedOperationException always
     */
    public Object getEventValue(String name) {
        throw new UnsupportedOperationException();
    }

    /**
     *  @throws UnsupportedOperationException always
     */
    public Set<String> getEvents() {
        throw new UnsupportedOperationException();
    }
    
    /**
     *  @throws UnsupportedOperationException always
     */
    public void ignoreEvents() {
        throw new UnsupportedOperationException();
    }
    
    public void unIgnoreEvents() {}
    
    /**
     *  @throws UnsupportedOperationException always
     */
    public Object waitEventValue(String name) {
        throw new UnsupportedOperationException();
    }
}
