package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.TunnelConfigurationSessionKey;
import net.i2p.data.i2np.TunnelSessionKey;
import net.i2p.data.i2np.TunnelSigningPrivateKey;
import net.i2p.data.i2np.TunnelSigningPublicKey;
import net.i2p.util.Clock;

/**
 * Defines the information associated with a tunnel
 */
public class TunnelInfo extends DataStructureImpl {
    private TunnelId _id;
    private Hash _nextHop;
    private Hash _thisHop;
    private TunnelInfo _nextHopInfo;
    private TunnelConfigurationSessionKey _configurationKey;
    private TunnelSigningPublicKey _verificationKey;
    private TunnelSigningPrivateKey _signingKey;
    private TunnelSessionKey _encryptionKey;
    private Destination _destination;
    private Properties _options;
    private TunnelSettings _settings;
    private long _created;
    private boolean _ready;
    private boolean _wasEverReady;
    
    public TunnelInfo() {
	setTunnelId(null);
	setThisHop(null);
	setNextHop(null);
	setNextHopInfo(null);
	_configurationKey = null;
	_verificationKey = null;
	_signingKey = null;
	_encryptionKey = null;
	setDestination(null);
	setSettings(null);
	_options = new Properties();
	_ready = false;
	_wasEverReady = false;
	_created = Clock.getInstance().now();
    }
    
    public TunnelId getTunnelId() { return _id; }
    public void setTunnelId(TunnelId id) { _id = id; }
    
    public Hash getNextHop() { return _nextHop; }
    public void setNextHop(Hash nextHopRouterIdentity) { _nextHop = nextHopRouterIdentity; }
    
    public Hash getThisHop() { return _thisHop; }
    public void setThisHop(Hash thisHopRouterIdentity) { _thisHop = thisHopRouterIdentity; }
    
    public TunnelInfo getNextHopInfo() { return _nextHopInfo; }
    public void setNextHopInfo(TunnelInfo info) { _nextHopInfo = info; }
    
    public TunnelConfigurationSessionKey getConfigurationKey() { return _configurationKey; }
    public void setConfigurationKey(TunnelConfigurationSessionKey key) { _configurationKey = key; }
    public void setConfigurationKey(SessionKey key) { 
	TunnelConfigurationSessionKey tk = new TunnelConfigurationSessionKey();
	tk.setKey(key);
	_configurationKey = tk;
    }
    
    public TunnelSigningPublicKey getVerificationKey() { return _verificationKey; }
    public void setVerificationKey(TunnelSigningPublicKey key) { _verificationKey = key; }
    public void setVerificationKey(SigningPublicKey key) { 
	TunnelSigningPublicKey tk = new TunnelSigningPublicKey();
	tk.setKey(key);
	_verificationKey = tk; 
    }
    
    public TunnelSigningPrivateKey getSigningKey() { return _signingKey; }
    public void setSigningKey(TunnelSigningPrivateKey key) { _signingKey = key; }
    public void setSigningKey(SigningPrivateKey key) { 
	TunnelSigningPrivateKey tk = new TunnelSigningPrivateKey();
	tk.setKey(key);
	_signingKey = tk; 
    }
    
    public TunnelSessionKey getEncryptionKey() { return _encryptionKey; }
    public void setEncryptionKey(TunnelSessionKey key) { _encryptionKey = key; }
    public void setEncryptionKey(SessionKey key) { 
	TunnelSessionKey tk = new TunnelSessionKey();
	tk.setKey(key);
	_encryptionKey = tk;
    }
    
    public Destination getDestination() { return _destination; }
    public void setDestination(Destination dest) { _destination = dest; }
    
    public String getProperty(String key) { return _options.getProperty(key); }
    public void setProperty(String key, String val) { _options.setProperty(key, val); }
    public void clearProperties() { _options.clear(); }
    public Set getPropertyNames() { return new HashSet(_options.keySet()); }
    
    public TunnelSettings getSettings() { return _settings; }
    public void setSettings(TunnelSettings settings) { _settings = settings; }    
   
    /**
     * Have all of the routers in this tunnel confirmed participation, and we're ok to
     * start sending messages through this tunnel?
     */
    public boolean getIsReady() { return _ready; }
    public void setIsReady(boolean ready) { 
	_ready = ready; 
	if (ready)
	    _wasEverReady = true;
    }
    /**
     * true if this tunnel was ever working (aka rebuildable)
     *
     */
    public boolean getWasEverReady() { return _wasEverReady; }
    
