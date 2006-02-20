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
import com.sun.syndication.feed.synd.SyndEnclosure;
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

/**
 *
 * todo:
 * - factor out the parsing / formatting / posting to let the sucker pull in arbitrary HTML pages
 *   (importing the images and SMLizing some stuff)
 * - push the posts out to a remote syndie instance too
 */
public class Sucker {
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(Sucker.class);
    private SuckerState _state;

    public Sucker() {}
    
    public Sucker(String[] strings) throws IllegalArgumentException {
        SuckerState state = new SuckerState();
        state.pushToSyndie=true;
        state.urlToLoad = strings[0];
        state.blog = strings[1];
        state.feedTag = strings[2];
        state.outputDir = "blog-"+state.blog;
        try {
            state.historyPath=BlogManager.instance().getRootDir().getCanonicalPath()+"/rss.history";
        } catch (IOException e) {
            e.printStackTrace();
        }
        state.proxyPort = BlogManager.instance().getDefaultProxyPort();
        state.proxyHost = BlogManager.instance().getDefaultProxyHost();

        state.bm = BlogManager.instance();
        Hash blogHash = new Hash();
        try {
            blogHash.fromBase64(state.blog);
        } catch (DataFormatException e1) {
            throw new IllegalArgumentException("ooh, bad $blog");
        }
     
        state.user = state.bm.getUser(blogHash);
        if(state.user==null)
            throw new IllegalArgumentException("wtf, user==null? hash:"+blogHash);
        state.history = new ArrayList();
        _state = state;
    }

    public boolean parseArgs(String args[]) {
        for (int i = 0; i < args.length; i++) {
            if ("--load".equals(args[i]))
                _state.urlToLoad = args[++i];
            if ("--outputdir".equals(args[i]))
                _state.outputDir = args[++i];
            if ("--history".equals(args[i]))
                _state.historyPath = args[++i];
            if ("--tag".equals(args[i]))
                _state.feedTag = args[++i];
            if ("--proxyhost".equals(args[i]))
                _state.proxyHost = args[++i];
            if ("--proxyport".equals(args[i]))
                _state.proxyPort = args[++i];
            if ("--exec".equals(args[i]))
                _state.pushScript = args[++i];
            if ("--importenclosures".equals(args[i]))
                _state.importEnclosures= args[++i].equals("true");
            if ("--importenrefs".equals(args[i]))
                _state.importRefs= args[++i].equals("true");
        }

        // Cut ending '/' from outputDir
        if (_state.outputDir.endsWith("/"))
            _state.outputDir = _state.outputDir.substring(0, _state.outputDir.length() - 1);

        if (_state.urlToLoad == null)
            return false;

        return true;
    }
    
