package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others Written
 * by human & jrandom in 2004 and released into the public domain with
 * no warranty of any kind, either expressed or implied.  It probably
 * won't make your computer catch on fire, or eat your children, but
 * it might.  Use at your own risk.
 *
 */

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An implementation of the EventDispatcher interface.  Since Java
 * doesn't support multiple inheritance, you could follow the Log.java
 * style: this class should be instantiated and kept as a variable by
 * each object it is used by, ala:
 * <code>private final EventDispatcher _event = new EventDispatcher();</code>
 *
 * Deprecated - Used only by I2PTunnel
 *
 * If there is anything in here that doesn't make sense, turn off
 * your computer and go fly a kite - (c) 2004 by jrandom

 * @author human
 * @author jrandom
 */
public class EventDispatcherImpl implements EventDispatcher {

    private boolean _ignore = false;
    private final Map<String, Object> _events = new ConcurrentHashMap(4);
    private final List<EventDispatcher> _attached = new CopyOnWriteArrayList();
    
    public EventDispatcher getEventDispatcher() {
        return this;
    }
    
    public void attachEventDispatcher(EventDispatcher ev) {
        if (ev == null) return;
        _attached.add(ev);
    }
    
    public void detachEventDispatcher(EventDispatcher ev) {
        if (ev == null) return;
        _attached.remove(ev);
    }
    
    public void notifyEvent(String eventName, Object args) {
        if (_ignore) return;
        if (args == null) {
            args = "[null value]";
        }
        _events.put(eventName, args);
        synchronized (_events) {
            _events.notifyAll();
        }
        for (EventDispatcher e : _attached) {
            e.notifyEvent(eventName, args);
        }
    }
    
    public Object getEventValue(String name) {
        if (_ignore) return null;
        return _events.get(name);
    }

    public Set<String> getEvents() {
        if (_ignore) return Collections.EMPTY_SET;
        return new HashSet(_events.keySet());
    }
    
    public void ignoreEvents() {
        _ignore = true;
        _events.clear();
    }
    
    public void unIgnoreEvents() {
        _ignore = false;
    }
    
    public Object waitEventValue(String name) {
        if (_ignore) return null;
        do {
            synchronized (_events) {
                Object val = _events.get(name);
                if (val != null)
                    return val;
                try {
                    _events.wait(5 * 1000);
                } catch (InterruptedException e) {}
            }
        } while (true);
    }
}
