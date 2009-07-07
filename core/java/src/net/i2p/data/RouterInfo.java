package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.SHA256Generator;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;

/**
 * Defines the data that a router either publishes to the global routing table or
 * provides to trusted peers.  
 *
 * @author jrandom
 */
public class RouterInfo extends DataStructureImpl {
    private final static Log _log = new Log(RouterInfo.class);
    private RouterIdentity _identity;
    private volatile long _published;
    private Set _addresses;
    private Set _peers;
    private Properties _options;
    private volatile Signature _signature;
    private volatile Hash _currentRoutingKey;
    private volatile byte _routingKeyGenMod[];
    private volatile boolean _validated;
    private volatile boolean _isValid;
    private volatile String _stringified;
    private volatile byte _byteified[];
    private volatile int _hashCode;
    private volatile boolean _hashCodeInitialized;

    public static final String PROP_NETWORK_ID = "netId";
    public static final String PROP_CAPABILITIES = "caps";
    public static final char CAPABILITY_HIDDEN = 'H';

    // Public string of chars which serve as bandwidth capacity markers
    // NOTE: individual chars defined in Router.java
    public static final String BW_CAPABILITY_CHARS = "KLMNO";
    
    public RouterInfo() {
        setIdentity(null);
        setPublished(0);
        _addresses = new HashSet();
        _peers = new HashSet();
        _options = new OrderedProperties();
        setSignature(null);
        _validated = false;
        _isValid = false;
        _currentRoutingKey = null;
        _stringified = null;
        _byteified = null;
        _hashCodeInitialized = false;
    }

    public RouterInfo(RouterInfo old) {
        this();
        setIdentity(old.getIdentity());
        setPublished(old.getPublished());
        setAddresses(old.getAddresses());
        setPeers(old.getPeers());
        setOptions(old.getOptions());
        setSignature(old.getSignature());
    }

    private void resetCache() {
        _stringified = null;
        _byteified = null;
        _hashCodeInitialized = false;
    }

    /**
     * Retrieve the identity of the router represented
     *
     */
    public RouterIdentity getIdentity() {
        return _identity;
    }

    /**
     * Configure the identity of the router represented
     * 
     */
    public void setIdentity(RouterIdentity ident) {
        _identity = ident;
        resetCache();
    }

    /**
     * Retrieve the approximate date on which the info was published 
     * (essentially a version number for the routerInfo structure, except that
     * it also contains freshness information - whether or not the router is
     * currently publishing its information).  This should be used to help expire
     * old routerInfo structures
     *
     */
    public long getPublished() {
        return _published;
    }

    /**
     * Date on which it was published, in milliseconds since Midnight GMT on Jan 01, 1970
     *
     */
    public void setPublished(long published) {
        _published = published;
        resetCache();
    }

    /**
     * Retrieve the set of RouterAddress structures at which this
     * router can be contacted.
     *
     */
    public Set getAddresses() {
        synchronized (_addresses) {
            return new HashSet(_addresses);
        }
    }

    /**
     * Specify a set of RouterAddress structures at which this router
     * can be contacted.
     *
     */
    public void setAddresses(Set addresses) {
        synchronized (_addresses) {
            _addresses.clear();
            if (addresses != null) _addresses.addAll(addresses);
        }
        resetCache();
    }

    /**
     * Retrieve a set of SHA-256 hashes of RouterIdentities from rotuers
     * this router can be reached through.
     *
     */
    public Set getPeers() {
        return _peers;
    }

    /**
     * Specify a set of SHA-256 hashes of RouterIdentities from rotuers
     * this router can be reached through.
     *
     */
    public void setPeers(Set peers) {
        synchronized (_peers) {
            _peers.clear();
            if (peers != null) _peers.addAll(peers);
        }
        resetCache();
    }

    /**
     * Retrieve a set of options or statistics that the router can expose
     *
     */
    public Properties getOptions() {
        if (_options == null) return new Properties();
        synchronized (_options) {
            return (Properties) _options.clone();
        }
    }
    public String getOption(String opt) {
        if (_options == null) return null;
        synchronized (_options) {
            return _options.getProperty(opt);
        }
    }

