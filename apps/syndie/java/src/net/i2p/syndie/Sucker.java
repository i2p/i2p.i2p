package net.i2p.syndie;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

//import sun.security.provider.SHA;

import com.sun.syndication.feed.atom.Entry;
import com.sun.syndication.feed.synd.SyndCategory;
import com.sun.syndication.feed.synd.SyndContent;
import com.sun.syndication.feed.synd.SyndEntry;
import com.sun.syndication.feed.synd.SyndFeed;
import com.sun.syndication.io.FeedException;
import com.sun.syndication.io.SyndFeedInput;
import com.sun.syndication.io.XmlReader;

import net.i2p.I2PAppContext;
import net.i2p.util.EepGet;

public class Sucker {
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
    
    public Sucker() {}
    
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
        
        // Find base url, gah HELP
        int idx=urlToLoad.length();
        int x=urlToLoad.indexOf('?');
        if(x>0)
            idx=x;
        while(idx>0)
        {
            idx--;
            if(urlToLoad.charAt(idx)=='/')
                break;
        }
        if(idx==0)
            idx=x;
        baseUrl=urlToLoad.substring(0,idx);
        System.out.println("BaseUrl: "+baseUrl);

        return true;
    }
    
    public void suck() {
        URL feedUrl;
        SyndFeed feed;
        int i;

        //if (proxyHost != null && proxyPort != null) {
        //    // Set proxy
        //    System.setProperty("http.proxyHost", proxyHost);
        //    System.setProperty("http.proxyPort", proxyPort);
        //}


        //
        try {
            
            // Create outputDir if missing
            File f = new File(outputDir);
            f.mkdirs();

            // Create historyFile if missing
            historyFile = new File(historyPath);
            if (!historyFile.exists())
                historyFile.createNewFile();

            int messageNumber;

            File lastIdFile = new File(historyPath + ".lastId");
            if (!lastIdFile.exists())
                lastIdFile.createNewFile();

            FileInputStream fis = new FileInputStream(lastIdFile);
            String number = readLine(fis);
            try {
                messageNumber = Integer.parseInt(number);
            } catch (NumberFormatException e) {
                messageNumber = 0;
            }

            SyndFeedInput input = new SyndFeedInput();

            boolean shouldProxy = false;
            int proxyPortNum = -1;
            if ( (proxyHost != null) && (proxyPort != null) ) {
                try {
                    proxyPortNum = Integer.parseInt(proxyPort);
                    if (proxyPortNum > 0)
                        shouldProxy = true;
                } catch (NumberFormatException nfe) {
                    nfe.printStackTrace();
                }
            }
            int numRetries = 2;
            // perhaps specify a temp dir?
            File fetched = File.createTempFile("sucker", ".fetch");
            fetched.deleteOnExit();
            // we use eepGet, since it retries and doesn't leak DNS requests like URL does
            EepGet get = new EepGet(I2PAppContext.getGlobalContext(), shouldProxy, proxyHost, proxyPortNum, 
                                    numRetries, fetched.getAbsolutePath(), urlToLoad);
            SuckerFetchListener lsnr = new SuckerFetchListener();
            get.addStatusListener(lsnr);
            get.fetch();
            boolean ok = lsnr.waitForSuccess();
            if (!ok) {
                System.err.println("Unable to retrieve the url after " + numRetries + " tries.");
                return;
            }
            
            feed = input.build(new XmlReader(fetched));

            List entries = feed.getEntries();

            FileOutputStream hos = new FileOutputStream(historyFile, true);

            ListIterator iter = entries.listIterator();
            while (iter.hasNext()) {
                
                attachmentCounter=0;
                
                SyndEntry e = (SyndEntry) iter.next();
                // Calculate messageId
                String feedHash = sha1(urlToLoad);
                String itemHash = sha1(e.getTitle() + e.getDescription());
                Date d = e.getPublishedDate();
                String time;
                if(d!=null)
                    time = "" + d.getTime();
                else
                    time = "" + new Date().getTime();
                    
                String outputFileName = outputDir + "/" + messageNumber;

                /*
                 * $feedHash:$itemHash:$time:$outputfile. $feedHash would be the
                 * hash (md5? sha1? sha2?) of the $urlToFeed, $itemHash is some
                 * hash of the SyndEntry, $time would be the time that the entry
                 * was posted $outputfile would be the $outputdir/$uniqueid
                 */
                String messageId = feedHash + ":" + itemHash + ":" + time + ":"
                                   + outputFileName;

                // Check if we already have this
                if (!existsInHistory(messageId)) {
                    System.out.println("new: " + messageId);

                    if (convertToSml(e, ""+messageNumber)) {
                        hos.write(messageId.getBytes());
                        hos.write("\n".getBytes());

                        if (pushScript != null) {
                            if (!execPushScript(""+messageNumber, time))
                                System.out.println("push failed");
                            else
                                System.out.println("push success");
                        }
                    }
                    messageNumber++;
                }
            }

            FileOutputStream fos = new FileOutputStream(lastIdFile);
            fos.write(("" + messageNumber).getBytes());
        } catch (MalformedURLException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (FeedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        System.out.println("Done.");
    }

    public static void main(String[] args) {
        Sucker sucker = new Sucker();
        boolean ok = sucker.parseArgs(args);
        if (!ok) {
            usage();
            return;
        }
        
        sucker.suck();
    }

    private boolean execPushScript(String id, String time) {
        try {
            String ls_str;

            String cli = pushScript + " " + outputDir + " " + id + " " + time;
            Process pushScript_proc = Runtime.getRuntime().exec(cli);

            // get its output (your input) stream

            DataInputStream ls_in = new DataInputStream(pushScript_proc.getInputStream());

            try {
                while ((ls_str = ls_in.readLine()) != null) {
                    System.out.println(pushScript + ": " + ls_str);
                }
            } catch (IOException e) {
                return false;
            }
            try {
                pushScript_proc.waitFor();
                if(pushScript_proc.exitValue()==0)
                    return true;
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            return false;
        } catch (IOException e1) {
            System.err.println(e1);
            return false;
        }
    }

    private boolean convertToSml(SyndEntry e, String messageName) {

        // Create message
        File messageFile = new File(messageName);
        FileOutputStream fos;
        messagePath=outputDir+"/"+messageName;
        try {
            fos = new FileOutputStream(messagePath);

            String sml;
            sml = "Subject: " + e.getTitle() + "\n";
            List cats = e.getCategories();
            Iterator iter = cats.iterator();
            String tags = feedTag;
            while (iter.hasNext()) {
                SyndCategory c = (SyndCategory) iter.next();
                String tag=c.getName();
                tag=tag.replaceAll("[^a-zA-z.-_:]","_");
                tags += "\t" + feedTag + "." + tag;
            }
            sml += "Tags: " + tags + "\n";
            sml += "\n";

            SyndContent content;

            List l = e.getContents();
            if(l!=null)
            {
            System.out.println("There is content");
                iter = l.iterator();
                while(iter.hasNext())
                {
                    content = (SyndContent)iter.next();
                    String c = content.getValue();
                    System.out.println("Content: "+c);
                                  sml += htmlToSml(c);
                                  sml += "\n";
                }
            }
            String source=e.getUri();
            if(source.indexOf("http")<0)
            source=baseUrl+source;
            sml += "[link schema=\"web\" location=\""+source+"\"]source[/link]\n";

            fos.write(sml.getBytes());

            return true;
        } catch (FileNotFoundException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        } catch (IOException e2) {
            // TODO Auto-generated catch block
            e2.printStackTrace();
        }
        return false;
    }

    private String htmlToSml(String html) {

        String sml="";
        int i;

        pendingEndLink=false;

        for(i=0;i<html.length();)
        {
            if(html.charAt(i)=='<')
            {
                //System.out.println("html: "+html.substring(i));
                
                int tagLen = findTagLen(html.substring(i));
                //
                String htmlTag = html.substring(i,i+tagLen);
                
                //System.out.println("htmlTag: "+htmlTag);
                
                String smlTag = htmlTagToSmlTag(htmlTag);
                if(smlTag!=null)
                    sml+=smlTag;
                i+=tagLen;
                //System.out.println("tagLen: "+tagLen);
                sml+=" "; 
            }
            else
            {
                sml+=html.charAt(i++);
            }
        }
        
        return sml;
    }

    private String htmlTagToSmlTag(String htmlTag) {
        String ret="";

        if(importEnclosures && htmlTag.startsWith("<img"))
        {
            System.out.println("Found image tag: "+htmlTag);
            int a,b;
            a=htmlTag.indexOf("src=\"")+5;
            b=a+1;
            while(htmlTag.charAt(b)!='\"')
                b++;
            String imageLink=htmlTag.substring(a,b);
            
            if(pendingEndLink) {
                ret="[/link]";
            pendingEndLink=false;
            }
    
            ret += "[img attachment=\""+""+ attachmentCounter +"\"]";
            
            a=htmlTag.indexOf("alt=\"")+5;
            if(a>=5)
            {
                b=a+1;
                while(htmlTag.charAt(b)!='\"')
                    b++;
                String altText=htmlTag.substring(a,b);
                ret+=altText;
            }
            
            ret+="[/img]";
            
            if(imageLink.indexOf("http")<0)
                imageLink=baseUrl+"/"+imageLink;
            
            fetchAttachment(imageLink);

            System.out.println("Converted to: "+ret);
            
            return ret;
            
        }
        if(importRefs && htmlTag.startsWith("<a "))
        {
            System.out.println("Found link tag: "+htmlTag);
            int a,b;
            
            a=htmlTag.indexOf("href=\"")+6;
            b=a+1;
            while(htmlTag.charAt(b)!='\"')
                b++;
            String link=htmlTag.substring(a,b);
            if(link.indexOf("http")<0)
                link=baseUrl+"/"+link;
            
            String schema="web";
            
            ret += "[link schema=\""+schema+"\" location=\""+link+"\"]";
            pendingEndLink=true;
        
            System.out.println("Converted to: "+ret);

            return ret;
        }
        
        if ("</a>".equals(htmlTag)) {
            if (pendingEndLink)
                return "[/link]";
        }
        
        if("<b>".equals(htmlTag))
            return "[b]";
        if("</b>".equals(htmlTag))
            return "[/b]";
        if("<i>".equals(htmlTag))
            return "[i]";
        if("</i>".equals(htmlTag))
            return "[/i]";
        
        return null;
    }

    private void fetchAttachment(String link) {
        System.out.println("Fetch attachment from: "+link);
        String attachmentPath = messagePath+"."+attachmentCounter;
        try {
            link=link.replaceAll("&amp;","&");
            URL attachmentUrl = new URL(link);
            InputStream is = attachmentUrl.openStream();
            
            FileOutputStream fos = new FileOutputStream(attachmentPath);
            
            while(true)
            {
                int i =is.read();
                if(i<0)
                    break;
                fos.write(i);
            }
            
        } catch (MalformedURLException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        
        attachmentCounter++;
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
                while(s.charAt(i)!='"')
                    i++;
            }   
        }
        System.out.println("WTF");
        return 0;
    }

    private boolean existsInHistory(String messageId) {
        int idx;
        idx = messageId.lastIndexOf(":");
        String lineToCompare = messageId.substring(0, idx-1);
        idx = lineToCompare.lastIndexOf(":");
        lineToCompare = lineToCompare.substring(0, idx-1);
        try {
            FileInputStream his = new FileInputStream(historyFile);
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
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return false;
    }

    private static void usage() {
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

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA");
            md.update(s.getBytes());
            byte[] buf = md.digest();
            String ret = new sun.misc.BASE64Encoder().encode(buf);
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
                // TODO Auto-generated catch block
                e.printStackTrace();
                break;
            }
            if (c < 0)
                break;
            if (c == '\n')
                break;
            sb.append((char) c);
        }
        // TODO Auto-generated method stub
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
