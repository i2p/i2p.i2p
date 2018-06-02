package net.i2p.data.router;

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

import net.i2p.I2PAppContext;
import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.SHA1;
import net.i2p.crypto.SHA1Hash;
import net.i2p.crypto.SHA256Generator;
import net.i2p.crypto.SigType;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.KeysAndCert;
import net.i2p.data.Signature;
import net.i2p.data.SimpleDataStructure;
import net.i2p.router.Router;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SystemVersion;

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
 * @since 0.9.16 moved from net.i2p.data
 * @author jrandom
 */
public class RouterInfo extends DatabaseEntry {
    private RouterIdentity _identity;
    private volatile long _published;
    /**
     *  Save addresses in the order received so the signature works.
     */
    private final List<RouterAddress> _addresses;
    /** may be null to save memory, no longer final */
    private Set<Hash> _peers;
    private final Properties _options;
    private volatile boolean _validated;
    private volatile boolean _isValid;
    //private volatile String _stringified;
    private volatile byte _byteified[];
    private volatile int _hashCode;
    private volatile boolean _hashCodeInitialized;
    /** should we cache the byte and string versions _byteified ? **/
    private boolean _shouldCache;
    /**
     * Maybe we should check if we are floodfill?
     * If we do bring this back, don't do on ARM or Android
     */
    private static final boolean CACHE_ALL = false; // SystemVersion.getMaxMemory() > 128*1024*1024l;

    public static final String PROP_NETWORK_ID = "netId";
    public static final String PROP_CAPABILITIES = "caps";
    public static final char CAPABILITY_HIDDEN = 'H';

    /** Public string of chars which serve as bandwidth capacity markers
     * NOTE: individual chars defined in Router.java
     */
    public static final String BW_CAPABILITY_CHARS = "" +
        // reverse, so e.g. "POfR" works correctly
        Router.CAPABILITY_BW_UNLIMITED +
        Router.CAPABILITY_BW512 +
        Router.CAPABILITY_BW256 +
        Router.CAPABILITY_BW128 +
        Router.CAPABILITY_BW64 +
        Router.CAPABILITY_BW32 +
        Router.CAPABILITY_BW12;
    
