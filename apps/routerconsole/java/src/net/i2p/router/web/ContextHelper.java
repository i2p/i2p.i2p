package net.i2p.router.web;

import java.util.List;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;

public class ContextHelper {

    /** @throws IllegalStateException if no context available */
    public static RouterContext getContext(String contextId) {
        List<RouterContext> contexts = RouterContext.listContexts();
        if ( (contexts == null) || (contexts.isEmpty()) ) 
            throw new IllegalStateException("No contexts. This is usually because the router is either starting up or shutting down.");
        if ( (contextId == null) || (contextId.trim().length() <= 0) )
            return contexts.get(0);
        for (int i = 0; i < contexts.size(); i++) {
            RouterContext context = contexts.get(i);
            Hash hash = context.routerHash();
            if (hash == null) continue;
            if (hash.toBase64().startsWith(contextId))
                return context;
        }
        // not found, so just give them the first we can find
        return contexts.get(0);
    }
}
