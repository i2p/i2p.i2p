/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */
package org.klomp.snark;

/**
 * A structure for known trackers
 *
 * @since 0.9.1
 */
public class Tracker {

    public final String name;
    public final String announceURL;
    public final String baseURL;
    public final boolean supportsDetails;

    /**
     *  @param baseURL The web site, may be null
     */
    public Tracker(String name, String announceURL, String baseURL) {
        this.name = name;
        this.announceURL = announceURL;
        this.baseURL = baseURL;
        this.supportsDetails = name.contains("tracker2.postman.i2p");
    }
}
