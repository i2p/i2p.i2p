package net.i2p;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import net.i2p.client.naming.NamingService;
import net.i2p.crypto.AESEngine;
import net.i2p.crypto.CryptixAESEngine;
import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.DummyElGamalEngine;
import net.i2p.crypto.ElGamalAESEngine;
import net.i2p.crypto.ElGamalEngine;
import net.i2p.crypto.HMACSHA256Generator;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.PersistentSessionKeyManager;
import net.i2p.crypto.SHA256Generator;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.RoutingKeyGenerator;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;
import net.i2p.util.LogManager;
import net.i2p.util.RandomSource;

/**
 * <p>Provide a base scope for accessing singletons that I2P exposes.  Rather than
 * using the traditional singleton, where any component can access the component
 * in question directly, all of those I2P related singletons are exposed through
 * a particular I2PAppContext.  This helps not only with understanding their use
 * and the components I2P exposes, but it also allows multiple isolated 
 * environments to operate concurrently within the same JVM - particularly useful
 * for stubbing out implementations of the rooted components and simulating the
 * software's interaction between multiple instances.</p>
 *
 * As a simplification, there is also a global context - if some component needs
 * access to one of the singletons but doesn't have its own context from which
 * to root itself, it binds to the I2PAppContext's globalAppContext(), which is
 * the first context that was created within the JVM, or a new one if no context
 * existed already.  This functionality is often used within the I2P core for 
 * logging - e.g. <pre>
 *     private static final Log _log = new Log(someClass.class);
 * </pre>
 * It is for this reason that applications that care about working with multiple
 * contexts should build their own context as soon as possible (within the main(..))
 * so that any referenced components will latch on to that context instead of 
 * instantiating a new one.  However, there are situations in which both can be
 * relevent.
 *
 */
public class I2PAppContext {
    /** the context that components without explicit root are bound */
    protected static I2PAppContext _globalAppContext;
    /** 
     * Determine if the app context been initialized.  If this is false 
     * and something asks for the globalAppContext, a new one is created, 
     * otherwise the existing one is used.
     *
     */
    protected static volatile boolean _globalAppContextInitialized;
    
    private Properties _overrideProps;
    
    private StatManager _statManager;
    private SessionKeyManager _sessionKeyManager;
    private NamingService _namingService;
    private ElGamalEngine _elGamalEngine;
    private ElGamalAESEngine _elGamalAESEngine;
    private AESEngine _AESEngine;
    private LogManager _logManager;
    private HMACSHA256Generator _hmac;
    private SHA256Generator _sha;
    private Clock _clock;
    private DSAEngine _dsa;
    private RoutingKeyGenerator _routingKeyGenerator;
    private RandomSource _random;
    private KeyGenerator _keyGenerator;
    private volatile boolean _statManagerInitialized;
    private volatile boolean _sessionKeyManagerInitialized;
    private volatile boolean _namingServiceInitialized;
    private volatile boolean _elGamalEngineInitialized;
    private volatile boolean _elGamalAESEngineInitialized;
    private volatile boolean _AESEngineInitialized;
    private volatile boolean _logManagerInitialized;
    private volatile boolean _hmacInitialized;
    private volatile boolean _shaInitialized;
    private volatile boolean _clockInitialized;
    private volatile boolean _dsaInitialized;
    private volatile boolean _routingKeyGeneratorInitialized;
    private volatile boolean _randomInitialized;
    private volatile boolean _keyGeneratorInitialized;

    /**
     * Pull the default context, creating a new one if necessary, else using 
     * the first one created.
     *
     */
    public static I2PAppContext getGlobalContext() { 
        if (!_globalAppContextInitialized) {
            synchronized (I2PAppContext.class) {
                System.err.println("*** Building seperate global context!");
                if (_globalAppContext == null)
                    _globalAppContext = new I2PAppContext(false, null);
                _globalAppContextInitialized = true;
            }
        }
        return _globalAppContext; 
    }

    /**
     * Lets root a brand new context
     *
     */
    public I2PAppContext() {
        this(true, null);
    }
    /**
     * Lets root a brand new context
     *
     */
    public I2PAppContext(Properties envProps) {
        this(true, envProps);
    }
    /**
     * @param doInit should this context be used as the global one (if necessary)?
     */
    private I2PAppContext(boolean doInit, Properties envProps) {
        //System.out.println("App context created: " + this);
        if (doInit) {
            if (!_globalAppContextInitialized) {
                synchronized (I2PAppContext.class) { 
                    if (_globalAppContext == null) {
                        _globalAppContext = this;
                        _globalAppContextInitialized = true;
                    }
                }
            }
        }
        _overrideProps = envProps;
        _statManager = null;
        _sessionKeyManager = null;
        _namingService = null;
        _elGamalEngine = null;
        _elGamalAESEngine = null;
        _logManager = null;
        _statManagerInitialized = false;
        _sessionKeyManagerInitialized = false;
        _namingServiceInitialized = false;
        _elGamalEngineInitialized = false;
        _elGamalAESEngineInitialized = false;
        _logManagerInitialized = false;
    }
    
