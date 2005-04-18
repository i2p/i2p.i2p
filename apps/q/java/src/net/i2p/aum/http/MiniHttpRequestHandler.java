/*
 * MiniHttpRequestHandler.java
 * Adapted from pont.net's httpRequestHandler (httpServer.java)
 *
 * Created on April 8, 2005, 3:15 PM
 */

package net.i2p.aum.http;

import java.net.*;
import java.io.*;
import java.util.*;
import java.lang.*;

import net.i2p.aum.*;

public abstract class MiniHttpRequestHandler implements Runnable {
    final static String CRLF = "\r\n";

    /** server which created this handler */
    protected MiniHttpServer server;

    /** socket through which client is connected to us */
    protected Object socket;

    /** stored constructor arg */
    protected Object serverArg;

    /** input sent from client in request */
    protected InputStream input;

    /** we use this to read from client */
    protected BufferedReader br;

    /** output sent to client in reply */
    protected OutputStream output;

    /** http request type - GET, POST etc */
    protected String reqType;

    /** the request pathname */
    protected String reqFile;

    /** the request protocol (eg 'HTTP/1.0') */
    protected String reqProto;

    /** http headers */
    protected DupHashtable headerVars;
    
    /** variable settings from POST data */
    public DupHashtable postVars;

    /** variable settings from URL (?name1=val1&name2=val2...) */
    public DupHashtable urlVars;

    /** consolidated variable settings from URL or POST data */
    public DupHashtable allVars;
    
    /** first line of response we send back to client, set this
     * with 'setStatus'
     */
    private String status = "HTTP/1.0 200 OK";
    private String contentType = "text/plain";
    private String reqContentType = null;
    protected String serverName = "aum's MiniHttpServer";
    
    protected byte [] rawContentBytes = null;
    
    /**
     * raw data sent by client in post req
     */
    protected char [] postData;

    /** if a POST, this holds the full POST data as a string */
    public String postDataStr;

    // Constructors
    public MiniHttpRequestHandler(MiniHttpServer server, Object socket) throws Exception {
        this(server, socket, null);
    }

    public MiniHttpRequestHandler(MiniHttpServer server, Object socket, Object arg) throws Exception {
        this.server = server;
        this.socket = socket;
        this.serverArg = arg;
        this.input = getInputStream();
        this.output = getOutputStream();
        this.br = new BufferedReader(new InputStreamReader(input));
    }

    // -------------------------------------------
    // START OF OVERRIDEABLES
    // -------------------------------------------
    
    // override these methods in subclass if your socket-type thang is not
    // a genuine Socket objct

    /** Extracts a readable InputStream from own socket */
    public InputStream getInputStream() throws IOException {
        return ((Socket)socket).getInputStream();
    }
    
    /** Extracts a writeable OutputStream from own socket */
    public OutputStream getOutputStream() throws IOException {
        return ((Socket)socket).getOutputStream();
    }

    /** closes the socket (or our socket-ish object) */
    public void closeSocket() throws IOException {
        ((Socket)socket).close();
    }

    /** method which gets called upon receipt of a GET.
     * You should override this
     */
    public abstract void on_GET() throws Exception;
    
    /** method which gets called upon receipt of a POST.
     * You should override this
     */
    public abstract void on_POST() throws Exception;

    // -------------------------------------------
    // END OF OVERRIDEABLES
    // -------------------------------------------

    /** Sets the HTTP status line (default 'HTTP/1.0 200 OK') */
    public void setStatus(String status) {
        this.status = status;
    }

    /** Sets the Content=Type header (default "text/plain") */
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    /** Sets the 'Server' header (default "aum's MiniHttpServer") */
    public void setServer(String serverType) {
        this.serverName = serverType;
    }

    /** Sets the full body of raw output to be written, replacing
     * the generated html tags
     */
    public void setRawOutput(String raw) {
        setRawOutput(raw.getBytes());
    }
    
    /** Sets the full body of raw output to be written, replacing
     * the generated html tags
     */
    public void setRawOutput(byte [] raw) {
        rawContentBytes = raw;
    }

    /** writes a String to output - normally you shouldn't need to call
     * this directly 
     */
    public void write(String raw) {
        write(raw.getBytes());
    }

    /** writes a byte array to output - normally you shouldn't need to call
     * this directly 
     */
    public void write(byte [] raw) {
        try {
            output.write(raw);
        } catch (Exception e) {
            System.out.print(e);
        }
    }

    /** processes the request, sends back response */
    public void run() {
        try {
            processRequest();
        }
        catch(Exception e) {
            e.printStackTrace();
            System.out.println(e);
        }
    }

