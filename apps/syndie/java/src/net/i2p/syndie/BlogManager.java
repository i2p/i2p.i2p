package net.i2p.syndie;

import java.io.*;
import java.text.*;
import java.util.*;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.PetName;
import net.i2p.client.naming.PetNameDB;
import net.i2p.data.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.sml.*;
import net.i2p.util.Log;

/**
 *
 */
public class BlogManager {
    private I2PAppContext _context;
    private Log _log;
    private static BlogManager _instance;
    private File _blogKeyDir;
    private File _privKeyDir;
    private File _archiveDir;
    private File _userDir;
    private File _cacheDir;
    private File _tempDir;
    private File _rootDir;
    private Archive _archive;
    
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT"));
    }
    
    public static BlogManager instance() { 
        synchronized (BlogManager.class) {
            if (_instance == null) {
                String rootDir = I2PAppContext.getGlobalContext().getProperty("syndie.rootDir");
                if (false) {
                    if (rootDir == null)
                        rootDir = System.getProperty("user.home");
                    rootDir = rootDir + File.separatorChar + ".syndie";
                } else {
                    if (rootDir == null)
                        rootDir = "./syndie";
                }
                _instance = new BlogManager(I2PAppContext.getGlobalContext(), rootDir, false);
                _instance.getArchive().regenerateIndex();
            }
            return _instance; 
        }
    }
    
    public BlogManager(I2PAppContext ctx, String rootDir) { this(ctx, rootDir, true); }
    public BlogManager(I2PAppContext ctx, String rootDir, boolean regenIndex) {
        _context = ctx;
        _log = ctx.logManager().getLog(BlogManager.class);
        _rootDir = new File(rootDir);
        _rootDir.mkdirs();
        readConfig();
        _blogKeyDir = new File(_rootDir, "blogkeys");
        _privKeyDir = new File(_rootDir, "privkeys");
        String archiveDir = _context.getProperty("syndie.archiveDir");
        if (archiveDir != null)
            _archiveDir = new File(archiveDir);
        else
            _archiveDir = new File(_rootDir, "archive");
        _userDir = new File(_rootDir, "users");
        _cacheDir = new File(_rootDir, "cache");
        _tempDir = new File(_rootDir, "temp");
        _blogKeyDir.mkdirs();
        _privKeyDir.mkdirs();
        _archiveDir.mkdirs();
        _cacheDir.mkdirs();
        _userDir.mkdirs();
        _tempDir.mkdirs();
        _archive = new Archive(ctx, _archiveDir.getAbsolutePath(), _cacheDir.getAbsolutePath());
        if (regenIndex)
            _archive.regenerateIndex();
    }
    
    private File getConfigFile() { return new File(_rootDir, "syndie.config"); }
    private void readConfig() {
        File config = getConfigFile();
        if (config.exists()) {
            try {
                Properties p = new Properties();
                DataHelper.loadProps(p, config);
                for (Iterator iter = p.keySet().iterator(); iter.hasNext(); ) {
                    String key = (String)iter.next();
                    System.setProperty(key, p.getProperty(key));
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Read config prop [" + key + "] = [" + p.getProperty(key) + "]");
                }
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Err reading", ioe);
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Config doesn't exist: " + config.getPath());
        }
    }
    
    public void writeConfig() {
        File config = new File(_rootDir, "syndie.config");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(config);
            for (Iterator iter = _context.getPropertyNames().iterator(); iter.hasNext(); ) {
                String name = (String)iter.next();
                if (name.startsWith("syndie."))
                    out.write(DataHelper.getUTF8(name + '=' + _context.getProperty(name) + '\n'));
            }
        } catch (IOException ioe) {
            _log.error("Error writing the config", ioe);
        } finally {
            if (out != null) try { out.close(); } catch (IOException ioe) {}
        }
    }
    
    public BlogInfo createBlog(String name, String description, String contactURL, String archives[]) {
        return createBlog(name, null, description, contactURL, archives);
    }
    public BlogInfo createBlog(String name, SigningPublicKey posters[], String description, String contactURL, String archives[]) {
        Object keys[] = _context.keyGenerator().generateSigningKeypair();
        SigningPublicKey pub = (SigningPublicKey)keys[0];
        SigningPrivateKey priv = (SigningPrivateKey)keys[1];
        
        try {
            FileOutputStream out = new FileOutputStream(new File(_privKeyDir, Base64.encode(pub.calculateHash().getData()) + ".priv"));
            pub.writeBytes(out);
            priv.writeBytes(out);
        } catch (DataFormatException dfe) {
            _log.error("Error creating the blog", dfe);
            return null;
        } catch (IOException ioe) {
            _log.error("Error creating the blog", ioe);
            return null;
        }
        
        return createInfo(pub, priv, name, posters, description, contactURL, archives, 0);
    }
    
    public BlogInfo createInfo(SigningPublicKey pub, SigningPrivateKey priv, String name, SigningPublicKey posters[], 
                               String description, String contactURL, String archives[], int edition) {
        Properties opts = new Properties();
        if (name == null) name = "";
        opts.setProperty("Name", name);
        if (description == null) description = "";
        opts.setProperty("Description", description);
        opts.setProperty("Edition", Integer.toString(edition));
        if (contactURL == null) contactURL = "";
        opts.setProperty("ContactURL", contactURL);
        for (int i = 0; archives != null && i < archives.length; i++) 
            opts.setProperty("Archive." + i, archives[i]);
        
        BlogInfo info = new BlogInfo(pub, posters, opts);
        info.sign(_context, priv);
        
        _archive.storeBlogInfo(info);
        
        return info;
    }
    
    public boolean updateMetadata(User user, Hash blog, Properties opts) {
        if (!user.getAuthenticated()) return false;
        BlogInfo oldInfo = getArchive().getBlogInfo(blog);
        if (oldInfo == null) return false;
        if (!user.getBlog().equals(oldInfo.getKey().calculateHash())) return false;
        int oldEdition = 0;
        try { 
            String ed = oldInfo.getProperty("Edition");
            if (ed != null)
                oldEdition = Integer.parseInt(ed);
        } catch (NumberFormatException nfe) {}
        opts.setProperty("Edition", oldEdition + 1 + "");
        BlogInfo info = new BlogInfo(oldInfo.getKey(), oldInfo.getPosters(), opts);
        SigningPrivateKey key = getMyPrivateKey(oldInfo);
        info.sign(_context, key);
        getArchive().storeBlogInfo(info);
        user.setLastMetaEntry(oldEdition+1);
        saveUser(user);
        return true;
    }
    
    public Archive getArchive() { return _archive; }
    public File getTempDir() { return _tempDir; }
    public File getRootDir() { return _rootDir; }
    
    public List listMyBlogs() {
        File files[] = _privKeyDir.listFiles();
        List rv = new ArrayList();
        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile() && !files[i].isHidden()) {
                try {
                    SigningPublicKey pub = new SigningPublicKey();
                    pub.readBytes(new FileInputStream(files[i]));
                    BlogInfo info = _archive.getBlogInfo(pub.calculateHash());
                    if (info != null)
                        rv.add(info);
                } catch (IOException ioe) {
                    _log.error("Error listing the blog", ioe);
                } catch (DataFormatException dfe) {
                    _log.error("Error listing the blog", dfe);
                }
            }
        }
        return rv;
    }
    
    public SigningPrivateKey getMyPrivateKey(BlogInfo blog) {
        if (blog == null) return null;
        File keyFile = new File(_privKeyDir, Base64.encode(blog.getKey().calculateHash().getData()) + ".priv");
        try {
            FileInputStream in = new FileInputStream(keyFile);
            SigningPublicKey pub = new SigningPublicKey();
            pub.readBytes(in);
            SigningPrivateKey priv = new SigningPrivateKey();
            priv.readBytes(in);
            return priv;
        } catch (IOException ioe) {
            _log.error("Error reading the blog key", ioe);
            return null;
        } catch (DataFormatException dfe) {
            _log.error("Error reading the blog key", dfe);
            return null;
        }
    }
    
    public User getUser(Hash blog) {
        File files[] = _userDir.listFiles();
        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile() && !files[i].isHidden()) {
                Properties userProps = loadUserProps(files[i]);
                if (userProps == null)
                    continue;
                User user = new User(_context);
                user.load(userProps);
                if (blog.equals(user.getBlog()))
                    return user;
            }
        }
        return null;
    }
    
    /**
     * List of User instances
     */
    public List listUsers() {
        File files[] = _userDir.listFiles();
        List rv = new ArrayList();
        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile() && !files[i].isHidden()) {
                Properties userProps = loadUserProps(files[i]);
                if (userProps == null)
                    continue;
                User user = new User(_context);
                user.load(userProps);
                rv.add(user);
            }
        }
        return rv;
    }
    
    private Properties loadUserProps(File userFile) {
        try {
            Properties props = new Properties();
            FileInputStream fin = new FileInputStream(userFile);
            BufferedReader in = new BufferedReader(new InputStreamReader(fin, "UTF-8"));
            String line = null;
            while ( (line = in.readLine()) != null) {
                int split = line.indexOf('=');
                if (split <= 0) continue;
                String key = line.substring(0, split);
                String val = line.substring(split+1);
                props.setProperty(key.trim(), val.trim());
            }
            String userHash = userFile.getName();
            props.setProperty(User.PROP_USERHASH, userHash);
            return props;
        } catch (IOException ioe) {
            return null;
        }
    }
    
    public boolean changePasswrd(User user, String oldPass, String pass0, String pass1) {
        boolean ok = user.changePassword(oldPass, pass0, pass1);
        if (ok)
            saveUser(user);
        return ok;
    }

    
    public User login(String login, String pass) {
        User u = new User(_context);
        String ok = login(u, login, pass);
        if (User.LOGIN_OK.equals(ok))
            return u;
        else
            return new User(_context);
    }
    
    public String login(User user, String login, String pass) {
        if ( (login == null) || (pass == null) ) return "<span class=\"b_loginMsgErr\">Login not specified</span>";
        Hash userHash = _context.sha().calculateHash(DataHelper.getUTF8(login));
        Hash passHash = _context.sha().calculateHash(DataHelper.getUTF8(pass));
        File userFile = new File(_userDir, Base64.encode(userHash.getData()));
        if (_log.shouldLog(Log.INFO))
            _log.info("Attempting to login to " + login + " w/ pass = " + pass 
                           + ": file = " + userFile.getAbsolutePath() + " passHash = "
                           + Base64.encode(passHash.getData()));
        if (userFile.exists()) {
            try {
                Properties props = loadUserProps(userFile);
                if (props == null) throw new IOException("Error reading " + userFile);
                String rv = user.login(login, pass, props);
                if (User.LOGIN_OK.equals(rv))
                    _log.info("Login successful");
                else
                    _log.info("Login failed: [" + rv + "]");
                return rv;
            } catch (IOException ioe) {
                _log.error("Error logging in", ioe);
                return "<span class=\"b_loginMsgErr\">Error logging in - corrupt userfile</span>";
            }
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("User does not exist");
            return "<span class=\"b_loginMsgErr\">User does not exist</span>";
        }
    }
    
    /** hash of the password required to register and create a new blog (null means no password required) */
    public String getRegistrationPasswordHash() { 
        String pass = _context.getProperty("syndie.registrationPassword");
        if ( (pass == null) || (pass.trim().length() <= 0) ) return null;
        return pass; 
    }
    
    /** Password required to access the remote syndication functinoality (null means no password required) */
    public String getRemotePasswordHash() { 
        String pass = _context.getProperty("syndie.remotePassword");
        
        if ( (pass == null) || (pass.trim().length() <= 0) ) return null;
        return pass;
    }
    public String getAdminPasswordHash() { 
        String pass = _context.getProperty("syndie.adminPassword");
        if ( (pass == null) || (pass.trim().length() <= 0) ) return "";
        return pass;
    }
    
    public boolean isConfigured() {
        String p = _context.getProperty("syndie.singleUser");
        if(p==null)
            return false;
        return true;
    }
    
    private static final boolean DEFAULT_IS_SINGLEUSER = true;
    
    /**
     * If true, this syndie instance is meant for just one local user, so we don't need
     * to password protect registration, remote.jsp, or admin.jsp
     *
     */
    public boolean isSingleUser() {
        if (!isConfigured()) return DEFAULT_IS_SINGLEUSER;
        String isSingle = _context.getProperty("syndie.singleUser");
        return ( (isSingle != null) && (Boolean.valueOf(isSingle).booleanValue()) );
    }

    public String getDefaultProxyHost() { return _context.getProperty("syndie.defaultProxyHost", "localhost"); }
    public String getDefaultProxyPort() { return _context.getProperty("syndie.defaultProxyPort", "4444"); }
    public String[] getUpdateArchives() { 
        String str = _context.getProperty("syndie.updateArchives", "");
        if ( (str != null) && (str.trim().length() > 0) )
            return str.split(",");
        else
            return new String[0];
    }
    public boolean getImportAddresses() { return _context.getProperty("syndie.importAddresses", "false").equals("true"); }
    public int getUpdateDelay() { 
        int delay = Integer.parseInt(_context.getProperty("syndie.updateDelay", "12"));
        if (delay < 1) delay = 1;
        return delay;
    }
    
    public List getRssFeeds() {
        List feedList = new ArrayList();
        int i=0;
        while(true) {
            String url = _context.getProperty("syndie.rssFeed."+i+".url");
            String blog = _context.getProperty("syndie.rssFeed."+i+".blog");
            String tagPrefix = _context.getProperty("syndie.rssFeed."+i+".tagPrefix");
            if(url==null || blog==null || tagPrefix==null)
                break;
            String feed[] = new String[3];
            feed[0]=url.trim();
            feed[1]=blog.trim();
            feed[2]=tagPrefix.trim();
            feedList.add(feed);
            i++;
        }
        return feedList;
    }
    public boolean addRssFeed(String url, String blog, String tagPrefix) {
        
        List feedList = getRssFeeds();
        int nextIdx=feedList.size();
        
        String baseFeedProp="syndie.rssFeed."+nextIdx;
        System.setProperty(baseFeedProp+".url",url);
        System.setProperty(baseFeedProp+".blog",blog);
        System.setProperty(baseFeedProp+".tagPrefix",tagPrefix);
        _log.info("addRssFeed("+nextIdx+"): "+url);
        writeConfig();
        Updater.wakeup();
        return true;
    }
    public boolean deleteRssFeed(String url, String blog, String tagPrefix) {
        List feedList = getRssFeeds();
        Iterator iter = feedList.iterator();
        int idx=0;
        while(iter.hasNext()) {
            String fields[] = (String[])iter.next();
            if(fields[0].equals(url) &&
               fields[1].equals(blog) &&
               fields[2].equals(tagPrefix)) {
                break;
            }
            idx++;
        }
        
        // copy any remaining to idx-1
        while(iter.hasNext()) {
            String fields[] = (String[])iter.next();
            String baseFeedProp="syndie.rssFeed."+idx;
            System.setProperty(baseFeedProp+".url",fields[0]);
            System.setProperty(baseFeedProp+".blog",fields[1]);
            System.setProperty(baseFeedProp+".tagPrefix",fields[2]);
            idx++;
        }
        
        // Delete last idx from properties
        String baseFeedProp="syndie.rssFeed."+idx;
        System.getProperties().remove(baseFeedProp+".url");
        System.getProperties().remove(baseFeedProp+".blog");
        System.getProperties().remove(baseFeedProp+".tagPrefix");
        _log.info("deleteRssFeed("+idx+"): "+url);
        writeConfig();
        return true;
    }
     
    private static final String DEFAULT_LOGIN = "default";
    private static final String DEFAULT_PASS = "";
    
    private static final String PROP_DEFAULT_LOGIN = "syndie.defaultSingleUserLogin";
    private static final String PROP_DEFAULT_PASS = "syndie.defaultSingleUserPass";
    
    public String getDefaultLogin() {
        String login = _context.getProperty(PROP_DEFAULT_LOGIN);
        if ( (login == null) || (login.trim().length() <= 0) )
            login = DEFAULT_LOGIN;
        return login;
    }
    public String getDefaultPass() {
        String pass = _context.getProperty(PROP_DEFAULT_PASS);
        if ( (pass == null) || (pass.trim().length() <= 0) )
            pass = DEFAULT_PASS;
        return pass;
    }

    /**
     * If we are a single user instance, when we create the default user, give them
     * addressbook entries for each of the following, *and* schedule them for syndication
     *
     */
    private static final String DEFAULT_SINGLE_USER_ARCHIVES[] = new String[] {
        "http://syndiemedia.i2p/archive/archive.txt"
        , "http://gloinsblog.i2p/archive/archive.txt"
        , "http://glog.i2p/archive/archive.txt"
    };
    
    public User getDefaultUser() {
        User user = new User(_context);
        getDefaultUser(user);
        return user;
    }
    public void getDefaultUser(User user) {
        if (isSingleUser()) {
            Hash userHash = _context.sha().calculateHash(DataHelper.getUTF8(getDefaultLogin()));
            File userFile = new File(_userDir, Base64.encode(userHash.getData()));
            if (_log.shouldLog(Log.INFO))
                _log.info("Attempting to login to the default user: " + userFile.getAbsolutePath());
            
            if (userFile.exists()) {
                Properties props = loadUserProps(userFile);
                if (props == null) {
                    user.invalidate();
                    _log.error("Error reading the default user file: " + userFile);
                    return;
                }
                String ok = user.login(getDefaultLogin(), getDefaultPass(), props);
                if (User.LOGIN_OK.equals(ok)) {
                    return;
                } else {
                    user.invalidate();
                    _log.error("Error logging into the default user: " + ok);
                    return;
                }
            } else {
                String ok = register(user, getDefaultLogin(), getDefaultPass(), "", "default", "Default Syndie blog", "");
                if (User.LOGIN_OK.equals(ok)) {
                    _log.info("Default user created: " + user);
                    for (int i = 0; i < DEFAULT_SINGLE_USER_ARCHIVES.length; i++)
                        user.getPetNameDB().add(new PetName("DefaultArchive" + i, "syndie", "syndiearchive", DEFAULT_SINGLE_USER_ARCHIVES[i]));
                    scheduleSyndication(DEFAULT_SINGLE_USER_ARCHIVES);
                    saveUser(user);
                    return;
                } else {
                    user.invalidate();
                    _log.error("Error registering the default user: " + ok);
                    return;
                }
            }
        } else {
            return;
        }
    }
    
    public boolean authorizeAdmin(String pass) {
        if (isSingleUser()) return true;
        String admin = getAdminPasswordHash();
        if ( (admin == null) || (admin.trim().length() <= 0) )
            return false;
        String hash = Base64.encode(_context.sha().calculateHash(DataHelper.getUTF8(pass.trim())).getData());
        return (hash.equals(admin));
    }
    public boolean authorizeRemote(String pass) {
        if (isSingleUser()) return true;
        String rem = getRemotePasswordHash();
        if ( (rem == null) || (rem.trim().length() <= 0) )
            return false;
        String hash = Base64.encode(_context.sha().calculateHash(DataHelper.getUTF8(pass.trim())).getData());
        return (hash.equals(rem));
    }
    public boolean authorizeRemote(User user) {
        if (isSingleUser()) return true;
        return (user.getAuthenticated() && user.getAllowAccessRemote());
    }
    
    public void configure(String registrationPassword, String remotePassword, String adminPass, String defaultSelector, 
                          String defaultProxyHost, int defaultProxyPort, boolean isSingleUser, Properties opts,
                          String defaultUser, String defaultPass) {
        File cfg = getConfigFile();
        Writer out = null;
        try {
            out = new OutputStreamWriter(new FileOutputStream(cfg), "UTF-8");
            if (registrationPassword != null)
                out.write("syndie.registrationPassword="+Base64.encode(_context.sha().calculateHash(DataHelper.getUTF8(registrationPassword.trim())).getData()) + "\n");
            if (remotePassword != null)
                out.write("syndie.remotePassword="+Base64.encode(_context.sha().calculateHash(DataHelper.getUTF8(remotePassword.trim())).getData()) + "\n");
            if (adminPass != null)
                out.write("syndie.adminPassword="+Base64.encode(_context.sha().calculateHash(DataHelper.getUTF8(adminPass.trim())).getData()) + "\n");
            if (defaultSelector != null)
                out.write("syndie.defaultSelector="+defaultSelector.trim() + "\n");
            if (defaultProxyHost != null)
                out.write("syndie.defaultProxyHost="+defaultProxyHost.trim() + "\n");
            if (defaultProxyPort > 0)
                out.write("syndie.defaultProxyPort="+defaultProxyPort + "\n");
            
            if ( (defaultUser == null) || (defaultUser.length() <= 0) )
                defaultUser = getDefaultLogin();
            if (defaultPass == null)
                defaultPass = getDefaultPass();
            out.write("syndie.defaultSingleUserLogin="+defaultUser+"\n");
            out.write("syndie.defaultSingleUserPass="+defaultPass+"\n");
            
            out.write("syndie.singleUser=" + isSingleUser + "\n"); // Used also in isConfigured()
            if (opts != null) {
                for (Iterator iter = opts.keySet().iterator(); iter.hasNext(); ) {
                    String key = (String)iter.next();
                    String val = opts.getProperty(key);
                    out.write(key.trim() + "=" + val.trim() + "\n");
                }
            }
            _archive.setDefaultSelector(defaultSelector);
        } catch (IOException ioe) {
            _log.error("Error writing out the config", ioe);
        } finally {
            if (out != null) try { out.close(); } catch (IOException ioe) {}
            readConfig();
        }
    }
    
    public String authorizeRemoteAccess(User user, String password) {
        if (!user.getAuthenticated()) return "<span class=\"b_remoteMsgErr\">Not logged in</span>";
        String remPass = getRemotePasswordHash();
        if (remPass == null)
            return "<span class=\"b_remoteMsgErr\">Remote access password not configured - please <a href=\"admin.jsp\">specify</a> a remote " +
                   "archive password</span>";
        
        if (authorizeRemote(password)) {
            user.setAllowAccessRemote(true);
            saveUser(user);
            return "<span class=\"b_remoteMsgOk\">Remote access authorized</span>";
        } else {
            return "<span class=\"b_remoteMsgErr\">Remote access denied</span>";
        }
    }
    
    /**
     * Store user info, regardless of whether they're logged in.  This lets you update a
     * different user's info!
     */
    void storeUser(User user) {
        String userHash = user.getUserHash();
        File userFile = new File(_userDir, userHash);
        if (!userFile.exists()) return;
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(userFile);
            out.write(DataHelper.getUTF8(user.export()));
            user.getPetNameDB().store(user.getAddressbookLocation());
        } catch (IOException ioe) {
            _log.error("Error writing out the user", ioe);
        } finally {
            if (out != null) try { out.close(); } catch (IOException ioe){}
        }
    }
    
    public void saveUser(User user) {
        if (!user.getAuthenticated()) return;
        storeUser(user);
    }
    public User register(String login, String password, String registrationPassword, String blogName, String blogDescription, String contactURL) {
        User user = new User(_context);
        if (User.LOGIN_OK.equals(register(user, login, password, registrationPassword, blogName, blogDescription, contactURL)))
            return user;
        else
            return null;
    }
    public String register(User user, String login, String password, String registrationPassword, String blogName, String blogDescription, String contactURL) {
        System.err.println("Register [" + login + "] pass [" + password + "] name [" + blogName + "] descr [" + blogDescription + "] contact [" + contactURL + "] regPass [" + registrationPassword + "]");
        String hashedRegistrationPassword = getRegistrationPasswordHash();
        if ( (hashedRegistrationPassword != null) && (!isSingleUser()) ) {
            try {
                if (!hashedRegistrationPassword.equals(Base64.encode(_context.sha().calculateHash(registrationPassword.getBytes("UTF-8")).getData())))
                    return "<span class=\"b_regMsgErr\">Invalid registration password</span>";
            } catch (UnsupportedEncodingException uee) {
                return "<span class=\"b_regMsgErr\">Error registering</span>";
            }
        }
        String userHash = Base64.encode(_context.sha().calculateHash(DataHelper.getUTF8(login)).getData());
        File userFile = new File(_userDir, userHash);
        if (userFile.exists()) {
            return "<span class=\"b_regMsgErr\">Cannot register the login " + login + ": it already exists</span>";
        } else {
            BlogInfo info = createBlog(blogName, blogDescription, contactURL, null);
            String hashedPassword = Base64.encode(_context.sha().calculateHash(DataHelper.getUTF8(password)).getData());
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(userFile);
                BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(out, "UTF-8"));
                bw.write("password=" + hashedPassword + "\n");
                bw.write("blog=" + Base64.encode(info.getKey().calculateHash().getData()) + "\n");
                bw.write("lastid=-1\n");
                bw.write("lastmetaedition=0\n");
                bw.write("addressbook=userhosts-"+userHash + ".txt\n");
                bw.write("showimages=false\n");
                bw.write("showexpanded=false\n");
                bw.flush();
            } catch (IOException ioe) {
                _log.error("Error registering the user", ioe);
                return "<span class=\"b_regMsgErr\">Internal error registering - " + ioe.getMessage() + "</span>";
            } finally {
                if (out != null) try { out.close(); } catch (IOException ioe) {}
            }
            String loginResult = login(user, login, password);
            _archive.regenerateIndex();
            return loginResult;
        }
    }

    public String exportHosts(User user) {
        if (!user.getAuthenticated() || !user.getAllowAccessRemote())
            return "<span class=\"b_addrMsgErr\">Not authorized to export the hosts</span>";
        PetNameDB userDb = user.getPetNameDB();
        PetNameDB routerDb = _context.petnameDb();
        // horribly inefficient...
        for (Iterator iter = userDb.iterator(); iter.hasNext();) {
            PetName pn = (PetName)iter.next();
            if (pn == null) continue;
            Destination existing = _context.namingService().lookup(pn.getName());
            if (existing == null && pn.getNetwork().equalsIgnoreCase("i2p")) {
                routerDb.add(pn);
                try {
                    routerDb.store();
                } catch (IOException ioe) {
                    _log.error("Error exporting the hosts", ioe);
                    return "<span class=\"b_addrMsgErr\">Error exporting the hosts: " + ioe.getMessage() + "</span>";
                }
            }
        }
        return "<span class=\"b_addrMsgOk\">Hosts exported</span>";
    }
    
    /**
     * Guess what the next entry ID should be for the given user.  Rounds down to 
     * midnight of the current day + 1 for each post in that day.
     */
    public long getNextBlogEntry(User user) {
        long entryId = -1;
        long now = _context.clock().now();
        long dayBegin = getDayBegin(now);
        if (user.getMostRecentEntry() >= dayBegin)
            entryId = user.getMostRecentEntry() + 1;
        else
            entryId = dayBegin;
        return entryId;
    }
    
    public BlogURI createBlogEntry(User user, String subject, String tags, String entryHeaders, String sml) {
        return createBlogEntry(user, true, subject, tags, entryHeaders, sml, null, null, null);
    }
    public BlogURI createBlogEntry(User user, String subject, String tags, String entryHeaders, String sml, List fileNames, List fileStreams, List fileTypes) {
        return createBlogEntry(user, true, subject, tags, entryHeaders, sml, fileNames, fileStreams, fileTypes);        
    }
    public BlogURI createBlogEntry(User user, boolean shouldAuthenticate, String subject, String tags, String entryHeaders, String sml, List fileNames, List fileStreams, List fileTypes) {
        if (shouldAuthenticate && !user.getAuthenticated()) return null;
        BlogInfo info = getArchive().getBlogInfo(user.getBlog());
        if (info == null) return null;
        SigningPrivateKey privkey = getMyPrivateKey(info);
        if (privkey == null) return null;
        
        long entryId = getNextBlogEntry(user);
        
        StringTokenizer tok = new StringTokenizer(tags, " ,\n\t");
        String tagList[] = new String[tok.countTokens()];
        for (int i = 0; i < tagList.length; i++) 
            tagList[i] = tok.nextToken().trim();
        
        BlogURI uri = new BlogURI(user.getBlog(), entryId);
        
        try {
            StringBuffer raw = new StringBuffer(sml.length() + 128);
            raw.append("Subject: ").append(subject).append('\n');
            raw.append("Tags: ");
            for (int i = 0; i < tagList.length; i++) 
                raw.append(tagList[i]).append('\t');
            raw.append('\n');
            if ( (entryHeaders != null) && (entryHeaders.trim().length() > 0) ) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Creating entry with headers: " + entryHeaders);
                BufferedReader userHeaders = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(DataHelper.getUTF8(entryHeaders)), "UTF-8"));
                String line = null;
                while ( (line = userHeaders.readLine()) != null) {
                    line = line.trim();
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("header line: " + line);
                    if (line.length() <= 0) continue;
                    int split = line.indexOf('=');
                    int split2 = line.indexOf(':');
                    if ( (split < 0) || ( (split2 > 0) && (split2 < split) ) ) split = split2;
                    if ( (split < 0) && (split2 < 0) )
                        continue;
                    String key = line.substring(0,split).trim();
                    String val = line.substring(split+1).trim();
                    raw.append(key).append(": ").append(val).append('\n');
                }
            }
            raw.append('\n');
            raw.append(sml);
            
            EntryContainer c = new EntryContainer(uri, tagList, DataHelper.getUTF8(raw));
            if ((fileNames != null) && (fileStreams != null) && (fileNames.size() == fileStreams.size()) ) {
                for (int i = 0; i < fileNames.size(); i++) {
                    String name = (String)fileNames.get(i);
                    InputStream in = (InputStream)fileStreams.get(i);
                    String fileType = (fileTypes != null ? (String)fileTypes.get(i) : "application/octet-stream");
                    ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
                    byte buf[] = new byte[1024];
                    while (true) {
                        int read = in.read(buf);
                        if (read == -1) break;
                        baos.write(buf, 0, read);
                    }
                    byte att[] = baos.toByteArray();
                    if ( (att != null) && (att.length > 0) )
                        c.addAttachment(att, new File(name).getName(), null, fileType);
                }
            }
            //for (int i = 7; i < args.length; i++) {
            //    c.addAttachment(read(args[i]), new File(args[i]).getName(), 
            //                    "Attached file", "application/octet-stream");
            //}
            SessionKey entryKey = null;
            //if (!"NONE".equals(args[5]))
            //    entryKey = new SessionKey(Base64.decode(args[5]));
            c.seal(_context, privkey, null);
            boolean ok = getArchive().storeEntry(c);
            if (ok) {
                getArchive().regenerateIndex();
                user.setMostRecentEntry(entryId);
                if(shouldAuthenticate)
                    saveUser(user);
                else
                    storeUser(user);
                return uri;
            } else {
                return null;
            }
        } catch (IOException ioe) {
            _log.error("Error creating post", ioe);
            return null;
        }
    }
    
    /** 
     * read in the syndie blog metadata file from the stream, verifying it and adding it to 
     * the archive if necessary
     *
     */
    public boolean importBlogMetadata(InputStream metadataStream) throws IOException {
        try {
            BlogInfo info = new BlogInfo();
            info.load(metadataStream);
            return _archive.storeBlogInfo(info);
        } catch (IOException ioe) {
            _log.error("Error importing meta", ioe);
            return false;
        }
    }
    
    /** 
     * read in the syndie entry file from the stream, verifying it and adding it to 
     * the archive if necessary
     *
     */
    public boolean importBlogEntry(InputStream entryStream) throws IOException {
        try {
            EntryContainer c = new EntryContainer();
            c.load(entryStream);
            return _archive.storeEntry(c);
        } catch (IOException ioe) {
            _log.error("Error importing entry", ioe);
            return false;
        }
    }

    public String addAddress(User user, String name, String protocol, String location, String schema) {
        if (!user.getAuthenticated()) return "<span class=\"b_addrMsgErr\">Not logged in</span>";
        boolean ok = validateAddressName(name);
        if (!ok) return "<span class=\"b_addrMsgErr\">Invalid name: " + HTMLRenderer.sanitizeString(name) + "</span>";
        ok = validateAddressLocation(location);
        if (!ok) return "<span class=\"b_addrMsgErr\">Invalid location: " + HTMLRenderer.sanitizeString(location) + "</span>";
        if (!validateAddressSchema(schema)) return "<span class=\"b_addrMsgErr\">Unsupported schema: " + HTMLRenderer.sanitizeString(schema) + "</span>";
        // no need to quote user/location further, as they've been sanitized
        
        PetNameDB names = user.getPetNameDB();
        if (names.containsName(name))
            return "<span class=\"b_addrMsgErr\">Name is already in use</span>";
        PetName pn = new PetName(name, schema, protocol, location);
        names.add(pn);
        
        try {
            names.store(user.getAddressbookLocation());
            return "<span class=\"b_addrMsgOk\">Address " + name + " written to your addressbook</span>";
        } catch (IOException ioe) {
            return "<span class=\"b_addrMsgErr\">Error writing out the name: " + ioe.getMessage() + "</span>";
        }
    }
    
    public Properties getKnownHosts(User user, boolean includePublic) throws IOException {
        Properties rv = new Properties();
        if ( (user != null) && (user.getAuthenticated()) ) {
            File userHostsFile = new File(user.getAddressbookLocation());
            rv.putAll(getKnownHosts(userHostsFile));
        }
        if (includePublic) {
            rv.putAll(getKnownHosts(new File("hosts.txt")));
        }
        return rv;
    }
    private Properties getKnownHosts(File filename) throws IOException {
        Properties rv = new Properties();
        if (filename.exists()) {
            rv.load(new FileInputStream(filename));
        }
        return rv;
    }
    
    private boolean validateAddressName(String name) {
        if ( (name == null) || (name.trim().length() <= 0) ) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && ('.' != c) && ('-' != c) && ('_' != c) )
                return false;
        }
        return true;
    }

    private boolean validateAddressLocation(String location) {
        if ( (location == null) || (location.trim().length() <= 0) ) return false;
        if (false) {
            try {
                Destination d = new Destination(location);
                return (d.getPublicKey() != null);
            } catch (DataFormatException dfe) {
                _log.error("Error validating address location", dfe);
                return false;
            }
        } else {
            // not everything is an i2p destination...
            return true;
        }
    }

    private boolean validateAddressSchema(String schema) {
        if ( (schema == null) || (schema.trim().length() <= 0) ) return false;
        if (true) {
            return true;
        } else {
            return "eep".equals(schema) || "i2p".equals(schema);
        }
    }

    private final SimpleDateFormat _dateFormat = new SimpleDateFormat("yyyy/MM/dd", Locale.UK);    
    private final long getDayBegin(long now) {
        synchronized (_dateFormat) {
            try {
                String str = _dateFormat.format(new Date(now));
                return _dateFormat.parse(str).getTime();
            } catch (ParseException pe) {
                pe.printStackTrace();
                // wtf
                return -1;
            }
        }
    }
    
    public void scheduleSyndication(String location) {
        String archives[] = getUpdateArchives();
        StringBuffer buf = new StringBuffer(64);
        if ( (archives != null) && (archives.length > 0) ) {
            for (int i = 0; i < archives.length; i++)
                if ( (!archives[i].equals(location)) && (archives[i].trim().length() > 0) )
                    buf.append(archives[i]).append(",");
        }
        if ( (location != null) && (location.trim().length() > 0) )
            buf.append(location.trim());
        System.setProperty("syndie.updateArchives", buf.toString());
        Updater.wakeup();
    }
    public void scheduleSyndication(String locations[]) {
        String archives[] = getUpdateArchives();
        HashSet locs = new HashSet();
        for (int i = 0; (archives != null) && (i < archives.length); i++)
            locs.add(archives[i]);
        for (int i = 0; (locations != null) && (i < locations.length); i++)
            locs.add(locations[i]);
        
        StringBuffer buf = new StringBuffer(64);
        for (Iterator iter = locs.iterator(); iter.hasNext(); )
            buf.append(iter.next().toString().trim()).append(',');
        System.setProperty("syndie.updateArchives", buf.toString());
        Updater.wakeup();
    }
    public void unscheduleSyndication(String location) {
        String archives[] = getUpdateArchives();
        if ( (archives != null) && (archives.length > 0) ) {
            StringBuffer buf = new StringBuffer(64);
            for (int i = 0; i < archives.length; i++)
                if ( (!archives[i].equals(location)) && (archives[i].trim().length() > 0) )
                    buf.append(archives[i]).append(",");
            System.setProperty("syndie.updateArchives", buf.toString());
        }
    }
    public boolean syndicationScheduled(String location) {
        String archives[] = getUpdateArchives();
        if ( (location == null) || (archives == null) || (archives.length <= 0) )
            return false;
        for (int i = 0; i < archives.length; i++)
            if (location.equals(archives[i]))
                return true;
        return false;
    }
}
