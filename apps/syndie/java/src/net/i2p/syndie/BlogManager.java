package net.i2p.syndie;

import java.io.*;
import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.data.*;
import net.i2p.syndie.data.*;
import net.i2p.syndie.sml.*;

/**
 *
 */
public class BlogManager {
    private I2PAppContext _context;
    private static BlogManager _instance;
    private File _blogKeyDir;
    private File _privKeyDir;
    private File _archiveDir;
    private File _userDir;
    private File _cacheDir;
    private Archive _archive;
    
    static {
        String rootDir = I2PAppContext.getGlobalContext().getProperty("syndie.rootDir");
        if (rootDir == null)
            rootDir = System.getProperty("user.home");
        rootDir = rootDir + File.separatorChar + ".syndie";
        _instance = new BlogManager(I2PAppContext.getGlobalContext(), rootDir);
    }
    public static BlogManager instance() { return _instance; }
    
    public BlogManager(I2PAppContext ctx, String rootDir) {
        _context = ctx;
        File root = new File(rootDir);
        root.mkdirs();
        _blogKeyDir = new File(root, "blogkeys");
        _privKeyDir = new File(root, "privkeys");
        String archiveDir = _context.getProperty("syndie.archiveDir");
        if (archiveDir != null)
            _archiveDir = new File(archiveDir);
        else
            _archiveDir = new File(root, "archive");
        _userDir = new File(root, "users");
        _cacheDir = new File(root, "cache");
        _blogKeyDir.mkdirs();
        _privKeyDir.mkdirs();
        _archiveDir.mkdirs();
        _cacheDir.mkdirs();
        _userDir.mkdirs();
        _archive = new Archive(ctx, _archiveDir.getAbsolutePath(), _cacheDir.getAbsolutePath());
        _archive.regenerateIndex();
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
            dfe.printStackTrace();
            return null;
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        }
        
