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
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

import net.i2p.I2PAppContext;
import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.SHA1;
import net.i2p.crypto.SHA1Hash;
import net.i2p.crypto.SHA256Generator;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;

/**
 * Defines the data that a router either publishes to the global routing table or
 * provides to trusted peers.  
 *
 * For efficiency, the methods and structures here are now unsynchronized.
 * Initialize the RI with readBytes(), or call the setters and then sign() in a single thread.
 * Don't change it after that.
 *
 * To ensure integrity of the RouterInfo, methods that change an element of the
 * RouterInfo will throw an IllegalStateException after the RouterInfo is signed.
 *
 * @author jrandom
 */
public class RouterInfo extends DatabaseEntry {
    private RouterIdentity _identity;
    private volatile long _published;
    /**
     *  Addresses must be sorted by SHA256.
     *  When an RI is created, they are sorted in setAddresses().
     *  Save addresses in the order received so we need not resort.
     */
    private final List<RouterAddress> _addresses;
    /** may be null to save memory, no longer final */
    private Set<Hash> _peers;
    private final Properties _options;
    private volatile boolean _validated;
    private volatile boolean _isValid;
    private volatile String _stringified;
    private volatile byte _byteified[];
    private volatile int _hashCode;
    private volatile boolean _hashCodeInitialized;
    /** should we cache the byte and string versions _byteified ? **/
    private boolean _shouldCache;
    /** maybe we should check if we are floodfill? */
    private static final boolean CACHE_ALL = Runtime.getRuntime().maxMemory() > 128*1024*1024l &&
                                             Runtime.getRuntime().maxMemory() < Long.MAX_VALUE;

    public static final String PROP_NETWORK_ID = "netId";
    public static final String PROP_CAPABILITIES = "caps";
    public static final char CAPABILITY_HIDDEN = 'H';

    // Public string of chars which serve as bandwidth capacity markers
    // NOTE: individual chars defined in Router.java
    public static final String BW_CAPABILITY_CHARS = "KLMNO";
    
    public RouterInfo() {
        _addresses = new ArrayList(2);
        _options = new OrderedProperties();
    }

    /**
     *  Used only by Router and PublishLocalRouterInfoJob.
     *  Copies ONLY the identity and peers.
     *  Does not copy published, addresses, options, or signature.
     */
    public RouterInfo(RouterInfo old) {
        this();
        setIdentity(old.getIdentity());
        //setPublished(old.getPublished());
        //setAddresses(old.getAddresses());
        setPeers(old.getPeers());
        //setOptions(old.getOptions());
        //setSignature(old.getSignature());
        // copy over _byteified?
    }

    public long getDate() {
        return _published;
    }

    protected KeysAndCert getKeysAndCert() {
        return _identity;
    }