    /** does all the work of processing the request */
    protected void processRequest() throws Exception {

        headerVars = new DupHashtable();
        urlVars = new DupHashtable();
        postVars = new DupHashtable();
        allVars = new DupHashtable();

        String line;

        // basic parsing of first req line
        String reqLine = br.readLine();
        printReq(reqLine);
        String [] reqBits = reqLine.split("\\s+", 3);
        reqType = reqBits[0];
        String [] reqFileBits = reqBits[1].split("[?]", 2);
        reqFile = reqFileBits[0];
        
        // check for URL variables
        if (reqFileBits.length > 1) {
            urlVars = parseVars(reqFileBits[1]);
        }

        // extract the 'request protocol', default to HTTP/1.0
        try {
            reqProto = reqBits[2];
        } catch (Exception e) {
            // workaround eepproxy bug
            reqFile = "/";
            reqProto = "HTTP/1.0";
        }

        // suck the headers
        while (true) {
            line = br.readLine();
            //System.out.println("Got header line: "+line);
            if (line.equals("")) {
                break;
            }
            String [] lineBits = line.split(":\\s+", 2);
            headerVars.put(lineBits[0], lineBits[1]);
        }
        //br.close();

        // GET is simple, all the work is already done
        if (reqType.equals("GET")) {
            on_GET();
        }

        // POST is more involved - need to read POST data and
        // break it up into fields
        else if (reqType.equals("POST")) {
            int postLen;
            String postLenStr;
            try {
                reqContentType = headerVars.get("Content-Type", 0, "");

                try {
                    postLenStr = headerVars.get("Content-Length", 0);
                } catch (Exception e) {
                    // damn opera
                    postLenStr = headerVars.get("Content-length", 0);
                }

                postLen = new Integer(postLenStr).intValue();
                postData = new char[postLen];

                //System.out.println("postLen="+postLen);
                for (int i=0; i<postLen; i++) {
                    int n = br.read();
                    postData[i] = (char)n;
                }
                //input.read(postData);
                postDataStr = new String(postData);
                //System.out.println("post data: '"+postDataStr+"'");
                
                // detect RPC
                if (reqContentType.equals("text/xml")
                    && postDataStr.startsWith("<?xml")
                )
                {
                    // yep, it's an rpc, fob off to handler
                    ByteArrayInputStream in = new ByteArrayInputStream(postDataStr.getBytes());
                    try {
                        byte [] resp = server.xmlRpcServer.execute(in);
                        setRawOutput(resp);
                    } catch (Exception e) {
                        e.printStackTrace();
                        throw e;
                    }
                } else if (reqContentType.startsWith("multipart/form-data")) {
                    // harder -parse as form
                    postVars = parsMultipartForm(reqContentType, postDataStr);
                    on_POST();
                } else {
                    // decode form vars
                    postVars = parseVars(postDataStr);
                    //System.out.println("postVars="+postVars);
                    on_POST();
                }
            } catch (Exception e) {
                e.printStackTrace();
                setStatus("HTTP/1.0 400 Missing Content-Length header");
                setRawOutput("Missing Content-Length header");
            }
        }

        write(status+"\r\n");
        write("Content-Type: "+contentType+"\r\n");
        write("Server: "+server+"\r\n");

        int contentLength;

        if (rawContentBytes == null) {
            // render out our html page
            String rawPage = toString();
            contentLength = rawPage.length();
            write("Content-Length: "+rawPage.length()+"\r\n");
            write("\r\n");
            write(rawPage);

        } else {
            // sending raw output
            write("Content-Length: "+rawContentBytes.length+"\r\n");
            write("\r\n");
            write(rawContentBytes);
        }

        output.flush();

        try {
            input.close();
            output.close();
            br.close();
            closeSocket();
        }
        catch(Exception e) {}
    }

    /** helper method which, given a filename, returns a guess at
     * a plausible mimetype for it
     */
    public String getContentType(String fileName) {
        return Mimetypes.guessType(fileName);
    }