        return createInfo(pub, priv, name, posters, description, contactURL, archives, 0);
    }
    
    public BlogInfo createInfo(SigningPublicKey pub, SigningPrivateKey priv, String name, SigningPublicKey posters[], 
                               String description, String contactURL, String archives[], int edition) {
        Properties opts = new Properties();
        opts.setProperty("Name", name);
        opts.setProperty("Description", description);
        opts.setProperty("Edition", Integer.toString(edition));
        opts.setProperty("ContactURL", contactURL);
        for (int i = 0; archives != null && i < archives.length; i++) 
            opts.setProperty("Archive." + i, archives[i]);
        
        BlogInfo info = new BlogInfo(pub, posters, opts);
        info.sign(_context, priv);
        
        _archive.storeBlogInfo(info);
        
        return info;
    }
    
    public Archive getArchive() { return _archive; }
    
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
                    ioe.printStackTrace();
                } catch (DataFormatException dfe) {
                    dfe.printStackTrace();
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
            ioe.printStackTrace();
            return null;
        } catch (DataFormatException dfe) {
            dfe.printStackTrace();
            return null;
        }
    }
    
    public String login(User user, String login, String pass) {
        File userFile = new File(_userDir, Base64.encode(_context.sha().calculateHash(login.getBytes()).getData()));
        System.out.println("Attempting to login to " + login + " w/ pass = " + pass 
                           + ": file = " + userFile.getAbsolutePath() + " passHash = "
                           + Base64.encode(_context.sha().calculateHash(pass.getBytes()).getData()));
        if (userFile.exists()) {
            try {
                Properties props = new Properties();
                BufferedReader in = new BufferedReader(new FileReader(userFile));
                String line = null;
                while ( (line = in.readLine()) != null) {
                    int split = line.indexOf('=');
                    if (split <= 0) continue;
                    String key = line.substring(0, split);
                    String val = line.substring(split+1);
                    props.setProperty(key.trim(), val.trim());
                }
                return user.login(login, pass, props);
            } catch (IOException ioe) {
                ioe.printStackTrace();
                return "Error logging in - corrupt userfile";
            }
        } else {
            return "User does not exist";
        }
    }
    
    /** hash of the password required to register and create a new blog (null means no password required) */
    public String getRegistrationPassword() { 
        String pass = _context.getProperty("syndie.registrationPassword");
        if ( (pass == null) || (pass.trim().length() <= 0) ) return null;
        return pass; 
    }
    
    public void saveUser(User user) {
        if (!user.getAuthenticated()) return;
        String userHash = Base64.encode(_context.sha().calculateHash(user.getUsername().getBytes()).getData());
        File userFile = new File(_userDir, userHash);
        FileWriter out = null;
        try {
            out = new FileWriter(userFile);
            out.write("password=" + user.getHashedPassword() + "\n");
            out.write("blog=" + user.getBlog().toBase64() + "\n");
            out.write("lastid=" + user.getMostRecentEntry() + "\n");
            out.write("lastmetaedition=" + user.getLastMetaEntry() + "\n");
            out.write("lastlogin=" + user.getLastLogin() + "\n");
            out.write("addressbook=" + user.getAddressbookLocation() + "\n");
            out.write("showimages=" + user.getShowImages() + "\n");
            out.write("showexpanded=" + user.getShowExpanded() + "\n");
            StringBuffer buf = new StringBuffer();
            buf.append("groups=");
            Map groups = user.getBlogGroups();
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
            out.write(buf.toString());
            // shitlist=hash,hash,hash
            List shitlistedBlogs = user.getShitlistedBlogs();
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
                out.write(buf.toString());
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        } finally {
            if (out != null) try { out.close(); } catch (IOException ioe){}
        }
    }
    public String register(User user, String login, String password, String registrationPassword, String blogName, String blogDescription, String contactURL) {
        String hashedRegistrationPassword = getRegistrationPassword();
        if (hashedRegistrationPassword != null) {
            if (!hashedRegistrationPassword.equals(Base64.encode(_context.sha().calculateHash(registrationPassword.getBytes()).getData())))
                return "Invalid registration password";
        }
        String userHash = Base64.encode(_context.sha().calculateHash(login.getBytes()).getData());
        File userFile = new File(_userDir, userHash);
        if (userFile.exists()) {
            return "Cannot register the login " + login + ": it already exists";
        } else {
            BlogInfo info = createBlog(blogName, blogDescription, contactURL, null);
            String hashedPassword = Base64.encode(_context.sha().calculateHash(password.getBytes()).getData());
            FileWriter out = null;
            try {
                out = new FileWriter(userFile);
                out.write("password=" + hashedPassword + "\n");
                out.write("blog=" + Base64.encode(info.getKey().calculateHash().getData()) + "\n");
                out.write("lastid=-1\n");
                out.write("lastmetaedition=0\n");
                out.write("addressbook=userhosts-"+userHash + ".txt\n");
                out.write("showimages=false\n");
                out.write("showexpanded=false\n");
            } catch (IOException ioe) {
                ioe.printStackTrace();
                return "Internal error registering - " + ioe.getMessage();
            } finally {
                if (out != null) try { out.close(); } catch (IOException ioe) {}
            }
            String loginResult = login(user, login, password);
            _archive.regenerateIndex();
            return loginResult;
        }
    }
    
    public BlogURI createBlogEntry(User user, String subject, String tags, String entryHeaders, String sml) {
        return createBlogEntry(user, subject, tags, entryHeaders, sml, null, null, null);
    }
    public BlogURI createBlogEntry(User user, String subject, String tags, String entryHeaders, String sml, List fileNames, List fileStreams, List fileTypes) {
        if (!user.getAuthenticated()) return null;
        BlogInfo info = getArchive().getBlogInfo(user.getBlog());
        if (info == null) return null;
        SigningPrivateKey privkey = getMyPrivateKey(info);
        if (privkey == null) return null;
        
        long entryId = -1;
        long now = _context.clock().now();
        long dayBegin = getDayBegin(now);
        if (user.getMostRecentEntry() >= dayBegin)
            entryId = user.getMostRecentEntry() + 1;
        else
            entryId = dayBegin;
        
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
                System.out.println("Entry headers: " + entryHeaders);
                BufferedReader userHeaders = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(entryHeaders.getBytes())));
                String line = null;
                while ( (line = userHeaders.readLine()) != null) {
                    line = line.trim();
                    System.out.println("Line: " + line);
                    if (line.length() <= 0) continue;
                    int split = line.indexOf('=');
                    int split2 = line.indexOf(':');
                    if ( (split < 0) || ( (split2 > 0) && (split2 < split) ) ) split = split2;
                    String key = line.substring(0,split).trim();
                    String val = line.substring(split+1).trim();
                    raw.append(key).append(": ").append(val).append('\n');
                }
            }
            raw.append('\n');
            raw.append(sml);
            
            EntryContainer c = new EntryContainer(uri, tagList, raw.toString().getBytes());
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
                saveUser(user);
                return uri;
            } else {
                return null;
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        }
    }
    
    public String addAddress(User user, String name, String location, String schema) {
        if (!user.getAuthenticated()) return "Not logged in";
        boolean ok = validateAddressName(name);
        if (!ok) return "Invalid name: " + HTMLRenderer.sanitizeString(name);
        ok = validateAddressLocation(location);
        if (!ok) return "Invalid location: " + HTMLRenderer.sanitizeString(location);
        if (!validateAddressSchema(schema)) return "Unsupported schema: " + HTMLRenderer.sanitizeString(schema);
        // no need to quote user/location further, as they've been sanitized
        
        FileWriter out = null;
        try {
            File userHostsFile = new File(user.getAddressbookLocation());
            Properties knownHosts = getKnownHosts(user, true);
            if (knownHosts.containsKey(name)) return "Name is already in use";
        
            out = new FileWriter(userHostsFile, true);
            out.write(name + "=" + location + '\n');
            return "Address " + name + " written to your hosts file (" + userHostsFile.getName() + ")";
        } catch (IOException ioe) {
            return "Error writing out host entry: " + ioe.getMessage();
        } finally {
            if (out != null) try { out.close(); } catch (IOException ioe) {}
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
        if ( (name == null) || (name.trim().length() <= 0) || (!name.endsWith(".i2p")) ) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && ('.' != c) && ('-' != c) && ('_' != c) )
                return false;
        }
        return true;
    }

    private boolean validateAddressLocation(String location) {
        if ( (location == null) || (location.trim().length() <= 0) ) return false;
        try {
            Destination d = new Destination(location);
            return (d.getPublicKey() != null);
        } catch (DataFormatException dfe) {
            dfe.printStackTrace();
            return false;
        }
    }

    private boolean validateAddressSchema(String schema) {
        if ( (schema == null) || (schema.trim().length() <= 0) ) return false;
        return "eep".equals(schema) || "i2p".equals(schema);
    }
    
    private final GregorianCalendar _cal = new GregorianCalendar();
    private long getDayBegin(long now) {
        synchronized (_cal) {
            _cal.setTimeInMillis(now);
            _cal.set(Calendar.MILLISECOND, 0);
            _cal.set(Calendar.SECOND, 0);
            _cal.set(Calendar.MINUTE, 0);
            _cal.set(Calendar.HOUR, 0);
            _cal.set(Calendar.HOUR_OF_DAY, 0);
            return _cal.getTimeInMillis();
        }
    }
}