    /**
     * Access the configuration attributes of this context, using properties 
     * provided during the context construction, or falling back on 
     * System.getProperty if no properties were provided during construction
     * (or the specified prop wasn't included).
     *
     */
    public String getProperty(String propName) {
        if (_overrideProps != null) {
            if (_overrideProps.containsKey(propName))
                return _overrideProps.getProperty(propName);
        }
        return System.getProperty(propName);
    }

    /**
     * Access the configuration attributes of this context, using properties 
     * provided during the context construction, or falling back on 
     * System.getProperty if no properties were provided during construction
     * (or the specified prop wasn't included).
     *
     */
    public String getProperty(String propName, String defaultValue) {
        if (_overrideProps != null) {
            if (_overrideProps.containsKey(propName))
                return _overrideProps.getProperty(propName, defaultValue);
        }
        return System.getProperty(propName, defaultValue);
    }
    /**
     * Access the configuration attributes of this context, listing the properties 
     * provided during the context construction, as well as the ones included in
     * System.getProperties.
     *
     * @return set of Strings containing the names of defined system properties
     */
    public Set getPropertyNames() { 
        Set names = new HashSet(System.getProperties().keySet());
        if (_overrideProps != null)
            names.addAll(_overrideProps.keySet());
        return names;
    }
    
    /**
     * The statistics component with which we can track various events
     * over time.
     */
    public StatManager statManager() { 
        if (!_statManagerInitialized) initializeStatManager();
        return _statManager;
    }
    private void initializeStatManager() {
        synchronized (this) {
            if (_statManager == null)
                _statManager = new StatManager(this);
            _statManagerInitialized = true;
        }
    }
    
    /**
     * The session key manager which coordinates the sessionKey / sessionTag
     * data.  This component allows transparent operation of the 
     * ElGamal/AES+SessionTag algorithm, and contains all of the session tags
     * for one particular application.  If you want to seperate multiple apps
     * to have their own sessionTags and sessionKeys, they should use different
     * I2PAppContexts, and hence, different sessionKeyManagers.
     *
     */
    public SessionKeyManager sessionKeyManager() { 
        if (!_sessionKeyManagerInitialized) initializeSessionKeyManager();
        return _sessionKeyManager;
    }
    private void initializeSessionKeyManager() {
        synchronized (this) {
            if (_sessionKeyManager == null) 
                _sessionKeyManager = new PersistentSessionKeyManager(this);
            _sessionKeyManagerInitialized = true;
        }
    }
    
    /**
     * Pull up the naming service used in this context.  The naming service itself
     * works by querying the context's properties, so those props should be 
     * specified to customize the naming service exposed.
     */
    public NamingService namingService() { 
        if (!_namingServiceInitialized) initializeNamingService();
        return _namingService;
    }
    private void initializeNamingService() {
        synchronized (this) {
            if (_namingService == null) {
                _namingService = NamingService.createInstance(this);
            }
            _namingServiceInitialized = true;
        }
    }
    
    /**
     * This is the ElGamal engine used within this context.  While it doesn't
     * really have anything substantial that is context specific (the algorithm
     * just does the algorithm), it does transparently use the context for logging
     * its performance and activity.  In addition, the engine can be swapped with
     * the context's properties (though only someone really crazy should mess with
     * it ;)
     */
    public ElGamalEngine elGamalEngine() {
        if (!_elGamalEngineInitialized) initializeElGamalEngine();
        return _elGamalEngine;
    }
    private void initializeElGamalEngine() {
        synchronized (this) {
            if (_elGamalEngine == null) {
                if ("off".equals(getProperty("i2p.encryption", "on")))
                    _elGamalEngine = new DummyElGamalEngine(this);
                else
                    _elGamalEngine = new ElGamalEngine(this);
            }
            _elGamalEngineInitialized = true;
        }
    }
    
    /**
     * Access the ElGamal/AES+SessionTag engine for this context.  The algorithm
     * makes use of the context's sessionKeyManager to coordinate transparent
     * access to the sessionKeys and sessionTags, as well as the context's elGamal
     * engine (which in turn keeps stats, etc).
     *
     */
    public ElGamalAESEngine elGamalAESEngine() {
        if (!_elGamalAESEngineInitialized) initializeElGamalAESEngine();
        return _elGamalAESEngine;
    }
    private void initializeElGamalAESEngine() {
        synchronized (this) {
            if (_elGamalAESEngine == null)
                _elGamalAESEngine = new ElGamalAESEngine(this);
            _elGamalAESEngineInitialized = true;
        }
    }
    