    /**
     * Fetch urlToLoad and call convertToHtml() on any new entries.
     * @return list of BlogURI entries posted, if any
     */
    public List suck() {
        _state.entriesPosted = new ArrayList();
        SyndFeed feed;
        File fetched=null;
        
        _state.tempFiles = new ArrayList();
        
        // Find base url
        int idx=_state.urlToLoad.lastIndexOf('/');
        if(idx>0)
            _state.baseUrl=_state.urlToLoad.substring(0,idx);
        else
            _state.baseUrl=_state.urlToLoad;

        infoLog("Processing: "+_state.urlToLoad);
        debugLog("Base url: "+_state.baseUrl);

        //
        try {
            File lastIdFile=null;
         
            // Get next message number to use (for messageId in history only)
            if(!_state.pushToSyndie) {
                
                lastIdFile = new File(_state.historyPath + ".lastId");
                if (!lastIdFile.exists())
                    lastIdFile.createNewFile();
                
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(lastIdFile);
                    String number = readLine(fis);
                    _state.messageNumber = Integer.parseInt(number);
                } catch (NumberFormatException e) {
                    _state.messageNumber = 0;
                } finally {
                    if (fis != null) try { fis.close(); } catch (IOException ioe) {}
                }

                // Create outputDir if missing
                File f = new File(_state.outputDir);
                f.mkdirs();
            } else {
                _state.messageNumber=_state.bm.getNextBlogEntry(_state.user);
            }

            _log.debug("message number: " + _state.messageNumber);
            
            _state.shouldProxy = false;
            _state.proxyPortNum = -1;
            if ( (_state.proxyHost != null) && (_state.proxyPort != null) ) {
                try {
                    _state.proxyPortNum = Integer.parseInt(_state.proxyPort);
                    if (_state.proxyPortNum > 0)
                        _state.shouldProxy = true;
                } catch (NumberFormatException nfe) {
                    nfe.printStackTrace();
                }
            }
            
            // fetch
            int numRetries = 2;
            fetched = File.createTempFile("sucker", ".fetch");
            EepGet get = new EepGet(I2PAppContext.getGlobalContext(), _state.shouldProxy, _state.proxyHost, _state.proxyPortNum, 
                                    numRetries, fetched.getAbsolutePath(), _state.urlToLoad);
            SuckerFetchListener lsnr = new SuckerFetchListener();
            get.addStatusListener(lsnr);
            
            _log.debug("fetching [" + _state.urlToLoad + "] / " + _state.shouldProxy + "/" + _state.proxyHost + "/" + _state.proxyHost);
            
            get.fetch();
            _log.debug("fetched: " + get.getNotModified() + "/" + get.getETag());
            boolean ok = lsnr.waitForSuccess();
            if (!ok) {
                _log.debug("success? " + ok);
                System.err.println("Unable to retrieve the url [" + _state.urlToLoad + "] after " + numRetries + " tries.");
                fetched.delete();
                return _state.entriesPosted;
            }
            _log.debug("fetched successfully? " + ok);
            if(get.getNotModified()) {
                debugLog("not modified, saving network bytes from useless fetch");
                fetched.delete();
                return _state.entriesPosted;
            }

            // Build entry list from fetched rss file
            SyndFeedInput input = new SyndFeedInput();
            feed = input.build(new XmlReader(fetched));

            List entries = feed.getEntries();

            _log.debug("entries: " + entries.size());
            
            loadHistory();
            
            // Process list backwards to get syndie to display the 
            // entries in the right order. (most recent at top)
            List feedMessageIds = new ArrayList();
            for (int i = entries.size()-1; i >= 0; i--) { 
                SyndEntry e = (SyndEntry) entries.get(i);

                _state.attachmentCounter=0;

                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Syndicate entry: " + e.getLink());

                // Calculate messageId, and check if we have got the message already
                String feedHash = sha1(_state.urlToLoad);
                String itemHash = sha1(e.getTitle() + e.getDescription());
                Date d = e.getPublishedDate();
                String time;
                if(d!=null)
                    time = "" + d.getTime();
                else
                    time = "" + new Date().getTime();
                String outputFileName = _state.outputDir + "/" + _state.messageNumber;
                String messageId = feedHash + ":" + itemHash + ":" + time + ":" + outputFileName;
                
                // Make sure these messageIds get into the history file
                feedMessageIds.add(messageId);
                
                // Check if we already have this
                if (existsInHistory(_state, messageId))
                    continue;
                
                infoLog("new: " + messageId);
                
                // process the new entry
                processEntry(_state, e, time);
            }
            
            // update history
            pruneHistory(_state.urlToLoad, 42*10); // could use 0 if we were sure old entries never re-appear
            Iterator iter = feedMessageIds.iterator();
            while(iter.hasNext())
            {
                String newMessageId = (String)iter.next();
                if(!existsInHistory(_state, newMessageId))
                    addHistory(newMessageId); // add new message ids from current feed to history
            }
            storeHistory();

            // call script if we don't just feed syndie
            if(!_state.pushToSyndie) {
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(lastIdFile);
                    fos.write(("" + _state.messageNumber).getBytes());
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
        return _state.entriesPosted;
    }

    private void loadHistory() {        
        try {
            // Create historyFile if missing
            _state.historyFile = new File(_state.historyPath);
            if (!_state.historyFile.exists())
                _state.historyFile.createNewFile();

            FileInputStream is = new FileInputStream(_state.historyFile);
            String s;
            while((s=readLine(is))!=null)
            {
                addHistory(s);
            }
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        
    }
    
    private boolean existsInHistory(SuckerState state, String messageId) {
        int idx;
        idx = messageId.lastIndexOf(":");
        String lineToCompare = messageId.substring(0, idx-1);
        idx = lineToCompare.lastIndexOf(":");
        lineToCompare = lineToCompare.substring(0, idx-1);
        Iterator iter = _state.history.iterator();
        while(iter.hasNext())
        {
            String line = (String)iter.next();
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
        return false;
    }

    private void addHistory(String messageId) {
        _state.history.add(messageId);
    }
    
    private void pruneHistory(String url, int nrToKeep) {
        int i=0;
        String urlHash=sha1(url);
        
        // Count nr of entries containing url hash
        Iterator iter = _state.history.iterator();
        while(iter.hasNext())
        {
            String historyLine = (String) iter.next();
            if(historyLine.startsWith(urlHash))
            {
                i++; 
            }
        }

        // keep first nrToKeep entries
        i = i - nrToKeep;
        if(i>0)
        {
            iter = _state.history.iterator();
            while(i>0 && iter.hasNext())
            {
                String historyLine = (String) iter.next();
                if(historyLine.startsWith(urlHash))
                {
                    iter.remove();
                    i--;
                }
            }
        }
    }

    private void storeHistory() {
        FileOutputStream hos = null;
        try {
            hos = new FileOutputStream(_state.historyFile, false);
            Iterator iter = _state.history.iterator();
            while(iter.hasNext())
            {
                String historyLine = (String) iter.next();
                hos.write(historyLine.getBytes());
                hos.write("\n".getBytes());
            }
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally {
            if (hos != null) try { hos.close(); } catch (IOException ioe) {}
        }
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
    private static boolean execPushScript(SuckerState state, String id, String time) {
        try {
            String cli = state.pushScript + " " + state.outputDir + " " + id + " " + time;
            Process pushScript_proc = Runtime.getRuntime().exec(cli);

            // get its output (your input) stream

            InputStream ls_in = pushScript_proc.getInputStream();

            try {
                StringBuffer buf = new StringBuffer();
                while (true) {
                    boolean eof = DataHelper.readLine(ls_in, buf);
                    if (buf.length() > 0) 
                        infoLog(state.pushScript + ": " + buf.toString());
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
    private static boolean processEntry(SuckerState state, SyndEntry e, String time) {
        String subject;

        state.stripNewlines=false;
        
        try {

            String sml="";
            subject=e.getTitle();
            List cats = e.getCategories();
            Iterator iter = cats.iterator();
            String tags = state.feedTag;
            while (iter.hasNext()) {
                SyndCategory c = (SyndCategory) iter.next();
                debugLog("Name: "+c.getName());
                debugLog("uri:"+c.getTaxonomyUri());
                String tag=c.getName();
                tag=tag.replaceAll("[^a-zA-z.-_:]","_");
                tags += "\t" + state.feedTag + "." + tag;
            }

            SyndContent content;

            List l = e.getContents();
            if(l!=null)
            {
                iter = l.iterator();
                while(iter.hasNext())
                {
                    content = (SyndContent)iter.next();
                    String c = content.getValue();
                    debugLog("Content: "+c);
                                  sml += htmlToSml(state, c);
                                  sml += "\n";
                }
            }
            
            List enclosures = e.getEnclosures();
            debugLog("Enclosures: " + enclosures.size());
            for (int i = 0; i < enclosures.size(); i++) {
                SyndEnclosure enc = (SyndEnclosure)enclosures.get(i);
                String enclosureURL = enc.getUrl();
                if (enclosureURL != null) {
                    if (!enclosureURL.startsWith("http://")) {
                        // e.g. postman's rss feed @ http://tracker.postman.i2p/rss.jsp has
                        // baseUrl = http://tracker.postman.i2p
                        // and enclosure URLs are /download.php?id=123&file=blah
                        if (enclosureURL.startsWith("/") || state.baseUrl.endsWith("/"))
                            enclosureURL = state.baseUrl + enclosureURL;
                        else
                            enclosureURL = state.baseUrl + '/' + enclosureURL;
                    }   
                    fetchAttachment(state, enclosureURL, enc.getType()); // fetches and adds to our streams
                }
            }
            
            String source=e.getLink(); //Uri();
            if(!source.startsWith("http://"))
                source=state.baseUrl+source;
            sml += "[link schema=\"web\" location=\""+source+"\"]source[/link]\n";

            if(state.pushToSyndie) {
                debugLog("user.blog: "+state.user.getBlogStr());
                debugLog("user.id: "+state.bm.getNextBlogEntry(state.user));
                debugLog("subject: "+subject);
                debugLog("tags: "+tags);
                debugLog("sml: "+sml);
                debugLog("");
                BlogURI uri = state.bm.createBlogEntry(
                        state.user, 
                        false,
                        subject, 
                        tags, 
                        null,
                        sml, 
                        state.fileNames, 
                        state.fileStreams, 
                        state.fileTypes);

                if(uri==null) {
                    errorLog("pushToSyndie failure.");
                    return false;
                } else {
                    state.entriesPosted.add(uri);
                    infoLog("pushToSyndie success, uri: "+uri.toString());
                }
            }
            else
            {
                FileOutputStream fos;
                fos = new FileOutputStream(state.messagePath);
                sml=subject + "\nTags: " + tags + "\n\n" + sml;
                fos.write(sml.getBytes());
                if (state.pushScript != null) {
                    if (!execPushScript(state, ""+state.messageNumber, time)) {
                        errorLog("push script failed");
                    } else {
                        infoLog("push script success: nr "+state.messageNumber);
                    }
                }
            }
            state.messageNumber++;
            deleteTempFiles(state);
            return true;
        } catch (FileNotFoundException e1) {
            e1.printStackTrace();
        } catch (IOException e2) {
            e2.printStackTrace();
        }
        deleteTempFiles(state);
        return false;
    }

    private static void deleteTempFiles(SuckerState state) {
        Iterator iter = state.tempFiles.iterator();
        while(iter.hasNext()) {
            File tempFile = (File)iter.next();
            tempFile.delete();
        }
    }

    private static String htmlToSml(SuckerState state, String html) {

        String sml="";
        int i=0;

        state.pendingEndLink=false;

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
                
                String smlTag = htmlTagToSmlTag(state, htmlTag);
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
                if(!state.stripNewlines)
                    sml+='\r';
                break;
            case '\n':
                if(!state.stripNewlines)
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

    private static String htmlTagToSmlTag(SuckerState state, String htmlTag) {
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
            
            if(state.pendingEndLink) { // <a href="..."><img src="..."></a> -> [link][/link][img][/img]
                ret="[/link]";
                state.pendingEndLink=false;
            }
    
            ret += "[img attachment=\""+""+ state.attachmentCounter +"\"]";
            
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
            
            if(!imageLink.startsWith("http://"))
                imageLink=state.baseUrl+"/"+imageLink;
            
            fetchAttachment(state, imageLink);

            debugLog("Converted to: "+ret);
            
            return ret;
            
        }
        if(htmlTagLowerCase.startsWith("<a "))
        {
            debugLog("Found link tag: "+htmlTag);
            int a,b;
            
            a=htmlTagLowerCase.indexOf("href=\"")+6;
            b=a+1;
            while ( (b < htmlTagLowerCase.length()) && (htmlTagLowerCase.charAt(b)!='\"') )
                b++;
            if (b >= htmlTagLowerCase.length())
                return null; // abort the b0rked tag
            String link=htmlTag.substring(a,b);
            if(!link.startsWith("http://"))
                link=state.baseUrl+"/"+link;
            
            String schema="web";
            
            ret += "[link schema=\""+schema+"\" location=\""+link+"\"]";
            if(htmlTagLowerCase.endsWith("/>"))
                ret += "[/link]";
            else
                state.pendingEndLink=true;
        
            debugLog("Converted to: "+ret);

            return ret;
        }
        
        if ("</a>".equals(htmlTagLowerCase)) {
            if (state.pendingEndLink) {
                state.pendingEndLink=false;
                return "[/link]";
            }
            return "";
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
            state.stripNewlines=true;
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
        if(htmlTagLowerCase.startsWith("<hr"))
            return "";
        if("</img>".equals(htmlTagLowerCase))
            return "";
        if("</font>".equals(htmlTagLowerCase))
            return "";
        if("<blockquote>".equals(htmlTagLowerCase))
            return "[quote]";
        if("</blockquote>".equals(htmlTagLowerCase))
            return "[/quote]";
        if(htmlTagLowerCase.startsWith("<table") || "</table>".equals(htmlTagLowerCase)) // emulate table with hr :)
            return "[hr][/hr]";
        if(htmlTagLowerCase.startsWith("<font"))
            return "";
        

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

    private static void fetchAttachment(SuckerState state, String link) { fetchAttachment(state, link, null); }
    private static void fetchAttachment(SuckerState state, String link, String suggestedMimeType) {
        
        link=link.replaceAll("&amp;","&");
        
        infoLog("Fetch attachment from: "+link);
        
        File fetched;
        if(state.pushToSyndie) {
            try {
                // perhaps specify a temp dir?
                fetched = File.createTempFile("sucker",".attachment");
                fetched.deleteOnExit();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
            state.tempFiles.add(fetched);
        } else {
            String attachmentPath = state.messagePath+"."+state.attachmentCounter;
            fetched = new File(attachmentPath);
        }
        int numRetries = 2;
        // we use eepGet, since it retries and doesn't leak DNS requests like URL does
        EepGet get = new EepGet(I2PAppContext.getGlobalContext(), state.shouldProxy, state.proxyHost, state.proxyPortNum, 
                                numRetries, fetched.getAbsolutePath(), link);
        SuckerFetchListener lsnr = new SuckerFetchListener();
        get.addStatusListener(lsnr);
        get.fetch();
        boolean ok = lsnr.waitForSuccess();
        if (!ok) {
            debugLog("Unable to retrieve the url [" + link + "] after " + numRetries + " tries.");
            fetched.delete();
            return;
        }
        state.tempFiles.add(fetched);
        String filename=EepGet.suggestName(link);
        String contentType = suggestedMimeType;
        if (contentType == null)
            contentType = get.getContentType();
        if(contentType==null)
            contentType="text/plain";
        debugLog("successful fetch of filename "+filename + " suggested mime type [" + suggestedMimeType 
                 + "], fetched mime type [" + get.getContentType() + "], final type [" + contentType + "]");
        if(state.fileNames==null) state.fileNames = new ArrayList();
        if(state.fileTypes==null) state.fileTypes = new ArrayList();
        if(state.fileStreams==null) state.fileStreams = new ArrayList();
        state.fileNames.add(filename);
        state.fileTypes.add(contentType);
        try {
            state.fileStreams.add(new FileInputStream(fetched));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        state.attachmentCounter++;
    }

    private static void errorLog(String string) {
        if (_log.shouldLog(Log.ERROR))
            _log.error(string);
        //if(!pushToSyndie)
        //    System.out.println(string);
    }

    private static void infoLog(String string) {
        if (_log.shouldLog(Log.INFO))
            _log.info(string);
        //if(!pushToSyndie)
        //    System.out.println(string);
    }

    private static void debugLog(String string) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(string);
        //if(!pushToSyndie)
        //    System.out.println(string);
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
                return null;
            }
            if (c < 0)
                return null;
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



class SuckerState {
    String urlToLoad;
    String outputDir="./sucker_out";
    String historyPath="./sucker.history";
    String feedTag="feed";
    File historyFile;
    String proxyPort;
    String proxyHost;
    String pushScript;
    int attachmentCounter=0;
    String messagePath;
    String baseUrl;
    boolean importEnclosures=true;
    boolean importRefs=true;
    boolean pendingEndLink;
    boolean shouldProxy;
    int proxyPortNum;
    String blog;
    boolean pushToSyndie;
    long messageNumber=0;
    BlogManager bm;
    User user;
    List entriesPosted;
    List history;

    //
    List fileNames;
    List fileStreams;
    List fileTypes;
    List tempFiles; // deleted after finished push 

    boolean stripNewlines;
}