package net.i2p.util;

/*
 * free (adj.): unencumbered; not under the control of others Written
 * by human in 2004 and released into the public domain with no
 * warranty of any kind, either expressed or implied.  It probably
 * won't make your computer catch on fire, or eat your children, but
 * it might.  Use at your own risk.
 *
 */

import java.util.Set;

/**
 * Event dispatching interface.  It allows objects to receive and
 * notify data events (basically String->Object associations) and
 * create notification chains.  To ease the usage of this interface,
 * you could define an EventDispatcherImpl attribute called
 * <code>_event</code> (as suggested in EventDispatcherImpl documentation)
 * and cut'n'paste the following default implementation:
 *
 * <code>
 * public EventDispatcher getEventDispatcher() { return _event; }
 * public void attachEventDispatcher(IEventDispatcher e) { _event.attachEventDispatcher(e.getEventDispatcher()); }
 * public void detachEventDispatcher(IEventDispatcher e) { _event.detachEventDispatcher(e.getEventDispatcher()); }
 * public void notifyEvent(String e, Object a) { _event.notifyEvent(e,a); }
 * public Object getEventValue(String n) { return _event.getEventValue(n); }
 * public Set getEvents() { return _event.getEvents(); }
 * public void ignoreEvents() { _event.ignoreEvents(); }
 * public void unIgnoreEvents() { _event.unIgnoreEvents(); }
 * public Object waitEventValue(String n) { return _event.waitEventValue(n); }
 * </code>
 *
 * Deprecated - Used only by I2PTunnel
 *
 * @author human
 */
public interface EventDispatcher {

    /**
     * Get an object to be used to deliver events (usually
     * <code>this</code>, but YMMV).
     */
    public EventDispatcher getEventDispatcher();

    /**
     * Attach an EventDispatcher object to the events dispatching chain.  Note
     * that notification is not bidirectional (i.e. events notified to
     * <code>ev</code> won't reach the object calling this method).
     * Good luck, and beware of notification loops! :-)
     *
     * @param iev Event object to be attached
     */
    public void attachEventDispatcher(EventDispatcher iev);

    /**
     * Detach the specified EventDispatcher object from the events dispatching chain.
     *
     * @param iev Event object to be detached
     */
    public void detachEventDispatcher(EventDispatcher iev);

    /**
     * Deliver an event
     * 
     * @param event name of the event 
     * @param args data being stored for that event
     */
    public void notifyEvent(String event, Object args);

    /**
     * Retrieve the value currently associated with the specified
     * event value
     *
     * @param name name of the event to query for 
     * @return value (or null if none are available)
     */
    public Object getEventValue(String name);

    /**
     * Retrieve the names of all the events that have been received
     *
     * @return A set of event names
     */
    public Set getEvents();

    /**
     * Ignore further event notifications
     *
     */
    public void ignoreEvents();

    /**
     * Almost like the method above :-)
     *
     */
    public void unIgnoreEvents();

    /**
     * Wait until the given event has received a value
     *
     * @param name name of the event to wait for
     * @return value specified for that event
     */
    public Object waitEventValue(String name);
}
