package net.i2p.syndie;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import com.sun.syndication.feed.synd.SyndCategory;
import com.sun.syndication.feed.synd.SyndContent;
import com.sun.syndication.feed.synd.SyndEntry;
import com.sun.syndication.feed.synd.SyndFeed;
import com.sun.syndication.io.FeedException;
import com.sun.syndication.io.SyndFeedInput;
import com.sun.syndication.io.XmlReader;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.syndie.data.BlogURI;
import net.i2p.util.EepGet;
import net.i2p.util.Log;

public class Sucker {
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(Sucker.class);
    private String urlToLoad;
    private String outputDir="./sucker_out";
    private String historyPath="./sucker.history";
    private String feedTag="feed";
    private File historyFile;
    private String proxyPort;
    private String proxyHost;
    private String pushScript;
    private int attachmentCounter=0;
    private String messagePath;
    private String baseUrl;
    private boolean importEnclosures=true;
    private boolean importRefs=true;
    private boolean pendingEndLink;
    private boolean shouldProxy;
    private int proxyPortNum;
    private String blog;
    private boolean pushToSyndie;
    private long messageNumber=0;
    private BlogManager bm;
    private User user;
    
    //
    private List fileNames;
    private List fileStreams;
    private List fileTypes;
    private List tempFiles; // deleted after finished push 
    private boolean stripNewlines;
    
    public Sucker() {
    }
    
    /**
     * Constructor for BlogManager. 
     */
    public Sucker(String[] strings) throws IllegalArgumentException {
        pushToSyndie=true;
        urlToLoad = strings[0];
        blog = strings[1];
        feedTag = strings[2];
        outputDir = "blog-"+blog;
        try {
            historyPath=BlogManager.instance().getRootDir().getCanonicalPath()+"/rss.history";
        } catch (IOException e) {
            e.printStackTrace();
        }
        proxyPort = BlogManager.instance().getDefaultProxyPort();
        proxyHost = BlogManager.instance().getDefaultProxyHost();

        bm = BlogManager.instance();
        Hash blogHash = new Hash();
        try {
            blogHash.fromBase64(blog);
        } catch (DataFormatException e1) {
            throw new IllegalArgumentException("ooh, bad $blog");
        }
     
        user = bm.getUser(blogHash);
        if(user==null)
            throw new IllegalArgumentException("wtf, user==null? hash:"+blogHash);
    }

    public boolean parseArgs(String args[]) {
        for (int i = 0; i < args.length; i++) {
            if ("--load".equals(args[i]))
                urlToLoad = args[++i];
            if ("--outputdir".equals(args[i]))
                outputDir = args[++i];
            if ("--history".equals(args[i]))
                historyPath = args[++i];
            if ("--tag".equals(args[i]))
                feedTag = args[++i];
            if ("--proxyhost".equals(args[i]))
                proxyHost = args[++i];
            if ("--proxyport".equals(args[i]))
                proxyPort = args[++i];
            if ("--exec".equals(args[i]))
                pushScript = args[++i];
            if ("--importenclosures".equals(args[i]))
                importEnclosures= args[++i].equals("true");
            if ("--importenrefs".equals(args[i]))
                importRefs= args[++i].equals("true");
        }

        // Cut ending '/' from outputDir
        if (outputDir.endsWith("/"))
            outputDir = outputDir.substring(0, outputDir.length() - 1);

        if (urlToLoad == null)
            return false;

        return true;
    }
    
