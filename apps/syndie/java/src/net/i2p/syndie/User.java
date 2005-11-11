package net.i2p.syndie;

import java.io.UnsupportedEncodingException;
import java.io.IOException;
import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.client.naming.PetNameDB;
import net.i2p.data.*;

/**
 * User session state and preferences.
 *
 */
public class User {
    private I2PAppContext _context;
    private String _username;
    private String _hashedPassword;
    private Hash _blog;
    private String _userHash;
    private long _mostRecentEntry;
    /** Group name to List of blog selectors, where the selectors are of the form
     * blog://$key, entry://$key/$entryId, blogtag://$key/$tag, tag://$tag
     */
    private Map _blogGroups;
    /** list of blogs (Hash) we never want to see entries from */
    private List _shitlistedBlogs;
    /** where our userhosts.txt is */
    private String _addressbookLocation;
    private boolean _showImagesByDefault;
    private boolean _showExpandedByDefault;
    private String _defaultSelector;
    private long _lastLogin;
    private long _lastMetaEntry;
    private boolean _allowAccessRemote;
    private boolean _authenticated;
    private String _eepProxyHost;
    private int _eepProxyPort;
    private String _webProxyHost;
    private int _webProxyPort;
    private String _torProxyHost;
    private int _torProxyPort;
    private PetNameDB _petnames;
    private boolean _importAddresses;

    static final String PROP_USERHASH = "__userHash";

    /**
     * Ugly hack to fetch the default User instance - this is the default
     * constructor so it can be used as a bean on the web interface.  If
     * the Syndie instance isn't in single user mode, the default User 
     * is an empty unauthenticated User.  If the instance IS in single user
     * mode, this will contain the logged in 'default' user (creating a new
     * one as necessary).  If you just want to create a User object, use the
     * new User(I2PAppContext ctx) constructor.
     *
     */
    public User() {
        this(I2PAppContext.getGlobalContext());
        BlogManager.instance().getDefaultUser(this);
    }
    
    public User(I2PAppContext ctx) {
        _context = ctx;
        init();
    }
    private void init() {
        _authenticated = false;
        _username = null;
        _userHash = null;
        _hashedPassword = null;
        _blog = null;
        _mostRecentEntry = -1;
        _blogGroups = new HashMap();
        _shitlistedBlogs = new ArrayList();
        _defaultSelector = null;
        _addressbookLocation = "userhosts.txt";
        _showImagesByDefault = true;
        _showExpandedByDefault = false;
        _allowAccessRemote = false;
        _eepProxyHost = null;
        _webProxyHost = null;
        _torProxyHost = null;
        _eepProxyPort = -1;
        _webProxyPort = -1;
        _torProxyPort = -1;
        _lastLogin = -1;
        _lastMetaEntry = 0;
        _petnames = new PetNameDB();
        _importAddresses = false;
    }
    
    public boolean getAuthenticated() { return _authenticated; }
    public String getUsername() { return _username; }
    public String getUserHash() { return _userHash; }
    public Hash getBlog() { return _blog; }
    public String getBlogStr() { return Base64.encode(_blog.getData()); }
    public long getMostRecentEntry() { return _mostRecentEntry; }
    public Map getBlogGroups() { return _blogGroups; }
    public List getShitlistedBlogs() { return _shitlistedBlogs; }
    public String getAddressbookLocation() { return _addressbookLocation; }
    public boolean getShowImages() { return _showImagesByDefault; }
    public boolean getShowExpanded() { return _showExpandedByDefault; }
    public long getLastLogin() { return _lastLogin; }
    public String getHashedPassword() { return _hashedPassword; }
    public long getLastMetaEntry() { return _lastMetaEntry; }
    public String getDefaultSelector() { return _defaultSelector; }
    public void setDefaultSelector(String sel) { _defaultSelector = sel; }
    public boolean getAllowAccessRemote() { return _allowAccessRemote; }
    public void setAllowAccessRemote(boolean allow) { _allowAccessRemote = true; }
    
    public void setMostRecentEntry(long id) { _mostRecentEntry = id; }
    public void setLastMetaEntry(long id) { _lastMetaEntry = id; }

    public String getEepProxyHost() { return _eepProxyHost; }
    public int getEepProxyPort() { return _eepProxyPort; }
    public String getWebProxyHost() { return _webProxyHost; }
    public int getWebProxyPort() { return _webProxyPort; }
    public String getTorProxyHost() { return _torProxyHost; }
    public int getTorProxyPort() { return _torProxyPort; }
    
    public PetNameDB getPetNameDB() { return _petnames; }
    public boolean getImportAddresses() { return _importAddresses; }
    
    public void invalidate() { 
        if (_authenticated)
            BlogManager.instance().saveUser(this);
        init(); 
    }
    