    /**
     * Ok, I'll admit it.  there is no good reason for having a context specific
     * AES engine.  We dont really keep stats on it, since its just too fast to
     * matter.  Though for the crazy people out there, we do expose a way to 
     * disable it.
     */
    public AESEngine AESEngine() {
        if (!_AESEngineInitialized) initializeAESEngine();
        return _AESEngine;
    }
    private void initializeAESEngine() {
        synchronized (this) {
            if (_AESEngine == null) {
                if ("off".equals(getProperty("i2p.encryption", "on")))
                    _AESEngine = new AESEngine(this);
                else
                    _AESEngine = new CryptixAESEngine(this);
            }
            _AESEngineInitialized = true;
        }
    }
    
    /**
     * Query the log manager for this context, which may in turn have its own
     * set of configuration settings (loaded from the context's properties).  
     * Each context's logManager keeps its own isolated set of Log instances with
     * their own log levels, output locations, and rotation configuration.
     */
    public LogManager logManager() { 
        if (!_logManagerInitialized) initializeLogManager();
        return _logManager;
    }
    private void initializeLogManager() {
        synchronized (this) {
            if (_logManager == null)
                _logManager = new LogManager(this);
            _logManagerInitialized = true;
        }
    }
    /** 
     * There is absolutely no good reason to make this context specific, 
     * other than for consistency, and perhaps later we'll want to 
     * include some stats.
     */
    public HMACSHA256Generator hmac() { 
        if (!_hmacInitialized) initializeHMAC();
        return _hmac;
    }
    private void initializeHMAC() {
        synchronized (this) {
            if (_hmac == null)
                _hmac= new HMACSHA256Generator(this);
            _hmacInitialized = true;
        }
    }
    
    /**
     * Our SHA256 instance (see the hmac discussion for why its context specific)
     *
     */
    public SHA256Generator sha() { 
        if (!_shaInitialized) initializeSHA();
        return _sha;
    }
    private void initializeSHA() {
        synchronized (this) {
            if (_sha == null)
                _sha= new SHA256Generator(this);
            _shaInitialized = true;
        }
    }
    
    /**
     * Our DSA engine (see HMAC and SHA above)
     *
     */
    public DSAEngine dsa() { 
        if (!_dsaInitialized) initializeDSA();
        return _dsa;
    }
    private void initializeDSA() {
        synchronized (this) {
            if (_dsa == null)
                _dsa = new DSAEngine(this);
            _dsaInitialized = true;
        }
    }
    
    /**
     * Component to generate ElGamal, DSA, and Session keys.  For why it is in
     * the appContext, see the DSA, HMAC, and SHA comments above.
     */
    public KeyGenerator keyGenerator() {
        if (!_keyGeneratorInitialized) initializeKeyGenerator();
        return _keyGenerator;
    }
    private void initializeKeyGenerator() {
        synchronized (this) {
            if (_keyGenerator == null)
                _keyGenerator = new KeyGenerator(this);
            _keyGeneratorInitialized = true;
        }
    }
    
    /**
     * The context's synchronized clock, which is kept context specific only to
     * enable simulators to play with clock skew among different instances.
     *
     */
    public Clock clock() { 
        if (!_clockInitialized) initializeClock();
        return _clock;
    }
    private void initializeClock() {
        synchronized (this) {
            if (_clock == null)
                _clock = new Clock(this);
            _clockInitialized = true;
        }
    }
    
    /**
     * Determine how much do we want to mess with the keys to turn them 
     * into something we can route.  This is context specific because we 
     * may want to test out how things react when peers don't agree on 
     * how to skew.
     *
     */
    public RoutingKeyGenerator routingKeyGenerator() {
        if (!_routingKeyGeneratorInitialized) initializeRoutingKeyGenerator();
        return _routingKeyGenerator;
    }
    private void initializeRoutingKeyGenerator() {
        synchronized (this) {
            if (_routingKeyGenerator == null)
                _routingKeyGenerator = new RoutingKeyGenerator(this);
            _routingKeyGeneratorInitialized = true;
        }
    }
    
    /**
     * [insert snarky comment here]
     *
     */
    public RandomSource random() {
        if (!_randomInitialized) initializeRandom();
        return _random;
    }
    private void initializeRandom() {
        synchronized (this) {
            if (_random == null)
                _random = new RandomSource(this);
            _randomInitialized = true;
        }
    }
}