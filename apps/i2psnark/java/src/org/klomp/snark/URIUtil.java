//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.klomp.snark;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;

import net.i2p.data.DataHelper;


/** URI Holder.
 * This class assists with the decoding and encoding or HTTP URI's.
 * It differs from the java.net.URL class as it does not provide
 * communications ability, but it does assist with query string
 * formatting.
 * <P>UTF-8 encoding is used by default for % encoded characters. This
 * may be overridden with the org.eclipse.jetty.util.URI.charset system property.
 * see UrlEncoded
 * 
 * I2P modded from Jetty 8.1.15
 * @since 0.9.15, moved from web in 0.9.49
 */
public class URIUtil
{

    /** Encode a URI path.
     * This is the same encoding offered by URLEncoder, except that
     * the '/' character is not encoded.
     * @param path The path the encode
     * @return The encoded path
     */
    public static String encodePath(String path)
    {
        if (path==null || path.length()==0)
            return path;
        
        StringBuilder buf = encodePath(null,path);
        return buf==null?path:buf.toString();
    }
        
    /** Encode a URI path.
     *
     *  Somewhat oddly, this encodes all chars &gt;= 0x80 if buf is null, (strict RFC 2396)
     *  but only the control, space, and special chars if buf is non-null.
     *
     * @param path The path the encode
     * @param buf StringBuilder to encode path into (or null)
     * @return The StringBuilder or null if no substitutions required.
     */
    public static StringBuilder encodePath(StringBuilder buf, String path)
    {
        byte[] bytes=null;
        if (buf==null)
        {
        loop:
            for (int i=0;i<path.length();i++)
            {
                char c=path.charAt(i);
                switch(c)
                {
                    case '%':
                    case '?':
                    case ';':
                    case '#':
                    case '\'':
                    case '"':
                    case '<':
                    case '>':
                    case ' ':
                    case ':':
                    case '[':
                    case ']':
                    case '&':
                    case '|':
                    case '\\':
                        buf=new StringBuilder(path.length()*2);
                        break loop;
                    default:
                        if (c >= 0x7f || c <= 0x1f)
                        {
                            bytes = DataHelper.getUTF8(path);
                            buf=new StringBuilder(path.length()*2);
                            break loop;
                        }
                       
                }
            }
            if (buf==null)
                return null;
        }
        
        //synchronized(buf)
        //{
            if (bytes!=null)
            {
                for (int i=0;i<bytes.length;i++)
                {
                    byte c=bytes[i];       
                    switch(c)
                    {
                      case '%':
                          buf.append("%25");
                          continue;
                      case '?':
                          buf.append("%3F");
                          continue;
                      case ';':
                          buf.append("%3B");
                          continue;
                      case '#':
                          buf.append("%23");
                          continue;
                      case '"':
                          buf.append("%22");
                          continue;
                      case '\'':
                          buf.append("%27");
                          continue;
                      case '<':
                          buf.append("%3C");
                          continue;
                      case '>':
                          buf.append("%3E");
                          continue;
                      case ' ':
                          buf.append("%20");
                          continue;
                      case 0x7f:
                          buf.append("%7F");
                          continue;
                      case ':':
                          buf.append("%3A");
                          continue;
                      case '[':
                          buf.append("%5B");
                          continue;
                      case ']':
                          buf.append("%5D");
                          continue;
                      // not strictly required but this is probably HTML output
                      case '&':
                          buf.append("%26");
                          continue;
                      case '|':
                          buf.append("%7C");
                          continue;
                      // browsers convert this to /
                      case '\\':
                          buf.append("%5C");
                          continue;
                      default:
                          if (c <= 0x1f) // includes negative
                              toHex(c,buf);
                          else
                              buf.append((char)c);
                          continue;
                    }
                }
                
            }
            else
            {
                for (int i=0;i<path.length();i++)
                {
                    char c=path.charAt(i);       
                    switch(c)
                    {
                        case '%':
                            buf.append("%25");
                            continue;
                        case '?':
                            buf.append("%3F");
                            continue;
                        case ';':
                            buf.append("%3B");
                            continue;
                        case '#':
                            buf.append("%23");
                            continue;
                        case '"':
                            buf.append("%22");
                            continue;
                        case '\'':
                            buf.append("%27");
                            continue;
                        case '<':
                            buf.append("%3C");
                            continue;
                        case '>':
                            buf.append("%3E");
                            continue;
                        case ' ':
                            buf.append("%20");
                            continue;
                        case ':':
                            buf.append("%3A");
                            continue;
                        case '[':
                            buf.append("%5B");
                            continue;
                        case ']':
                            buf.append("%5D");
                            continue;
                        case '&':
                            buf.append("%26");
                            continue;
                        case '|':
                            buf.append("%7C");
                            continue;
                        case '\\':
                            buf.append("%5C");
                            continue;
                        default:
                            if (c <= 0x1f || (c >= 0x7f && c <= 0x9f) || Character.isSpaceChar(c))
                                toHex(c,buf);
                            else
                              buf.append(c);
                            continue;
                    }
                }
            }
        //}

        return buf;
    }
    
    /**
     *  Modded from Jetty TypeUtil
     */
    private static void toHex(byte b, StringBuilder buf)
    {
            buf.append('%');
            int d=0xf&((0xF0&b)>>4);
            buf.append((char)((d>9?('A'-10):'0')+d));
            d=0xf&b;
            buf.append((char)((d>9?('A'-10):'0')+d));
    }
    
    /**
     *  UTF-8
     */
    private static void toHex(char c, StringBuilder buf)
    {
            if (c > 0x7f) {
                byte[] b = DataHelper.getUTF8(Character.toString(c));
                for (int i = 0; i < b.length; i++) {
                    toHex(b[i], buf);
                }
            } else {
                toHex((byte) c, buf);
            }
    }
}