    public long getCreated() { return _created; }
    
    /**
     * Number of hops left in the tunnel (including this one)
     *
     */
    public final int getLength() {
	int len = 0;
	TunnelInfo info = this;
	while (info != null) {
	    info = info.getNextHopInfo();
	    len++;
	}
	return len;
    }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
	_options = DataHelper.readProperties(in);
	Boolean includeDest = DataHelper.readBoolean(in);
	if (includeDest.booleanValue()) {
	    _destination = new Destination();
	    _destination.readBytes(in);
	} else {
	    _destination = null;
	}
	Boolean includeThis = DataHelper.readBoolean(in);
	if (includeThis.booleanValue()) {
	    _thisHop = new Hash();
	    _thisHop.readBytes(in);
	} else {
	    _thisHop = null;
	}
	Boolean includeNext = DataHelper.readBoolean(in);
	if (includeNext.booleanValue()) {
	    _nextHop = new Hash();
	    _nextHop.readBytes(in);
	} else {
	    _nextHop = null;
	}
	Boolean includeNextInfo = DataHelper.readBoolean(in);
	if (includeNextInfo.booleanValue()) {
	    _nextHopInfo = new TunnelInfo();
	    _nextHopInfo.readBytes(in);
	} else {
	    _nextHopInfo = null;
	}
	_id = new TunnelId();
	_id.readBytes(in);
	Boolean includeConfigKey = DataHelper.readBoolean(in);
	if (includeConfigKey.booleanValue()) {
	    _configurationKey = new TunnelConfigurationSessionKey();
	    _configurationKey.readBytes(in);
	} else {
	    _configurationKey = null;
	}
	Boolean includeEncryptionKey = DataHelper.readBoolean(in);
	if (includeEncryptionKey.booleanValue()) {
	    _encryptionKey = new TunnelSessionKey();
	    _encryptionKey.readBytes(in);
	} else {
	    _encryptionKey = null;
	}
	Boolean includeSigningKey = DataHelper.readBoolean(in);
	if (includeSigningKey.booleanValue()) {
	    _signingKey = new TunnelSigningPrivateKey();
	    _signingKey.readBytes(in);
	} else {
	    _signingKey = null;
	}
	Boolean includeVerificationKey = DataHelper.readBoolean(in);
	if (includeVerificationKey.booleanValue()) {
	    _verificationKey = new TunnelSigningPublicKey();
	    _verificationKey.readBytes(in);
	} else {
	    _verificationKey = null;
	}
	_settings = new TunnelSettings();
	_settings.readBytes(in);
	Boolean ready = DataHelper.readBoolean(in);
	if (ready != null)
	    setIsReady(ready.booleanValue());
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_id == null) throw new DataFormatException("Invalid tunnel ID: " + _id);
	if (_options == null) throw new DataFormatException("Options are null"); 
	if (_settings == null) throw new DataFormatException("Settings are null");
	// everything else is optional in the serialization

	DataHelper.writeProperties(out, _options);
	if (_destination != null) {
	    DataHelper.writeBoolean(out, Boolean.TRUE);
	    _destination.writeBytes(out);
	} else {
	    DataHelper.writeBoolean(out, Boolean.FALSE);
	}
	if (_thisHop != null) {
	    DataHelper.writeBoolean(out, Boolean.TRUE);
	    _thisHop.writeBytes(out);
	} else {
	    DataHelper.writeBoolean(out, Boolean.FALSE);
	}
	if (_nextHop != null) {
	    DataHelper.writeBoolean(out, Boolean.TRUE);
	    _nextHop.writeBytes(out);
	} else {
	    DataHelper.writeBoolean(out, Boolean.FALSE);
	}
	if (_nextHopInfo != null) {
	    DataHelper.writeBoolean(out, Boolean.TRUE);
	    _nextHopInfo.writeBytes(out);
	} else {
	    DataHelper.writeBoolean(out, Boolean.FALSE);
	}
	_id.writeBytes(out);
	if (_configurationKey != null) {
	    DataHelper.writeBoolean(out, Boolean.TRUE);
	    _configurationKey.writeBytes(out);
	} else {
	    DataHelper.writeBoolean(out, Boolean.FALSE);
	}
	if (_encryptionKey != null) {
	    DataHelper.writeBoolean(out, Boolean.TRUE);
	    _encryptionKey.writeBytes(out);
	} else {
	    DataHelper.writeBoolean(out, Boolean.FALSE);
	}
	if (_signingKey != null) {
	    DataHelper.writeBoolean(out, Boolean.TRUE);
	    _signingKey.writeBytes(out);
	} else {
	    DataHelper.writeBoolean(out, Boolean.FALSE);
	}
	if (_verificationKey != null) {
	    DataHelper.writeBoolean(out, Boolean.TRUE);
	    _verificationKey.writeBytes(out);
	} else {
	    DataHelper.writeBoolean(out, Boolean.FALSE);
	}
	_settings.writeBytes(out);
	DataHelper.writeBoolean(out, new Boolean(_ready));
    }
    
    public String toString() {
	StringBuffer buf = new StringBuffer();
	buf.append("[Tunnel ").append(_id.getTunnelId());
	TunnelInfo cur = this;
	int i = 0;
	while (cur != null) {
	    buf.append("\n*Hop ").append(i).append(": ").append(cur.getThisHop());
	    if (cur.getEncryptionKey() != null)
		buf.append("\n Encryption key: ").append(cur.getEncryptionKey());
	    if (cur.getSigningKey() != null)
		buf.append("\n Signing key: ").append(cur.getSigningKey());
	    if (cur.getVerificationKey() != null)
		buf.append("\n Verification key: ").append(cur.getVerificationKey());
	    if (cur.getDestination() != null)
		buf.append("\n Destination: ").append(cur.getDestination().calculateHash().toBase64());
	    if (cur.getNextHop() != null)
		buf.append("\n Next: ").append(cur.getNextHop());
	    if (cur.getSettings() == null)
		buf.append("\n Expiration: ").append("none");
	    else
		buf.append("\n Expiration: ").append(new Date(cur.getSettings().getExpiration()));
	    buf.append("\n Ready: ").append(getIsReady());
	    cur = cur.getNextHopInfo();
	    i++;
	}
	buf.append("]");
	return buf.toString();
    }
    
    public int hashCode() {
	int rv = 0;
	rv = 7*rv + DataHelper.hashCode(_options);
	rv = 7*rv + DataHelper.hashCode(_destination);
	rv = 7*rv + DataHelper.hashCode(_nextHop);
	rv = 7*rv + DataHelper.hashCode(_thisHop);
	rv = 7*rv + DataHelper.hashCode(_id);
	rv = 7*rv + DataHelper.hashCode(_configurationKey);
	rv = 7*rv + DataHelper.hashCode(_encryptionKey);
	rv = 7*rv + DataHelper.hashCode(_signingKey);
	rv = 7*rv + DataHelper.hashCode(_verificationKey);
	rv = 7*rv + DataHelper.hashCode(_settings);
	rv = 7*rv + (_ready ? 0 : 1);
	return rv;
    }
    
    public boolean equals(Object obj) {
	if ( (obj != null) && (obj instanceof TunnelInfo) ) {
	    TunnelInfo info = (TunnelInfo)obj;
	    return DataHelper.eq(getConfigurationKey(), info.getConfigurationKey()) &&
		   DataHelper.eq(getDestination(), info.getDestination()) &&
		   getIsReady() == info.getIsReady() &&
		   DataHelper.eq(getEncryptionKey(), info.getEncryptionKey()) && 
		   DataHelper.eq(getNextHop(), info.getNextHop()) && 
		   DataHelper.eq(getNextHopInfo(), info.getNextHopInfo()) &&
		   DataHelper.eq(getSettings(), info.getSettings()) &&
		   DataHelper.eq(getSigningKey(), info.getSigningKey()) &&
		   DataHelper.eq(getThisHop(), info.getThisHop()) &&
		   DataHelper.eq(getTunnelId(), info.getTunnelId()) &&
		   DataHelper.eq(getVerificationKey(), info.getVerificationKey()) &&
		   DataHelper.eq(_options, info._options);
	} else {
	    return false;
	}
    }
}
