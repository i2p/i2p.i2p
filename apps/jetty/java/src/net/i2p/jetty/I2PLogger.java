// ========================================================================
// Copyright 2004-2005 Mort Bay Consulting Pty. Ltd.
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

package net.i2p.jetty;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

import org.mortbay.jetty.Server;
import org.mortbay.log.Logger;

/**
 * Modified from Jetty 6.1.26 StdErrLog.java and Slf4jLog.java
 *
 * Usage: org.mortbay.log.Log.setLog(new I2PLogger(ctx));
 *
 * @since Jetty 6
 */
public class I2PLogger implements Logger
{    
    private final Log _log;
    
    StringBuilder _buffer = new StringBuilder();
    
    public I2PLogger()
    {
        this(I2PAppContext.getGlobalContext());
    }
    
    public I2PLogger(I2PAppContext ctx)
    {
        _log = ctx.logManager().getLog(Server.class);
        if (System.getProperty("DEBUG") != null)
            setDebugEnabled(true);
    }
    
    public boolean isDebugEnabled()
    {
        return _log.shouldLog(Log.DEBUG);
    }
    
    public void setDebugEnabled(boolean enabled)
    {
        if (enabled)
            _log.setMinimumPriority(Log.DEBUG);
        else
            // LogManager.getDefaultLimit() returns a String, not worth it
            _log.setMinimumPriority(Log.ERROR);
    }
    
    public void info(String msg,Object arg0, Object arg1)
    {
        if (arg0 == null && arg1 == null) {
            _log.info(msg);
        } else if (_log.shouldLog(Log.INFO)) {
            synchronized(_buffer) {
                format(msg,arg0,arg1);
                _log.info(_buffer.toString());
            }
        }
    }
    
    public void debug(String msg,Throwable th)
    {
        _log.debug(msg,th);
    }
    
    public void debug(String msg,Object arg0, Object arg1)
    {
        if (arg0 == null && arg1 == null) {
            _log.debug(msg);
        } else if (_log.shouldLog(Log.DEBUG)) {
            synchronized(_buffer) {
                format(msg,arg0,arg1);
                _log.debug(_buffer.toString());
            }
        }
    }
    
    public void warn(String msg,Object arg0, Object arg1)
    {
        if (arg0 == null && arg1 == null) {
            _log.warn(msg);
        } else if (_log.shouldLog(Log.WARN)) {
            synchronized(_buffer) {
                format(msg,arg0,arg1);
                _log.warn(_buffer.toString());
            }
        }
    }
    
    public void warn(String msg, Throwable th)
    {
        // This doesn't cover ClassNotFoundException, etc.
        //if (th instanceof RuntimeException || th instanceof Error)
            _log.error(msg, th);
        //else
        //    _log.warn(msg,th);
    }
    
    private void format(String msg, Object arg0, Object arg1)
    {
        _buffer.setLength(0);
        int i0=msg==null?-1:msg.indexOf("{}");
        int i1=i0<0?-1:msg.indexOf("{}",i0+2);
        
        if (i0>=0)
        {
            format(msg.substring(0,i0));
            format(String.valueOf(arg0==null?"null":arg0));
            
            if (i1>=0)
            {
                format(msg.substring(i0+2,i1));
                format(String.valueOf(arg1==null?"null":arg1));
                format(msg.substring(i1+2));
            }
            else
            {
                format(msg.substring(i0+2));
                if (arg1!=null)
                {
                    _buffer.append(' ');
                    format(String.valueOf(arg1));
                }
            }
        }
        else
        {
            format(msg);
            if (arg0!=null)
            {
                _buffer.append(' ');
                format(String.valueOf(arg0));
            }
            if (arg1!=null)
            {
                _buffer.append(' ');
                format(String.valueOf(arg1));
            }
        }
    }
    
    private void format(String msg)
    {
        if (msg == null)
            _buffer.append("null");
        else
            _buffer.append(msg);
    }

    public Logger getLogger(String name)
    {
            return this;
    }
    
    @Override
    public String toString()
    {
        return "I2PLogger";
    }
    

}
