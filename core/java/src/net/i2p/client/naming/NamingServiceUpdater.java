package net.i2p.client.naming;

import java.util.Properties;

/**
 * @since 0.8.6
 */
public interface NamingServiceUpdater {

    /**
     *  Should not block.
     *  @param options Updater-specific, may be null
     */
    public void update(Properties options);
}