    /**
     * Configure a set of options or statistics that the router can expose
     *
     */
    public void setOptions(Properties options) {
        synchronized (_options) {
            _options.clear();
            if (options != null) {
                for (Iterator iter = options.keySet().iterator(); iter.hasNext();) {
                    String name = (String) iter.next();
                    if (name == null) continue;
                    String val = options.getProperty(name);
                    if (val == null) continue;
                    _options.setProperty(name, val);
                }
            }
        }
        resetCache();
    }

    /**
     * Retrieve the proof that the identity stands behind the info here
     *
     */
    public Signature getSignature() {
        return _signature;
    }

    /**
     * Configure the proof that the entity stands behind the info here
     *
     */
    public void setSignature(Signature signature) {
        _signature = signature;
        resetCache();
    }

    /**
     * Sign the structure using the supplied signing key
     *
     */
    public synchronized void sign(SigningPrivateKey key) throws DataFormatException {
        byte[] bytes = getBytes();
        if (bytes == null) throw new DataFormatException("Not enough data to sign");
        // now sign with the key 
        Signature sig = DSAEngine.getInstance().sign(bytes, key);
        if (sig == null) throw new DataFormatException("Not enough data to sign, or other signature failure");
        setSignature(sig);
        //_log.debug("Signed " + SHA256Generator.getInstance().calculateHash(bytes).toBase64() + " with " + key);
        //_log.debug("verify ok? " + DSAEngine.getInstance().verifySignature(sig, bytes, getIdentity().getSigningPublicKey()));
        //_log.debug("Signed data: \n" + Base64.encode(bytes));
        //_log.debug("Signature: " + getSignature());
    }

    /** 
     * Write out the raw payload of the routerInfo, excluding the signature.  This
     * caches the data in memory if possible.
     *
     * @throws DataFormatException if the data is somehow b0rked (missing props, etc)
     */
    private byte[] getBytes() throws DataFormatException {
        if (_byteified != null) return _byteified;
        if (_identity == null) throw new DataFormatException("Router identity isn't set? wtf!");
        if (_addresses == null) throw new DataFormatException("Router addressess isn't set? wtf!");
        if (_peers == null) throw new DataFormatException("Router peers isn't set? wtf!");
        if (_options == null) throw new DataFormatException("Router options isn't set? wtf!");

        long before = Clock.getInstance().now();
        ByteArrayOutputStream out = new ByteArrayOutputStream(6*1024);
        try {
            _identity.writeBytes(out);
            DataHelper.writeDate(out, new Date(_published));
            if (isHidden()) {
                // Do not send IP address to peers in hidden mode
                DataHelper.writeLong(out, 1, 0);
            } else {
                DataHelper.writeLong(out, 1, _addresses.size());
                List addresses = DataHelper.sortStructures(_addresses);
                for (Iterator iter = addresses.iterator(); iter.hasNext();) {
                    RouterAddress addr = (RouterAddress) iter.next();
                    addr.writeBytes(out);
                }
            }
            // XXX: what about peers?
            // answer: they're always empty... they're a placeholder for one particular
            //         method of trusted links, which isn't implemented in the router
            //         at the moment, and may not be later.
            DataHelper.writeLong(out, 1, _peers.size());
            List peers = DataHelper.sortStructures(_peers);
            for (Iterator iter = peers.iterator(); iter.hasNext();) {
                Hash peerHash = (Hash) iter.next();
                peerHash.writeBytes(out);
            }
            DataHelper.writeProperties(out, _options);
        } catch (IOException ioe) {
            throw new DataFormatException("IO Error getting bytes", ioe);
        }
        byte data[] = out.toByteArray();
        long after = Clock.getInstance().now();
        _log.debug("getBytes()  took " + (after - before) + "ms");
        _byteified = data;
        return data;
    }

    /**
     * Determine whether this router info is authorized with a valid signature
     *
     */
    public synchronized boolean isValid() {
        if (!_validated) doValidate();
        return _isValid;
    }

    /**
     * which network is this routerInfo a part of.  configured through the property
     * PROP_NETWORK_ID
     */
    public int getNetworkId() {
        if (_options == null) return -1;
        String id = null;
        synchronized (_options) {
            id = _options.getProperty(PROP_NETWORK_ID);
        }
        if (id != null) {
            try {
                return Integer.parseInt(id);
            } catch (NumberFormatException nfe) {}
        }
        return -1;
    }

