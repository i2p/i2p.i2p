// ========================================================================
// $Id: NCSARequestLog.java,v 1.35 2005/08/13 00:01:24 gregwilkins Exp $
// Copyright 2000-2004 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.mortbay.http;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Locale;
import java.util.TimeZone;

import javax.servlet.http.Cookie;

import org.apache.commons.logging.Log;
import org.mortbay.log.LogFactory;
import org.mortbay.util.DateCache;
import org.mortbay.util.LogSupport;
import org.mortbay.util.RolloverFileOutputStream;
import org.mortbay.util.StringUtil;


/* ------------------------------------------------------------ */
/** NCSA HTTP Request Log.
 *
 * Override log() to put in the requestor's destination hash,
 * instead of 127.0.0.1,
 * which is placed in the X-I2P-DestHash field in the request headers
 * by I2PTunnelHTTPServer.
 *
 * NCSA common or NCSA extended (combined) request log.
 * @version $Id: NCSARequestLog.java,v 1.35 2005/08/13 00:01:24 gregwilkins Exp $
 * @author Tony Thompson
 * @author Greg Wilkins
 */
public class I2PRequestLog extends NCSARequestLog
{
    /* ------------------------------------------------------------ */
    /** Constructor.
     */
    public I2PRequestLog()
    {
        super();
    }
    
    /* ------------------------------------------------------------ */
    /** Constructor. 
     * @param filename Filename, which can be in
     * rolloverFileOutputStream format
     * @see org.mortbay.util.RolloverFileOutputStream
     * @exception IOException 
     */
    public I2PRequestLog(String filename)
        throws IOException
    {
        super(filename);
    }

    /* ------------------------------------------------------------ */
    /** Log a request.
     * @param request The request
     * @param response The response to this request.
     * @param responseLength The bytes written to the response.
     */
    public void log(HttpRequest request,
                    HttpResponse response,
                    int responseLength)
    {
        try{
            // ignore ignorables
            if (_ignorePathMap != null &&
                _ignorePathMap.getMatch(request.getPath()) != null)
                return;

            // log the rest
            if (_fileOut==null)
                return;

            StringBuilder buf = new StringBuilder(160);
            
            String addr = request.getField("X-I2P-DestHash");
            if(addr != null)
                buf.append(addr).append(".i2p");
            else
                buf.append(request.getRemoteAddr());
            
            buf.append(" - ");
            String user = request.getAuthUser();
            buf.append((user==null)?"-":user);
            buf.append(" [");
            buf.append(_logDateCache.format(request.getTimeStamp()));
            buf.append("] \"");
            buf.append(request.getMethod());
            buf.append(' ');
            buf.append(request.getURI());
            buf.append(' ');
            buf.append(request.getVersion());
            buf.append("\" ");
            int status=response.getStatus();    
            buf.append((char)('0'+((status/100)%10)));
            buf.append((char)('0'+((status/10)%10)));
            buf.append((char)('0'+(status%10)));
            if (responseLength>=0)
            {
                buf.append(' ');
                if (responseLength>99999)
                    buf.append(Integer.toString(responseLength));
                else
                {
                    if (responseLength>9999) 
                        buf.append((char)('0'+((responseLength/10000)%10)));
                    if (responseLength>999) 
                        buf.append((char)('0'+((responseLength/1000)%10)));
                    if (responseLength>99) 
                        buf.append((char)('0'+((responseLength/100)%10)));
                    if (responseLength>9) 
                        buf.append((char)('0'+((responseLength/10)%10)));
                    buf.append((char)('0'+(responseLength%10)));
                }
                buf.append(' ');
            }
            else
                buf.append(" - ");

            String log =buf.toString();
            synchronized(_writer)
            {
                _writer.write(log);
                if (isExtended())
                {
                    logExtended(request,response,_writer);
                    if (!getLogCookies())
                        _writer.write(" -");
                }
                
                if (getLogCookies())
                {
                    Cookie[] cookies = request.getCookies();
                    if (cookies==null || cookies.length==0)
                        _writer.write(" -");
                    else
                    {
                        _writer.write(" \"");
                        for (int i=0;i<cookies.length;i++)
                        {
                            if (i!=0)
                                _writer.write(';');
                            _writer.write(cookies[i].getName());
                            _writer.write('=');
                            _writer.write(cookies[i].getValue());
                        }
                        _writer.write("\"");
                    }
                }
                
                if (getLogLatency())
                    _writer.write(" "+(System.currentTimeMillis()-request.getTimeStamp()));
                
                _writer.write(StringUtil.__LINE_SEPARATOR);
                _writer.flush();
            }
        }
        catch(IOException e)
        {
            log.warn(LogSupport.EXCEPTION,e);
        }
    }

}

