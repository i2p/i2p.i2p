package net.i2p.i2ptunnel.access;

import java.util.Map;
import java.util.HashMap;

import java.io.IOException;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.client.streaming.IncomingConnectionFilter;

class AccessFilter implements IncomingConnectionFilter {

    private final FilterDefinition definition;
    private final I2PAppContext context;

    /**
     * Trackers for known destinations defined in access lists
     */
    private final Map<String, DestTracker> knownDests = new HashMap<String, DestTracker>();
    /**
     * Trackers for unknown destinations not defined in access lists
     */
    private final Map<String, DestTracker> unknownDests = new HashMap<String, DestTracker>();

    AccessFilter(I2PAppContext context, FilterDefinition definition) {
        this.context = context;
        this.definition = definition;
    }

    @Override
    public boolean allowDestination(Destination d) {
        String b32 = d.toBase32();
        long now = context.clock().now();
        DestTracker tracker = null;
        synchronized(knownDests) {
            tracker = knownDests.get(b32);
        }
        if (tracker == null) {
            synchronized(unknownDests) {
                tracker = unknownDests.get(b32);
                if (tracker == null) {
                    tracker = new DestTracker(b32, definition.getDefaultThreshold());
                    unknownDests.put(b32, tracker);
                }
            }
        }

        return !tracker.recordAccess(now);
    }

    private void reload() throws IOException {
        synchronized(knownDests) {
            for (FilterDefinitionElement element : definition.getElements())
                element.update(knownDests);
        }
        
    }
}
