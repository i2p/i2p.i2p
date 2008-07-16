package net.i2p.router.web;

import java.util.List;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;

class ContextHelper {
    public static RouterContext getContext(String contextId) {
        List contexts = RouterContext.listContexts();
        if ( (contexts == null) || (contexts.size() <= 0) ) 
            throw new IllegalStateException("No contexts?  wtf");
        if ( (contextId == null) || (contextId.trim().length() <= 0) )
            return (RouterContext)contexts.get(0);
        for (int i = 0; i < contexts.size(); i++) {
            RouterContext context = (RouterContext)contexts.get(i);
            Hash hash = context.routerHash();
            if (hash == null) continue;
            if (hash.toBase64().startsWith(contextId))
                return context;
        }
        // not found, so just give them the first we can find
        return (RouterContext)contexts.get(0);
    }
}