    public String login(String login, String pass, Properties props) {
        _username = login;
        load(props);
        String hpass = Base64.encode(_context.sha().calculateHash(DataHelper.getUTF8(pass)).getData());
        if (!hpass.equals(_hashedPassword)) {
            return "<span class=\"b_loginMsgErr\">Incorrect password</span>";
        }
        _lastLogin = _context.clock().now();
        _authenticated = true;
        return LOGIN_OK;
    }
    
    
    public void load(Properties props) {
        _authenticated = false;
        _hashedPassword = props.getProperty("password");
        _userHash = props.getProperty(PROP_USERHASH);
        
        // blog=luS9d3uaf....HwAE=
        String b = props.getProperty("blog");
        if (b != null) _blog = new Hash(Base64.decode(b));
        // lastid=12345
        String id = props.getProperty("lastid");
        if (id != null) try { _mostRecentEntry = Long.parseLong(id); } catch (NumberFormatException nfe) {}
        // lastmetaedition=12345
        id = props.getProperty("lastmetaedition");
        if (id != null) try { _lastMetaEntry = Long.parseLong(id); } catch (NumberFormatException nfe) {}
        // groups=abc:selector,selector,selector,selector def:selector,selector,selector
        StringTokenizer tok = new StringTokenizer(props.getProperty("groups", ""), " ");
        while (tok.hasMoreTokens()) {
            String group = tok.nextToken();
            int endName = group.indexOf(':');
            if (endName <= 0)
                continue;
            String groupName = group.substring(0, endName);
            String sel = group.substring(endName+1);
            List selectors = new ArrayList();
            while ( (sel != null) && (sel.length() > 0) ) {
                int end = sel.indexOf(',');
                if (end < 0) {
                    selectors.add(sel);
                    sel = null;
                } else {
                    if (end + 1 >= sel.length()) {
                        selectors.add(sel.substring(0,end));
                        sel = null;
                    } else if (end == 0) {
                        sel = sel.substring(1);
                    } else {
                        selectors.add(sel.substring(0, end));
                        sel = sel.substring(end+1);
                    }
                }
            }
            _blogGroups.put(groupName.trim(), selectors);
        }
        // shitlist=hash,hash,hash
        tok = new StringTokenizer(props.getProperty("shitlistedblogs", ""), ",");
        while (tok.hasMoreTokens()) {
            String blog = tok.nextToken();
            byte bl[] = Base64.decode(blog);
            if ( (bl != null) && (bl.length == Hash.HASH_LENGTH) )
                _shitlistedBlogs.add(new Hash(bl));
        }
        
        String addr = props.getProperty("addressbook", "userhosts.txt");
        if (addr != null) {
            _addressbookLocation = addr;
            try {
                _petnames.load(addr);
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        }
        
        String show = props.getProperty("showimages", "true");
        _showImagesByDefault = (show != null) && (show.equals("true"));
        show = props.getProperty("showexpanded", "false");
        _showExpandedByDefault = (show != null) && (show.equals("true"));
        _defaultSelector = props.getProperty("defaultselector");
        String allow = props.getProperty("allowaccessremote", "false");
        _allowAccessRemote = (allow != null) && (allow.equals("true"));
        _eepProxyPort = getInt(props.getProperty("eepproxyport"));
        _webProxyPort = getInt(props.getProperty("webproxyport"));
        _torProxyPort = getInt(props.getProperty("torproxyport"));
        _eepProxyHost = props.getProperty("eepproxyhost");
        _webProxyHost = props.getProperty("webproxyhost");
        _torProxyHost = props.getProperty("torproxyhost");
        String importadr = props.getProperty("importaddresses", "false");
        _importAddresses = (importadr != null) && (importadr.equals("true"));
    }
    
    private int getInt(String val) {
        if (val == null) return -1;
        try { return Integer.parseInt(val); } catch (NumberFormatException nfe) { return -1; }
    }
    
    public static final String LOGIN_OK = "<span class=\"b_loginMsgOk\">Logged in</span>";
    
    public String export() {
        StringBuffer buf = new StringBuffer(512);
        buf.append("password=" + getHashedPassword() + "\n");
        buf.append("blog=" + getBlog().toBase64() + "\n");
        buf.append("lastid=" + getMostRecentEntry() + "\n");
        buf.append("lastmetaedition=" + getLastMetaEntry() + "\n");
        buf.append("lastlogin=" + getLastLogin() + "\n");
        buf.append("addressbook=" + getAddressbookLocation() + "\n");
        buf.append("showimages=" + getShowImages() + "\n");
        buf.append("showexpanded=" + getShowExpanded() + "\n");
        buf.append("defaultselector=" + getDefaultSelector() + "\n");
        buf.append("allowaccessremote=" + _allowAccessRemote + "\n");
        buf.append("importaddresses=" + getImportAddresses() + "\n");
        buf.append("groups=");
        Map groups = getBlogGroups();
        for (Iterator iter = groups.keySet().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            List selectors = (List)groups.get(name);
            buf.append(name).append(':');
            for (int i = 0; i < selectors.size(); i++) {
                buf.append(selectors.get(i));
                if (i + 1 < selectors.size())
                    buf.append(",");
            }
            if (iter.hasNext())
                buf.append(' ');
        }
        buf.append('\n');
        // shitlist=hash,hash,hash
        List shitlistedBlogs = getShitlistedBlogs();
        if (shitlistedBlogs.size() > 0) {
            buf.setLength(0);
            buf.append("shitlistedblogs=");
            for (int i = 0; i < shitlistedBlogs.size(); i++) {
                Hash blog = (Hash)shitlistedBlogs.get(i);
                buf.append(blog.toBase64());
                if (i + 1 < shitlistedBlogs.size())
                    buf.append(',');
            }
            buf.append('\n');
        }

        return buf.toString();
    }
}