    /**
     * what special capabilities this router offers
     *
     */
    public String getCapabilities() {
        if (_options == null) return "";
        String capabilities = null;
        synchronized (_options) {
            capabilities = _options.getProperty(PROP_CAPABILITIES);
        }
        if (capabilities != null)
            return capabilities;
        else
            return "";
    }

    /**
     * Is this a hidden node?
     */
    public boolean isHidden() {
        return (getCapabilities().indexOf(CAPABILITY_HIDDEN) != -1);
    }

    /**
     * Return a string representation of this node's bandwidth tier,
     * or "Unknown"
     */
    public String getBandwidthTier() {
        String bwTiers = BW_CAPABILITY_CHARS;
        String bwTier = "Unknown";
        String capabilities = getCapabilities();
        // Iterate through capabilities, searching for known bandwidth tier
        for (int i = 0; i < capabilities.length(); i++) {
            if (bwTiers.indexOf(String.valueOf(capabilities.charAt(i))) != -1) {
                bwTier = String.valueOf(capabilities.charAt(i));
                break;
            }
        }
        return (bwTier);
    }

    public void addCapability(char cap) {
        if (_options == null) _options = new OrderedProperties();
        synchronized (_options) {
            String caps = _options.getProperty(PROP_CAPABILITIES);
            if (caps == null)
                _options.setProperty(PROP_CAPABILITIES, ""+cap);
            else if (caps.indexOf(cap) == -1)
                _options.setProperty(PROP_CAPABILITIES, caps + cap);
        }
    }

    public void delCapability(char cap) {
        if (_options == null) return;
        synchronized (_options) {
            String caps = _options.getProperty(PROP_CAPABILITIES);
            int idx;
            if (caps == null) {
                return;
            } else if ((idx = caps.indexOf(cap)) == -1) {
                return;
	    } else {
                StringBuilder buf = new StringBuilder(caps);
		while ( (idx = buf.indexOf(""+cap)) != -1)
                    buf.deleteCharAt(idx);
                _options.setProperty(PROP_CAPABILITIES, buf.toString());
            }
        }
    }

        
    /**
     * Get the routing key for the structure using the current modifier in the RoutingKeyGenerator.
     * This only calculates a new one when necessary though (if the generator's key modifier changes)
     *
     */
    public synchronized Hash getRoutingKey() {
        RoutingKeyGenerator gen = RoutingKeyGenerator.getInstance();
        if ((gen.getModData() == null) || (_routingKeyGenMod == null)
            || (!DataHelper.eq(gen.getModData(), _routingKeyGenMod))) {
            setRoutingKey(gen.getRoutingKey(getIdentity().getHash()));
            _routingKeyGenMod = gen.getModData();
        }
        return _currentRoutingKey;
    }

    public void setRoutingKey(Hash key) {
        _currentRoutingKey = key;
    }

    public boolean validateRoutingKey() {
        Hash identKey = getIdentity().getHash();
        Hash rk = RoutingKeyGenerator.getInstance().getRoutingKey(identKey);
        if (rk.equals(getRoutingKey()))
            return true;

        return false;
    }

    /**
     * Determine whether the router was published recently (within the given age milliseconds).
     * The age should be large enough to take into consideration any clock fudge factor, so
     * values such as 1 or 2 hours are probably reasonable.
     *
     * @param maxAgeMs milliseconds between the current time and publish date to check
     * @return true if it was published recently, false otherwise
     */
    public boolean isCurrent(long maxAgeMs) {
        long earliestExpire = Clock.getInstance().now() - maxAgeMs;
        if (getPublished() < earliestExpire)
            return false;

        return true;
    }
    
    
    /**
     * Pull the first workable target address for the given transport
     *
     */
    public RouterAddress getTargetAddress(String transportStyle) {
        synchronized (_addresses) {
            for (Iterator iter = _addresses.iterator(); iter.hasNext(); ) {
                RouterAddress addr = (RouterAddress)iter.next();
                if (addr.getTransportStyle().equals(transportStyle)) 
                    return addr;
            }
        }
        return null;
    }