    /**
     * Fetch urlToLoad and call convertToHtml() on any new entries.
     */
    public void suck() {
        SyndFeed feed;
        File fetched=null;
        
        tempFiles = new ArrayList();
        
        // Find base url
        int idx=urlToLoad.lastIndexOf('/');
        if(idx>0)
            baseUrl=urlToLoad.substring(0,idx);
        else
            baseUrl=urlToLoad;

        infoLog("Processing: "+urlToLoad);
        debugLog("Base url: "+baseUrl);

        //
        try {
            File lastIdFile=null;
         
            // Get next message number to use (for messageId in history only)
            if(!pushToSyndie) {
                
                lastIdFile = new File(historyPath + ".lastId");
                if (!lastIdFile.exists())
                    lastIdFile.createNewFile();
                
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(lastIdFile);
                    String number = readLine(fis);
                    messageNumber = Integer.parseInt(number);
                } catch (NumberFormatException e) {
                    messageNumber = 0;
                } finally {
                    if (fis != null) try { fis.close(); } catch (IOException ioe) {}
                }

                // Create outputDir if missing
                File f = new File(outputDir);
                f.mkdirs();
            } else {
                messageNumber=bm.getNextBlogEntry(user);
            }

            _log.debug("message number: " + messageNumber);
            
            // Create historyFile if missing
            historyFile = new File(historyPath);
            if (!historyFile.exists())
                historyFile.createNewFile();

            shouldProxy = false;
            proxyPortNum = -1;
            if ( (proxyHost != null) && (proxyPort != null) ) {
                try {
                    proxyPortNum = Integer.parseInt(proxyPort);
                    if (proxyPortNum > 0)
                        shouldProxy = true;
                } catch (NumberFormatException nfe) {
                    nfe.printStackTrace();
                }
            }
            
            // fetch
            int numRetries = 2;
            fetched = File.createTempFile("sucker", ".fetch");
            EepGet get = new EepGet(I2PAppContext.getGlobalContext(), shouldProxy, proxyHost, proxyPortNum, 
                                    numRetries, fetched.getAbsolutePath(), urlToLoad);
            SuckerFetchListener lsnr = new SuckerFetchListener();
            get.addStatusListener(lsnr);
            
            _log.debug("fetching [" + urlToLoad + "] / " + shouldProxy + "/" + proxyHost + "/" + proxyHost);
            
            get.fetch();
            _log.debug("fetched: " + get.getNotModified() + "/" + get.getETag());
            boolean ok = lsnr.waitForSuccess();
            if (!ok) {
                _log.debug("success? " + ok);
                System.err.println("Unable to retrieve the url after " + numRetries + " tries.");
                fetched.delete();
                return;
            }
            _log.debug("fetched successfully? " + ok);
            if(get.getNotModified()) {
                debugLog("not modified, saving network bytes from useless fetch");
                fetched.delete();
                return;
            }

            // Build entry list from fetched rss file
            SyndFeedInput input = new SyndFeedInput();
            feed = input.build(new XmlReader(fetched));

            List entries = feed.getEntries();

            _log.debug("entries: " + entries.size());
            
            FileOutputStream hos = null;

            try {
                hos = new FileOutputStream(historyFile, true);
                
                // Process list backwards to get syndie to display the 
                // entries in the right order. (most recent at top)
                for (int i = entries.size()-1; i >= 0; i--) { 
                    SyndEntry e = (SyndEntry) entries.get(i);

                    attachmentCounter=0;

                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Syndicate entry: " + e.getLink());

                    String messageId = convertToSml(e);
                    if (messageId!=null) {
                        hos.write(messageId.getBytes());
                        hos.write("\n".getBytes());
                    }
                }
            } finally {
                if (hos != null) try { hos.close(); } catch (IOException ioe) {}
            }
            
            if(!pushToSyndie) {
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(lastIdFile);
                    fos.write(("" + messageNumber).getBytes());
                } finally {
                    if (fos != null) try { fos.close(); } catch (IOException ioe) {}
                }
            }
            
            _log.debug("done fetching");
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (FeedException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        if(fetched!=null)
            fetched.delete();
        debugLog("Done.");
    }

    public static void main(String[] args) {
        Sucker sucker = new Sucker();
        boolean ok = sucker.parseArgs(args);
        if (!ok) {
            System.out.println("sucker --load $urlToFeed \n"
                    + "--proxyhost <host> \n" 
                    + "--proxyport <port> \n"
                    + "--importenclosures true \n" 
                    + "--importrefs true \n"
                    + "--tag feed \n" 
                    + "--outputdir ./sucker_out \n"
                    + "--exec pushscript.sh OUTPUTDIR UNIQUEID ENTRYTIMESTAMP \n"
                    + "--history ./sucker.history");
            System.exit(1);
        }
        
        sucker.suck();
    }