    /** a crude rfc-1867-subset form decoder, for allowing file
     * uploads. For binary file upload, only supports
     * application/octet-stream and application/x-macbinary at present
     */
    public DupHashtable parsMultipartForm(String reqContentType, String postDataStr) {
        
        DupHashtable flds = new DupHashtable();
        
        //System.out.println("contenttype='"+reqContentType+"'");
        //System.out.println("raw postDataStr='"+postDataStr+"'");

        // determine the 'boundary' string separating the form items
        String boundary = reqContentType
            .split("multipart/form-data;\\s*")
            [1]
            .split("boundary\\=")
            [1];

        // convert it to escaped string
        boundary = "\\Q" + boundary + "\\E";

        //System.out.println("boundary='"+boundary+"'");

        // break up the raw post data into items
        String [] postItems = postDataStr.split(boundary);

        // try to extract a form variable from each item
        for (int i=0; i<postItems.length; i++) {
            
            String item = postItems[i];
            
            // strip the newline at start
            String [] items = item.split("^\\s+");
            item = items[items.length-1];
            
            // strip the trailing '--'
            try {
                item = item.substring(0, item.length()-4);
            } catch (StringIndexOutOfBoundsException e) {
                item = item.substring(0, item.length()-2);
            }
            postItems[i] = item;

            // break up item into headers+content
            String [] bits = item.split("\\r\\n\\r\\n", 2);
            String [] fldHdrs = bits[0].split("\\r\\n");
            String fldContent;
            try {
                // get item content
                fldContent = bits[1];
            } catch (ArrayIndexOutOfBoundsException e) {
                // no content
                fldContent = "";
            }

            //System.out.println("-----------------------");

            // go through the headers in search of 'name='
            for (int j=0; j<fldHdrs.length; j++) {
                //System.out.println("hdr: '"+fldHdrs[j]+"'");

                // break up header into its parts
                String [] hdrItems = fldHdrs[j].split(";\\s+");
                
                // go through each part in search of 'name='
                for (int k=0; k<hdrItems.length; k++) {
                    String hdrItem = hdrItems[k];

                    if (hdrItem.startsWith("name=\"")) {
                        // got a field name, add to our DupHashtable
                        String varName = hdrItem.substring(6, hdrItem.length()-1);
                        flds.put(varName, fldContent);
                        allVars.put(varName, fldContent);
                    }
                }
            }

            //System.out.println("data("+fldContent.length()+"): '"+fldContent+"'");
            
            //System.out.println("postItem='"+postItems[i]+"'");
            //byte [] b = fldContent.getBytes();
            //for (int j=0; j<b.length; j++) {
            //    System.out.print("" + b[j] + ", ");
            //}
            //System.out.println("");
        }

        return flds;

// ----------------------------
// raw sample of a posted form
        
/*
Content-Type: multipart/form-data; boundary=---------------------------121990404611892642131748622646
Content-Length: 1443

-----------------------------121990404611892642131748622646
Content-Disposition: form-data; name="cmd"

put
-----------------------------121990404611892642131748622646
Content-Disposition: form-data; name="type"

other
-----------------------------121990404611892642131748622646
Content-Disposition: form-data; name="title"


-----------------------------121990404611892642131748622646
Content-Disposition: form-data; name="path"


-----------------------------121990404611892642131748622646
Content-Disposition: form-data; name="mimetype"

text/plain
-----------------------------121990404611892642131748622646
Content-Disposition: form-data; name="keywords"


-----------------------------121990404611892642131748622646
Content-Disposition: form-data; name="summary"


-----------------------------121990404611892642131748622646
Content-Disposition: form-data; name="data"; filename="tmpd.lst"
Content-Type: application/octet-stream

/tmp/d
/tmp/d/d1
/tmp/d/d1/f4
/tmp/d/d1/f3
/tmp/d/f2
/tmp/d/f1

-----------------------------121990404611892642131748622646
Content-Disposition: form-data; name="privkey"


-----------------------------121990404611892642131748622646
Content-Disposition: form-data; name="submit"

Insert it
-----------------------------121990404611892642131748622646
Content-Disposition: form-data; name="rawdata"


-----------------------------121990404611892642131748622646--


**/

    }

    public DupHashtable parseVars(String raw) {
        DupHashtable h = new DupHashtable();
        
        URLDecoder u = new URLDecoder();
        String [] items = raw.split("[&]");
        String dec;
        for (int i=0; i<items.length; i++) {
            try {
                dec = u.decode(items[i], "ISO-8859-1");
                String [] items1 = dec.split("[=]",2);
                //System.out.println("parseVars: "+items1[0]+"="+items1[1]);
                h.put(items1[0], items1[1]);
                allVars.put(items1[0], items1[1]);
            } catch (Exception e) {
                    e.printStackTrace();
            }
        }
        
        return h;
    }

    public void printReq(String r) {
        System.out.println(r);
    }

    public Tag dumpVars() {

        Tag t = new Tag("table "
            +"width=90% cellspacing=0 cellpadding=4 border=1");
        tag(t, "tr")
            .nest("td colspan=2")
                .nest("big")
                    .nest("bold")
                        .raw("Dump of session vars");
        dumpVarsFor(t, headerVars, "HTTP header variables");
        dumpVarsFor(t, urlVars, "URL variables");
        dumpVarsFor(t, postVars, "POST variables");
        return t;
    }
    
    public void dumpVarsFor(Tag t, DupHashtable h, String heading) {
        
        Tag tr;
        Tag td;
        
        //System.out.println("dumpVarsFor: map="+h);
        
        // add the html headers
        tr = tag(t, "tr");
        t.nest("tr")
            .nest("td colspan=2 align=left")
                .nest("b")
                    .raw(heading);

        //System.out.println("dumpVarsFor: heading="+heading);
        Enumeration en = h.keys();
        while (en.hasMoreElements()) {
            String key = (String)en.nextElement();
            Vector vals = h.get(key);
            //System.out.println("dumpVarsFor: key="+key+" val="+vals);
            for (int i=0; i<vals.size(); i++) {
                tr = tag(t, "tr");
                tr.nest("td").raw(i == 0 ? key : "&nbsp");
                tr.nest("td").raw((String)vals.get(i));
            }
        }
    }

    /** creates an Tag object, and inserts it into
     * a parent Tag
     */
    public Tag tag(Tag parent, String tagopen) {
        
        return new Tag(parent, tagopen);
    }

    /** creates an Tag object */
    public Tag tag(String tagopen) {
        return new Tag(tagopen);
    }

}