    public RouterInfo() {
        _addresses = new ArrayList<RouterAddress>(2);
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

    public KeysAndCert getKeysAndCert() {
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
     * Return the number of router addresses.
     * More efficient than getAddresses().size()
     *
     * @since 0.9.27
     */
    public int getAddressCount() {
        return _addresses.size();
    }

    /**
     * Retrieve the set of RouterAddress structures at which this
     * router can be contacted.
     *
     * @return unmodifiable view, non-null
     */
    public Collection<RouterAddress> getAddresses() {
            return Collections.unmodifiableList(_addresses);
    }

    /**
     * Specify a set of RouterAddress structures at which this router
     * can be contacted.
     *
     * @param addresses may be null
     * @throws IllegalStateException if RouterInfo is already signed or addresses previously set
     */
    public void setAddresses(Collection<RouterAddress> addresses) {
        if (_signature != null || !_addresses.isEmpty())
            throw new IllegalStateException();
        if (addresses != null) {
            _addresses.addAll(addresses);
        }
    }

    /**
     * Retrieve a set of SHA-256 hashes of RouterIdentities from routers
     * this router can be reached through.
     *
     * @deprecated Implemented here but unused elsewhere
     */
    @Deprecated
    public Set<Hash> getPeers() {
        if (_peers == null)
            return Collections.emptySet();
        return _peers;
    }

    /**
     * Specify a set of SHA-256 hashes of RouterIdentities from routers
     * this router can be reached through.
     *
     * @deprecated Implemented here but unused elsewhere
     * @throws IllegalStateException if RouterInfo is already signed
     */
    @Deprecated
    public void setPeers(Set<Hash> peers) {
        if (_signature != null)
            throw new IllegalStateException();
        if (peers == null || peers.isEmpty()) {
            _peers = null;
            return;
        }
        if (_peers == null)
            _peers = new HashSet<Hash>(2);
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
    @Deprecated
    public Properties getOptions() {
        return _options;
    }

    /**
     * Retrieve a set of options or statistics that the router can expose.
     *
     * @return an unmodifiable view, non-null, sorted
     * @since 0.8.13
     */
    public Map<Object, Object> getOptionsMap() {
        return Collections.unmodifiableMap(_options);
    }

    public String getOption(String opt) {
        return _options.getProperty(opt);
    }

    /**
     * For convenience, the same as getOption("router.version"),
     * but returns "0" if unset.
     *
     * @return non-null, "0" if unknown.
     * @since 0.9.18
     */
    public String getVersion() {
        String rv = _options.getProperty("router.version");
        return rv != null ? rv : "0";
    }

    /**
     * Configure a set of options or statistics that the router can expose.
     * Makes a copy.
     *
     * Warning, clears all capabilities, must be called BEFORE addCapability().
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
        ByteArrayOutputStream out = new ByteArrayOutputStream(2*1024);
        try {
            writeDataBytes(out);
        } catch (IOException ioe) {
            throw new DataFormatException("IO Error getting bytes", ioe);
        }
        byte data[] = out.toByteArray();
        if (CACHE_ALL || _shouldCache)
            _byteified = data;
        return data;
    }

    /** 
     * Write out the raw payload of the routerInfo, excluding the signature.  This
     * caches the data in memory if possible.
     *
     * @throws DataFormatException if the data is somehow b0rked (missing props, etc)
     * @throws IOException
     * @since 0.9.24
     */
    private void writeDataBytes(OutputStream out) throws DataFormatException, IOException {
        if (_identity == null) throw new DataFormatException("Missing identity");
        if (_published < 0) throw new DataFormatException("Invalid published date: " + _published);

            _identity.writeBytes(out);
            // avoid thrashing objects
            //DataHelper.writeDate(out, new Date(_published));
            DataHelper.writeLong(out, 8, _published);
            int sz = _addresses.size();
            if (sz <= 0 || isHidden()) {
                // Do not send IP address to peers in hidden mode
                out.write((byte) 0);
            } else {
                out.write((byte) sz);
                for (RouterAddress addr : _addresses) {
                    addr.writeBytes(out);
                }
            }
            // XXX: what about peers?
            // answer: they're always empty... they're a placeholder for one particular
            //         method of trusted links, which isn't implemented in the router
            //         at the moment, and may not be later.
            int psz = _peers == null ? 0 : _peers.size();
            out.write((byte) psz);
            if (psz > 0) {
                Collection<Hash> peers = _peers;
                if (psz > 1)
                    // WARNING this sort algorithm cannot be changed, as it must be consistent
                    // network-wide. The signature is not checked at readin time, but only
                    // later, and the hashes are stored in a Set, not a List.
                    peers = SortHelper.sortStructures(peers);
                for (Hash peerHash : peers) {
                    peerHash.writeBytes(out);
                }
            }
            DataHelper.writeProperties(out, _options);
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
        // shortcut
        if ("2".equals(id))
            return 2;
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
     *
     * @return true if either 'H' is in the capbilities, or router indentity contains a hidden cert.
     */
    public boolean isHidden() {
        return (getCapabilities().indexOf(CAPABILITY_HIDDEN) >= 0) ||
               (_identity != null && _identity.isHidden());
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
            if (bwTiers.indexOf(capabilities.charAt(i)) != -1) {
                bwTier = String.valueOf(capabilities.charAt(i));
                break;
            }
        }
        return (bwTier);
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
     * Pull the first workable target address for the given transport.
     * Use to check for any address. For all addresses, use getTargetAddresses(),
     * which you probably want if you care about IPv6.
     */
    public RouterAddress getTargetAddress(String transportStyle) {
        for (RouterAddress addr :  _addresses) {
            if (addr.getTransportStyle().equals(transportStyle)) 
                return addr;
        }
        return null;
    }
    
    /**
     *  For multiple addresses per-transport (IPv4 or IPv6)
     *  @return non-null
     *  @since 0.7.11
     */
    public List<RouterAddress> getTargetAddresses(String transportStyle) {
        List<RouterAddress> ret = new ArrayList<RouterAddress>(_addresses.size());
        for (RouterAddress addr :  _addresses) {
            if(addr.getTransportStyle().equals(transportStyle))
                ret.add(addr);
        }
        return ret;
    }
    
    /**
     *  For multiple addresses per-transport (IPv4 or IPv6)
     *  Return addresses matching either of two styles
     *
     *  @return non-null
     *  @since 0.9.35
     */
    public List<RouterAddress> getTargetAddresses(String transportStyle1, String transportStyle2) {
        List<RouterAddress> ret = new ArrayList<RouterAddress>(_addresses.size());
        for (RouterAddress addr :  _addresses) {
            String style = addr.getTransportStyle();
            if (style.equals(transportStyle1) || style.equals(transportStyle2))
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
            if (log.shouldWarn()) {
                log.warn("Sig verify fail: " + toString(), new Exception("from"));
            //} else {
            //    log.error("RI Sig verify fail: " + _identity.getHash());
            }
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
        _identity = new RouterIdentity();
        _identity.readBytes(in);
        // can't set the digest until we know the sig type
        InputStream din;
        MessageDigest digest;
        if (verifySig) {
            SigType type = _identity.getSigningPublicKey().getType();
            if (type != SigType.EdDSA_SHA512_Ed25519) {
                // This won't work for EdDSA
                digest = _identity.getSigningPublicKey().getType().getDigestInstance();
                // TODO any better way?
                digest.update(_identity.toByteArray());
                din = new DigestInputStream(in, digest);
            } else {
                digest = null;
                din = in;
            }
        } else {
            digest = null;
            din = in;
        }
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
            _peers = new HashSet<Hash>(numPeers);
            for (int i = 0; i < numPeers; i++) {
                Hash peerIdentityHash = new Hash();
                peerIdentityHash.readBytes(din);
                _peers.add(peerIdentityHash);
            }
        }
        DataHelper.readProperties(din, _options);
        _signature = new Signature(_identity.getSigningPublicKey().getType());
        _signature.readBytes(in);

        if (verifySig) {
            SigType type = _identity.getSigningPublicKey().getType();
            if (type != SigType.EdDSA_SHA512_Ed25519) {
                // This won't work for EdDSA
                SimpleDataStructure hash = _identity.getSigningPublicKey().getType().getHashInstance();
                hash.setData(digest.digest());
                _isValid = DSAEngine.getInstance().verifySignature(_signature, hash, _identity.getSigningPublicKey());
                _validated = true;
            } else {
                doValidate();
            }
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
        if (_signature == null) throw new DataFormatException("Signature is null");
        writeDataBytes(out);
        _signature.writeBytes(out);
    }
    
    @Override
    public boolean equals(Object object) {
        if (object == this) return true;
        if ((object == null) || !(object instanceof RouterInfo)) return false;
        RouterInfo info = (RouterInfo) object;
        return
               _published == info.getPublished()
               && DataHelper.eq(_signature, info.getSignature())
               && DataHelper.eq(_identity, info.getIdentity());
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
        //if (_stringified != null) return _stringified;
        StringBuilder buf = new StringBuilder(1024);
        buf.append("[RouterInfo: ");
        buf.append("\n\tIdentity: ").append(_identity);
        buf.append("\n\tSignature: ").append(_signature);
        buf.append("\n\tPublished: ").append(new Date(_published));
        if (_peers != null) {
            buf.append("\n\tPeers (").append(_peers.size()).append("):");
            for (Hash hash : _peers) {
                buf.append("\n\t\tPeer hash: ").append(hash);
            }
        }
        buf.append("\n\tOptions (").append(_options.size()).append("):");
        for (Map.Entry<Object, Object> e : _options.entrySet()) {
            String key = (String) e.getKey();
            String val = (String) e.getValue();
            buf.append("\n\t\t[").append(key).append("] = [").append(val).append("]");
        }
        if (!_addresses.isEmpty()) {
            buf.append("\n\tAddresses (").append(_addresses.size()).append("):");
            for (RouterAddress addr : _addresses) {
                buf.append("\n\t").append(addr);
            }
        }
        buf.append("]");
        String rv = buf.toString();
        //_stringified = rv;
        return rv;
    }

    /**
     *  Print out routerinfos from files specified on the command line.
     *  Exits 1 if any RI is invalid, fails signature, etc.
     *  @since 0.8
     */
    public static void main(String[] args) {
        if (args.length <= 0) {
            System.err.println("Usage: RouterInfo file ...");
            System.exit(1);
        }
        boolean fail = false;
        for (int i = 0; i < args.length; i++) {
             RouterInfo ri = new RouterInfo();
             InputStream is = null;
             try {
                 is = new java.io.FileInputStream(args[i]);
                 ri.readBytes(is);
                 if (ri.isValid()) {
                     System.out.println(ri.toString());
                  } else {
                     System.err.println("Router info " + args[i] + " is invalid");
                     fail = true;
                  }
             } catch (IOException e) {
                 System.err.println("Error reading " + args[i] + ": " + e);
                 fail = true;
             } catch (DataFormatException e) {
                 System.err.println("Error reading " + args[i] + ": " + e);
                 fail = true;
             } finally {
                 if (is != null) {
                     try { is.close(); } catch (IOException ioe) {}
                 }
             }
        }
        if (fail)
            System.exit(1);
    }
}
