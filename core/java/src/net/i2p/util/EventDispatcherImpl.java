package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others Written
 * by human & jrandom in 2004 and released into the public domain with
 * no warranty of any kind, either expressed or implied.  It probably
 * won't make your computer catch on fire, or eat your children, but
 * it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.Set;

/**
 * An implementation of the EventDispatcher interface.  Since Java
 * doesn't support multiple inheritance, you could follow the Log.java
 * style: this class should be instantiated and kept as a variable by
 * each object it is used by, ala:
 * <code>private final EventDispatcher _event = new EventDispatcher();</code>
 *
 * If there is anything in here that doesn't make sense, turn off
 * your computer and go fly a kite - (c) 2004 by jrandom

 * @author human
 * @author jrandom
 */
public class EventDispatcherImpl implements EventDispatcher {

    private final static Log _log = new Log(EventDispatcherImpl.class);

    private boolean _ignore = false;
    private HashMap _events = new HashMap(4);
    private ArrayList _attached = new ArrayList();

    public EventDispatcher getEventDispatcher() {
        return this;
    }

    public void attachEventDispatcher(EventDispatcher ev) {
        if (ev == null) return;
        synchronized (_attached) {
            _log.debug(this.hashCode() + ": attaching EventDispatcher " + ev.hashCode());
            _attached.add(ev);
        }
    }

    public void detachEventDispatcher(EventDispatcher ev) {
        if (ev == null) return;
        synchronized (_attached) {
            ListIterator it = _attached.listIterator();
            while (it.hasNext()) {
                if (((EventDispatcher) it.next()) == ev) {
                    it.remove();
                    break;
                }
            }
        }
    }

    public void notifyEvent(String eventName, Object args) {
        if (_ignore) return;
        if (args == null) {
            args = "[null value]";
        }
        _log.debug(this.hashCode() + ": got notification [" + eventName + "] = [" + args + "]");
        synchronized (_events) {
            _events.put(eventName, args);
            _events.notifyAll();
            synchronized (_attached) {
                Iterator it = _attached.iterator();
                EventDispatcher e;
                while (it.hasNext()) {
                    e = (EventDispatcher) it.next();
                    _log.debug(this.hashCode() + ": notifying attached EventDispatcher " + e.hashCode() + ": ["
                               + eventName + "] = [" + args + "]");
                    e.notifyEvent(eventName, args);
                }
            }
        }
    }

    public Object getEventValue(String name) {
        if (_ignore) return null;
        Object val;

        synchronized (_events) {
            val = _events.get(name);
        }

        return val;
    }

    public Set getEvents() {
        if (_ignore) return Collections.EMPTY_SET;
        Set set;

        synchronized (_events) {
            set = new HashSet(_events.keySet());
        }

        return set;
    }

    public void ignoreEvents() {
        _ignore = true;
        synchronized (_events) {
            _events.clear();
        }
        _events = null;
    }

    public void unIgnoreEvents() {
        _ignore = false;
    }

    public Object waitEventValue(String name) {
        if (_ignore) return null;
        Object val;

        _log.debug(this.hashCode() + ": waiting for [" + name + "]");
        do {
            synchronized (_events) {
                if (_events.containsKey(name)) {
                    val = _events.get(name);
                    break;
                }
                try {
                    _events.wait(1 * 1000);
                } catch (InterruptedException e) { // nop
                }
            }
        } while (true);

        return val;
    }
}