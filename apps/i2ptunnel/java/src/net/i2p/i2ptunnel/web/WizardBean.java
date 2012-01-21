package net.i2p.i2ptunnel.web;

/**
 * A temporary data holder for the wizard pages
 *
 * Warning - This class is not part of the i2ptunnel API, and at some point
 * it will be moved from the jar to the war.
 * Usage by classes outside of i2ptunnel.war is deprecated.
 */
public class WizardBean extends IndexBean {
    private boolean _isClient;
    public WizardBean() { super(); }

    /**
     * Whether the tunnel being set up is a client tunnel or not.
     *
     */
    public void setIsClient(String isClient) { 
        _isClient = Boolean.valueOf(isClient);   
    }
    public boolean getIsClient() {
        return _isClient;
    }
}