    /**
     * Call the specified script with "$outputDir $id and $time". 
     */
    private boolean execPushScript(String id, String time) {
        try {
            String ls_str;

            String cli = pushScript + " " + outputDir + " " + id + " " + time;
            Process pushScript_proc = Runtime.getRuntime().exec(cli);

            // get its output (your input) stream

            InputStream ls_in = pushScript_proc.getInputStream();

            try {
                StringBuffer buf = new StringBuffer();
                while (true) {
                    boolean eof = DataHelper.readLine(ls_in, buf);
                    if (buf.length() > 0) 
                        infoLog(pushScript + ": " + buf.toString());
                    buf.setLength(0);
                    if (eof)
                        break;
                }
            } catch (IOException e) {
                return false;
            }
            try {
                pushScript_proc.waitFor();
                if(pushScript_proc.exitValue()==0)
                    return true;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return false;
        } catch (IOException e1) {
            System.err.println(e1);
            return false;
        }
    }

    /** 
     * Converts the SyndEntry e to sml and fetches any images as attachments 
     */ 
    private String convertToSml(SyndEntry e) {
        String subject;

        stripNewlines=false;
        
        // Calculate messageId, and check if we have got the message already
        String feedHash = sha1(urlToLoad);
        String itemHash = sha1(e.getTitle() + e.getDescription());
        Date d = e.getPublishedDate();
        String time;
        if(d!=null)
            time = "" + d.getTime();
        else
            time = "" + new Date().getTime();
        String outputFileName = outputDir + "/" + messageNumber;
        String messageId = feedHash + ":" + itemHash + ":" + time + ":" + outputFileName;
        // Check if we already have this
        if (existsInHistory(messageId))
            return null;
        
        infoLog("new: " + messageId);
            
        try {

            String sml="";
            subject=e.getTitle();
            List cats = e.getCategories();
            Iterator iter = cats.iterator();
            String tags = feedTag;
            while (iter.hasNext()) {
                SyndCategory c = (SyndCategory) iter.next();
                debugLog("Name: "+c.getName());
                debugLog("uri:"+c.getTaxonomyUri());
                String tag=c.getName();
                tag=tag.replaceAll("[^a-zA-z.-_:]","_");
                tags += "\t" + feedTag + "." + tag;
            }

            SyndContent content;

            List l = e.getContents();
            if(l!=null)
            {
            debugLog("There is content");
                iter = l.iterator();
                while(iter.hasNext())
                {
                    content = (SyndContent)iter.next();
                    String c = content.getValue();
                    debugLog("Content: "+c);
                                  sml += htmlToSml(c);
                                  sml += "\n";
                }
            }
            String source=e.getUri();
            if(source.indexOf("http")<0)
            source=baseUrl+source;
            sml += "[link schema=\"web\" location=\""+source+"\"]source[/link]\n";

            if(pushToSyndie) {
                debugLog("user.blog: "+user.getBlogStr());
                debugLog("user.id: "+bm.getNextBlogEntry(user));
                debugLog("subject: "+subject);
                debugLog("tags: "+tags);
                debugLog("sml: "+sml);
                debugLog("");
                BlogURI uri = bm.createBlogEntry(
                        user, 
                        false,
                        subject, 
                        tags, 
                        null,
                        sml, 
                        fileNames, 
                        fileStreams, 
                        fileTypes);

                if(uri==null) {
                    errorLog("pushToSyndie failure.");
                    return null;
                }
                else
                    infoLog("pushToSyndie success, uri: "+uri.toString());
            }
            else
            {
                FileOutputStream fos;
                fos = new FileOutputStream(messagePath);
                sml=subject + "\nTags: " + tags + "\n\n" + sml;
                fos.write(sml.getBytes());
                if (pushScript != null) {
                    if (!execPushScript(""+messageNumber, time)) {
                        errorLog("push script failed");
                    } else {
                        infoLog("push script success: nr "+messageNumber);
                    }
                }
            }
            messageNumber++;
            deleteTempFiles();
            return messageId;
        } catch (FileNotFoundException e1) {
            e1.printStackTrace();
        } catch (IOException e2) {
            e2.printStackTrace();
        }
        deleteTempFiles();
        return null;
    }

    private void deleteTempFiles() {
        Iterator iter = tempFiles.iterator();
        while(iter.hasNext()) {
            File tempFile = (File)iter.next();
            tempFile.delete();
        }
    }

    private String htmlToSml(String html) {

        String sml="";
        int i=0;

        pendingEndLink=false;

        while(i<html.length())
        {
            char c=html.charAt(i);
            switch(c) {
            case '<':
                //log("html: "+html.substring(i));
                
                int tagLen = findTagLen(html.substring(i));
                if(tagLen<=0) {
                    // did not find anything that looks like tag, treat it like text
                    sml+="&lt;";
                    break;
                }
                //
                String htmlTag = html.substring(i,i+tagLen);
                
                //log("htmlTag: "+htmlTag);
                
                String smlTag = htmlTagToSmlTag(htmlTag);
                if(smlTag!=null) {
                    sml+=smlTag;
                    i+=tagLen;
                    sml+=" "; 
                    continue;
                }
                // Unrecognized tag, treat it as text
                sml+="&lt;";
                break;
            case '\r':
                if(!stripNewlines)
                    sml+='\r';
                break;
            case '\n':
                if(!stripNewlines)
                    sml+='\n';
                break;
            case '[':
                sml+="&#91;";
                break;
            case ']':
                sml+="&#93;";
                break;
            default:
                sml+=c;
                break;
            }
            i++;
        }
        
        return sml;
    }

    private String htmlTagToSmlTag(String htmlTag) {
        final String ignoreTags[] = {
                "span",
                "tr",
                "td",
                "th",
                "div",
                "input",
                "ul"
        };
        htmlTag = htmlTag.replaceAll("\\[","&#91;").replaceAll("\\]","&#93;");
        String ret="";
        String htmlTagLowerCase=htmlTag.toLowerCase();

        if(htmlTagLowerCase.startsWith("<img"))
        {
            debugLog("Found image tag: "+htmlTag);
            int a,b;
            a=htmlTagLowerCase.indexOf("src=\"")+5;
            b=a+1;
            while(htmlTagLowerCase.charAt(b)!='\"')
                b++;
            String imageLink=htmlTag.substring(a,b);
            
            if(pendingEndLink) { // <a href="..."><img src="..."></a> -> [link][/link][img][/img]
                ret="[/link]";
                pendingEndLink=false;
            }
    
            ret += "[img attachment=\""+""+ attachmentCounter +"\"]";
            
            a=htmlTagLowerCase.indexOf("alt=\"")+5;
            if(a>=5)
            {
                b=a;
                if(htmlTagLowerCase.charAt(b)!='\"') {
                    while(htmlTagLowerCase.charAt(b)!='\"')
                        b++;
                    String altText=htmlTag.substring(a,b);
                    ret+=altText;
                }
            }
            
            ret+="[/img]";
            
            if(imageLink.indexOf("http")<0)
                imageLink=baseUrl+"/"+imageLink;
            
            fetchAttachment(imageLink);

            debugLog("Converted to: "+ret);
            
            return ret;
            
        }
        if(htmlTagLowerCase.startsWith("<a "))
        {
            debugLog("Found link tag: "+htmlTag);
            int a,b;
            
            a=htmlTagLowerCase.indexOf("href=\"")+6;
            b=a+1;
            while(htmlTagLowerCase.charAt(b)!='\"')
                b++;
            String link=htmlTag.substring(a,b);
            if(link.indexOf("http")<0)
                link=baseUrl+"/"+link;
            
            String schema="web";
            
            ret += "[link schema=\""+schema+"\" location=\""+link+"\"]";
            if(htmlTagLowerCase.endsWith("/>"))
                ret += "[/link]";
            else
                pendingEndLink=true;
        
            debugLog("Converted to: "+ret);

            return ret;
        }
        
        if ("</a>".equals(htmlTagLowerCase)) {
            if (pendingEndLink) {
                pendingEndLink=false;
                return "[/link]";
            }
        }
        
        if("<b>".equals(htmlTagLowerCase))
            return "[b]";
        if("</b>".equals(htmlTagLowerCase))
            return "[/b]";
        if("<i>".equals(htmlTagLowerCase))
            return "[i]";
        if("</i>".equals(htmlTagLowerCase))
            return "[/i]";
        if("<em>".equals(htmlTagLowerCase))
            return "[i]";
        if("</em>".equals(htmlTagLowerCase))
            return "[/i]";
        if("<strong>".equals(htmlTagLowerCase))
            return "[b]";
        if("</strong>".equals(htmlTagLowerCase))
            return "[/b]";
        if(htmlTagLowerCase.startsWith("<br")) {
            stripNewlines=true;
            return "\n";
        }
        if("<p>".equals(htmlTagLowerCase))
            return "\n\n";
        if("</p>".equals(htmlTagLowerCase))
            return "";
        if("<li>".equals(htmlTagLowerCase))
            return "\n * ";
        if("</li>".equals(htmlTagLowerCase))
            return "";
        if("</br>".equals(htmlTagLowerCase))
            return "";
        if(htmlTagLowerCase.startsWith("<table") || "</table>".equals(htmlTagLowerCase)) // emulate table with hr
            return "[hr][/hr]";

        for(int i=0;i<ignoreTags.length;i++) {
            String openTag = "<"+ignoreTags[i];
            String closeTag = "</"+ignoreTags[i];
            if(htmlTagLowerCase.startsWith(openTag))
                return "";
            if(htmlTagLowerCase.startsWith(closeTag))
                return "";
        }
        
        return null;
    }

    private void fetchAttachment(String link) {
        
        link=link.replaceAll("&amp;","&");
        
        infoLog("Fetch attachment from: "+link);
        
        File fetched;
        if(pushToSyndie) {
            try {
                // perhaps specify a temp dir?
                fetched = File.createTempFile("sucker",".attachment");
                fetched.deleteOnExit();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
            tempFiles.add(fetched);
        } else {
            String attachmentPath = messagePath+"."+attachmentCounter;
            fetched = new File(attachmentPath);
        }
        int numRetries = 2;
        // we use eepGet, since it retries and doesn't leak DNS requests like URL does
        EepGet get = new EepGet(I2PAppContext.getGlobalContext(), shouldProxy, proxyHost, proxyPortNum, 
                                numRetries, fetched.getAbsolutePath(), link);
        SuckerFetchListener lsnr = new SuckerFetchListener();
        get.addStatusListener(lsnr);
        get.fetch();
        boolean ok = lsnr.waitForSuccess();
        if (!ok) {
            System.err.println("Unable to retrieve the url after " + numRetries + " tries.");
            fetched.delete();
            return;
        }
        tempFiles.add(fetched);
        String filename=EepGet.suggestName(link);
        String contentType = get.getContentType();
        if(contentType==null)
            contentType="text/plain";
        debugLog("successful fetch of filename "+filename);
        if(fileNames==null) fileNames = new ArrayList();
        if(fileTypes==null) fileTypes = new ArrayList();
        if(fileStreams==null) fileStreams = new ArrayList();
        fileNames.add(filename);
        fileTypes.add(contentType);
        try {
            fileStreams.add(new FileInputStream(fetched));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        attachmentCounter++;
    }

    private void errorLog(String string) {
        if (_log.shouldLog(Log.ERROR))
            _log.error(string);
        if(!pushToSyndie)
            System.out.println(string);
    }

    private void infoLog(String string) {
        if (_log.shouldLog(Log.INFO))
            _log.info(string);
        if(!pushToSyndie)
            System.out.println(string);
    }

    private void debugLog(String string) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(string);
        if(!pushToSyndie)
            System.out.println(string);
    }

    private static int findTagLen(String s) {
        int i;
        for(i=0;i<s.length();i++)
        {
            if(s.charAt(i)=='>')
                return i+1;
            if(s.charAt(i)=='"')
            {
                i++;
                while(i<s.length() && s.charAt(i)!='"')
                    i++;
            }   
        }
        return -1;
    }

    private boolean existsInHistory(String messageId) {
        int idx;
        idx = messageId.lastIndexOf(":");
        String lineToCompare = messageId.substring(0, idx-1);
        idx = lineToCompare.lastIndexOf(":");
        lineToCompare = lineToCompare.substring(0, idx-1);
        FileInputStream his = null;
        try {
            his = new FileInputStream(historyFile);
            String line;
            while ((line = readLine(his)) != null) {
                idx = line.lastIndexOf(":");
                if (idx < 0)
                    return false;
                line = line.substring(0, idx-1);
                idx = line.lastIndexOf(":");
                if (idx < 0)
                    return false;
                line = line.substring(0, idx-1);
                if (line.equals(lineToCompare))
                    return true;
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } finally {
            if (his != null) try { his.close(); } catch (IOException ioe) {}
        }
        return false;
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA");
            md.update(s.getBytes());
            byte[] buf = md.digest();
            String ret = Base64.encode(buf);
            return ret;
        } catch (NoSuchAlgorithmException ex) {
            ex.printStackTrace();
        }
        return null;
    }

    private static String readLine(FileInputStream in) {
        StringBuffer sb = new StringBuffer();
        int c = 0;
        while (true) {
            try {
                c = in.read();
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
            if (c < 0)
                break;
            if (c == '\n')
                break;
            sb.append((char) c);
        }
        return sb.toString();
    }
}

/**
 * Simple blocking listener for eepget.  block in waitForSuccess().
 */
class SuckerFetchListener implements EepGet.StatusListener {
    private volatile boolean _complete;
    private volatile boolean _successful;
    
    public SuckerFetchListener() {
        _complete = false;
        _successful = false;
    }
    
    public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {
        notifyComplete(true);
    }
    
    public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {
        notifyComplete(false);
    }
    
    private void notifyComplete(boolean ok) {
        synchronized (this) {
            _complete = true;
            _successful = ok;
            notifyAll();
        }
    }
    
    /**
     * Block until the fetch is successful, returning true if it did fetch completely, 
     * false if it didn't.
     *
     */
    public boolean waitForSuccess() {
        while (!_complete) {
            try {
                synchronized (this) {
                    wait();
                }
            } catch (InterruptedException ie) {}
        }
        return _successful;
    }
    
    public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {
        // noop, it may retry
    }
    public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
        // ignore this status update
    }
    public void headerReceived(String url, int currentAttempt, String key, String val) {
        // ignore
    }
    
}