    public int getType() {
        return KEY_TYPE_ROUTERINFO;
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
     * @throws IllegalStateException if RouterInfo is already signed
     */
    public void setIdentity(RouterIdentity ident) {
        if (_signature != null)
            throw new IllegalStateException();
        _identity = ident;
        // We only want to cache the bytes for our own RI, which is frequently written.
        // To cache for all RIs doubles the RI memory usage.
        // setIdentity() is only called when we are creating our own RI.
        // Otherwise, the data is populated with readBytes().
        _shouldCache = true;
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
     * @throws IllegalStateException if RouterInfo is already signed
     */
    public void setPublished(long published) {
        if (_signature != null)
            throw new IllegalStateException();
        _published = published;
    }

    /**
     * Retrieve the set of RouterAddress structures at which this
     * router can be contacted.
     *
     * @return unmodifiable view, non-null
     */
    public Collection<RouterAddress> getAddresses() {
            return Collections.unmodifiableCollection(_addresses);
    }

    /**
     * Specify a set of RouterAddress structures at which this router
     * can be contacted.
     *
     * Warning - Sorts the addresses here. Do not modify any address
     *           after calling this, as the sort order is based on the
     *           hash of the entire address structure.
     *
     * @param addresses may be null
     * @throws IllegalStateException if RouterInfo is already signed or addresses previously set
     */
    public void setAddresses(Collection<RouterAddress> addresses) {
        if (_signature != null || !_addresses.isEmpty())
            throw new IllegalStateException();
        if (addresses != null) {
            _addresses.addAll(addresses);
            if (_addresses.size() > 1) {
                // WARNING this sort algorithm cannot be changed, as it must be consistent
                // network-wide. The signature is not checked at readin time, but only
                // later, and the addresses are stored in a Set, not a List.
                DataHelper.sortStructureList(_addresses);
            }
        }
    }

    /**
     * Retrieve a set of SHA-256 hashes of RouterIdentities from routers
     * this router can be reached through.
     *
     * @deprecated Implemented here but unused elsewhere
     */
    public Set<Hash> getPeers() {
        if (_peers == null)
            return Collections.EMPTY_SET;
        return _peers;
    }

    /**
     * Specify a set of SHA-256 hashes of RouterIdentities from routers
     * this router can be reached through.
     *
     * @deprecated Implemented here but unused elsewhere
     * @throws IllegalStateException if RouterInfo is already signed
     */
    public void setPeers(Set<Hash> peers) {
        if (_signature != null)
            throw new IllegalStateException();
        if (peers == null || peers.isEmpty()) {
            _peers = null;
            return;
        }
        if (_peers == null)
            _peers = new HashSet(2);
        synchronized (_peers) {
            _peers.clear();
            _peers.addAll(peers);
        }
    }

    /**
     * Retrieve a set of options or statistics that the router can expose.
     *
     * @deprecated use getOptionsMap()
     * @return sorted, non-null, NOT a copy, do not modify!!!
     */
    public Properties getOptions() {
        return _options;
    }

    /**
     * Retrieve a set of options or statistics that the router can expose.
     *
     * @return an unmodifiable view, non-null, sorted
     * @since 0.8.13
     */
    public Map getOptionsMap() {
        return Collections.unmodifiableMap(_options);
    }

    public String getOption(String opt) {
        return _options.getProperty(opt);
    }

    /**
     * Configure a set of options or statistics that the router can expose.
     * Makes a copy.
     *
     * @param options if null, clears current options
     * @throws IllegalStateException if RouterInfo is already signed
     */
    public void setOptions(Properties options) {
        if (_signature != null)
            throw new IllegalStateException();

        _options.clear();
        if (options != null)
            _options.putAll(options);
    }

    /** 
     * Write out the raw payload of the routerInfo, excluding the signature.  This
     * caches the data in memory if possible.
     *
     * @throws DataFormatException if the data is somehow b0rked (missing props, etc)
     */
    protected byte[] getBytes() throws DataFormatException {
        if (_byteified != null) return _byteified;
        if (_identity == null) throw new DataFormatException("Router identity isn't set? wtf!");

        //long before = Clock.getInstance().now();
        ByteArrayOutputStream out = new ByteArrayOutputStream(2*1024);
        try {
            _identity.writeBytes(out);
            // avoid thrashing objects
            //DataHelper.writeDate(out, new Date(_published));
            DataHelper.writeLong(out, 8, _published);
            int sz = _addresses.size();
            if (sz <= 0 || isHidden()) {
                // Do not send IP address to peers in hidden mode
                DataHelper.writeLong(out, 1, 0);
            } else {
                DataHelper.writeLong(out, 1, sz);
                for (RouterAddress addr : _addresses) {
                    addr.writeBytes(out);
                }
            }
            // XXX: what about peers?
            // answer: they're always empty... they're a placeholder for one particular
            //         method of trusted links, which isn't implemented in the router
            //         at the moment, and may not be later.
            int psz = _peers == null ? 0 : _peers.size();
            DataHelper.writeLong(out, 1, psz);
            if (psz > 0) {
                Collection<Hash> peers = _peers;
                if (psz > 1)
                    // WARNING this sort algorithm cannot be changed, as it must be consistent
                    // network-wide. The signature is not checked at readin time, but only
                    // later, and the hashes are stored in a Set, not a List.
                    peers = (Collection<Hash>) DataHelper.sortStructures(peers);
                for (Hash peerHash : peers) {
                    peerHash.writeBytes(out);
                }
            }
            DataHelper.writeProperties(out, _options);
        } catch (IOException ioe) {
            throw new DataFormatException("IO Error getting bytes", ioe);
        }
        byte data[] = out.toByteArray();
        //if (_log.shouldLog(Log.DEBUG)) {
        //    long after = Clock.getInstance().now();
        //    _log.debug("getBytes()  took " + (after - before) + "ms");
        //}
        if (CACHE_ALL || _shouldCache)
            _byteified = data;
        return data;
    }

    /**
     * Determine whether this router info is authorized with a valid signature
     *
     */
    public boolean isValid() {
        if (!_validated) doValidate();
        return _isValid;
    }

    /**
     * Same as isValid()
     * @since 0.9
     */
    @Override
    public boolean verifySignature() {
        return isValid();
    }

    /**
     * which network is this routerInfo a part of.  configured through the property
     * PROP_NETWORK_ID
     * @return -1 if unknown
     */
    public int getNetworkId() {
        String id = _options.getProperty(PROP_NETWORK_ID);
        if (id != null) {
            try {
                return Integer.parseInt(id);
            } catch (NumberFormatException nfe) {}
        }
        return -1;
    }

    /**
     * what special capabilities this router offers
     * @return non-null, empty string if none
     */
    public String getCapabilities() {
        String capabilities = _options.getProperty(PROP_CAPABILITIES);
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

    /**
     * @throws IllegalStateException if RouterInfo is already signed
     */
    public void addCapability(char cap) {
        if (_signature != null)
            throw new IllegalStateException();

            String caps = _options.getProperty(PROP_CAPABILITIES);
            if (caps == null)
                _options.setProperty(PROP_CAPABILITIES, ""+cap);
            else if (caps.indexOf(cap) == -1)
                _options.setProperty(PROP_CAPABILITIES, caps + cap);
    }

    /**
     * @throws IllegalStateException if RouterInfo is already signed
     */
    public void delCapability(char cap) {
        if (_signature != null)
            throw new IllegalStateException();

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
        if (_published < earliestExpire)
            return false;

        return true;
    }
    
    
    /**
     * Pull the first workable target address for the given transport
     *
     */
    public RouterAddress getTargetAddress(String transportStyle) {
        for (RouterAddress addr :  _addresses) {
            if (addr.getTransportStyle().equals(transportStyle)) 
                return addr;
        }
        return null;
    }
    
    /**
     *  For future multiple addresses per-transport (IPV6), currently unused
     *  @since 0.7.11
     */
    public List<RouterAddress> getTargetAddresses(String transportStyle) {
        List<RouterAddress> ret = new Vector<RouterAddress>();
        for (RouterAddress addr :  _addresses) {
            if(addr.getTransportStyle().equals(transportStyle))
                ret.add(addr);
        }
        return ret;
    }

    /**
     * Actually validate the signature
     */
    private void doValidate() {
        _isValid = super.verifySignature();
        _validated = true;

        if (!_isValid) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(RouterInfo.class);
            byte data[] = null;
            try {
                data = getBytes();
            } catch (DataFormatException dfe) {
                log.error("Error validating", dfe);
                return;
            }
            log.error("Invalid [" + SHA256Generator.getInstance().calculateHash(data).toBase64()
                           + (log.shouldLog(Log.WARN) ? ("]\n" + toString()) : ""),
                           new Exception("Signature failed"));
        }
    }
    
    /**
     *  This does NOT validate the signature
     *
     *  @throws IllegalStateException if RouterInfo was already read in
     */
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        readBytes(in, false);
    }

    /**
     *  If verifySig is true,
     *  this validates the signature while reading in,
     *  and throws a DataFormatException if the sig is invalid.
     *  This is faster than reserializing to validate later.
     *
     *  @throws IllegalStateException if RouterInfo was already read in
     *  @since 0.9
     */
    public void readBytes(InputStream in, boolean verifySig) throws DataFormatException, IOException {
        if (_signature != null)
            throw new IllegalStateException();
        InputStream din;
        MessageDigest digest;
        if (verifySig) {
            digest = SHA1.getInstance();
            din = new DigestInputStream(in, digest);
        } else {
            digest = null;
            din = in;
        }
        _identity = new RouterIdentity();
        _identity.readBytes(din);
        // avoid thrashing objects
        //Date when = DataHelper.readDate(in);
        //if (when == null)
        //    _published = 0;
        //else
        //    _published = when.getTime();
        _published = DataHelper.readLong(din, 8);
        int numAddresses = (int) DataHelper.readLong(din, 1);
        for (int i = 0; i < numAddresses; i++) {
            RouterAddress address = new RouterAddress();
            address.readBytes(din);
            _addresses.add(address);
        }
        int numPeers = (int) DataHelper.readLong(din, 1);
        if (numPeers == 0) {
            _peers = null;
        } else {
            _peers = new HashSet(numPeers);
            for (int i = 0; i < numPeers; i++) {
                Hash peerIdentityHash = new Hash();
                peerIdentityHash.readBytes(din);
                _peers.add(peerIdentityHash);
            }
        }
        DataHelper.readProperties(din, _options);
        _signature = new Signature();
        _signature.readBytes(in);

        SHA1Hash hash = new SHA1Hash(digest.digest());
        if (verifySig) {
            _isValid = DSAEngine.getInstance().verifySignature(_signature, hash, _identity.getSigningPublicKey());
            _validated = true;
            if (!_isValid) {
                throw new DataFormatException("Bad sig");
            }
        }

        //_log.debug("Read routerInfo: " + toString());
    }
    
    /**
     *  This does NOT validate the signature
     */
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_identity == null) throw new DataFormatException("Missing identity");
        if (_published < 0) throw new DataFormatException("Invalid published date: " + _published);
        if (_signature == null) throw new DataFormatException("Signature is null");
        //if (!isValid())
        //    throw new DataFormatException("Data is not valid");
        ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
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
               && _published == info.getPublished();
               // Let's speed up the NetDB
               //&& DataHelper.eq(_addresses, info.getAddresses())
               //&& DataHelper.eq(_options, info.getOptions()) 
               //&& DataHelper.eq(getPeers(), info.getPeers());
    }
    
    @Override
    public int hashCode() {
        if (!_hashCodeInitialized) {
            _hashCode = DataHelper.hashCode(_identity) + (int) _published;
            _hashCodeInitialized = true;
        }
        return _hashCode;
    }
    
    @Override
    public String toString() {
        if (_stringified != null) return _stringified;
        StringBuilder buf = new StringBuilder(5*1024);
        buf.append("[RouterInfo: ");
        buf.append("\n\tIdentity: ").append(_identity);
        buf.append("\n\tSignature: ").append(_signature);
        buf.append("\n\tPublished on: ").append(new Date(_published));
        buf.append("\n\tAddresses: #: ").append(_addresses.size());
        for (RouterAddress addr : _addresses) {
            buf.append("\n\t\tAddress: ").append(addr);
        }
        Set<Hash> peers = getPeers();
        buf.append("\n\tPeers: #: ").append(peers.size());
        for (Hash hash : peers) {
            buf.append("\n\t\tPeer hash: ").append(hash);
        }
        buf.append("\n\tOptions: #: ").append(_options.size());
        for (Map.Entry e : _options.entrySet()) {
            String key = (String) e.getKey();
            String val = (String) e.getValue();
            buf.append("\n\t\t[").append(key).append("] = [").append(val).append("]");
        }
        buf.append("]");
        _stringified = buf.toString();
        return _stringified;
    }

    /**
     *  Print out routerinfos from files specified on the command line
     *  @since 0.8
     */
    public static void main(String[] args) {
        if (args.length <= 0) {
            System.err.println("Usage: RouterInfo file ...");
            System.exit(1);
        }
        for (int i = 0; i < args.length; i++) {
             RouterInfo ri = new RouterInfo();
             InputStream is = null;
             try {
                 is = new java.io.FileInputStream(args[i]);
                 ri.readBytes(is);
                 if (ri.isValid())
                     System.out.println(ri.toString());
                 else
                     System.err.println("Router info " + args[i] + " is invalid");
             } catch (Exception e) {
                 System.err.println("Error reading " + args[i] + ": " + e);
             } finally {
                 if (is != null) {
                     try { is.close(); } catch (IOException ioe) {}
                 }
             }
        }
    }
}