    /**
     * Actually validate the signature
     */
    private synchronized void doValidate() {
        _validated = true;
        if (getSignature() == null) {
            _log.error("Signature is null");
            _isValid = false;
            return;
        }
        byte data[] = null;
        try {
            data = getBytes();
        } catch (DataFormatException dfe) {
            _log.error("Error validating", dfe);
            _isValid = false;
            return;
        }
        if (data == null) {
            _log.error("Data could not be loaded");
            _isValid = false;
            return;
        }
        _isValid = DSAEngine.getInstance().verifySignature(_signature, data, _identity.getSigningPublicKey());
        if (!_isValid) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Invalid [" + SHA256Generator.getInstance().calculateHash(data).toBase64()
                           + "] w/ signing key: " + _identity.getSigningPublicKey(), 
                           new Exception("Signature failed"));
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Failed data: \n" + Base64.encode(data));
                _log.debug("Signature: " + getSignature());
            }
        }
    }
    
    public synchronized void readBytes(InputStream in) throws DataFormatException, IOException {
        _identity = new RouterIdentity();
        _identity.readBytes(in);
        Date when = DataHelper.readDate(in);
        if (when == null)
            _published = 0;
        else
            _published = when.getTime();
        int numAddresses = (int) DataHelper.readLong(in, 1);
        for (int i = 0; i < numAddresses; i++) {
            RouterAddress address = new RouterAddress();
            address.readBytes(in);
            _addresses.add(address);
        }
        int numPeers = (int) DataHelper.readLong(in, 1);
        for (int i = 0; i < numPeers; i++) {
            Hash peerIdentityHash = new Hash();
            peerIdentityHash.readBytes(in);
            _peers.add(peerIdentityHash);
        }
        _options = DataHelper.readProperties(in);
        _signature = new Signature();
        _signature.readBytes(in);

        resetCache();

        //_log.debug("Read routerInfo: " + toString());
    }
    
    public synchronized void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_identity == null) throw new DataFormatException("Missing identity");
        if (_published < 0) throw new DataFormatException("Invalid published date: " + _published);
        if (_signature == null) throw new DataFormatException("Signature is null");
        //if (!isValid())
        //    throw new DataFormatException("Data is not valid");
        ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
        baos.write(getBytes());
        _signature.writeBytes(baos);

        byte data[] = baos.toByteArray();
        //_log.debug("Writing routerInfo [len=" + data.length + "]: " + toString());
        out.write(data);
    }
    
    @Override
    public boolean equals(Object object) {
        if ((object == null) || !(object instanceof RouterInfo)) return false;
        RouterInfo info = (RouterInfo) object;
        return DataHelper.eq(_identity, info.getIdentity())
               && DataHelper.eq(_signature, info.getSignature())
               && DataHelper.eq(getPublished(), info.getPublished())
               && DataHelper.eq(_addresses, info.getAddresses())
               && DataHelper.eq(_options, info.getOptions()) 
               && DataHelper.eq(_peers, info.getPeers());
    }
    
    @Override
    public int hashCode() {
        if (!_hashCodeInitialized) {
            _hashCode = DataHelper.hashCode(_identity) + (int) getPublished();
            _hashCodeInitialized = true;
        }
        return _hashCode;
    }
    
    @Override
    public String toString() {
        if (_stringified != null) return _stringified;
        StringBuilder buf = new StringBuilder(5*1024);
        buf.append("[RouterInfo: ");
        buf.append("\n\tIdentity: ").append(getIdentity());
        buf.append("\n\tSignature: ").append(getSignature());
        buf.append("\n\tPublished on: ").append(new Date(getPublished()));
        Set addresses = _addresses; // getAddresses()
        buf.append("\n\tAddresses: #: ").append(addresses.size());
        for (Iterator iter = addresses.iterator(); iter.hasNext();) {
            RouterAddress addr = (RouterAddress) iter.next();
            buf.append("\n\t\tAddress: ").append(addr);
        }
        Set peers = _peers; // getPeers()
        buf.append("\n\tPeers: #: ").append(peers.size());
        for (Iterator iter = peers.iterator(); iter.hasNext();) {
            Hash hash = (Hash) iter.next();
            buf.append("\n\t\tPeer hash: ").append(hash);
        }
        Properties options = _options; // getOptions();
        buf.append("\n\tOptions: #: ").append(options.size());
        for (Iterator iter = options.keySet().iterator(); iter.hasNext();) {
            String key = (String) iter.next();
            String val = options.getProperty(key);
            buf.append("\n\t\t[").append(key).append("] = [").append(val).append("]");
        }
        buf.append("]");
        _stringified = buf.toString();
        return _stringified;
    }
}
