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

import java.nio.channels.ClosedChannelException;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

import org.slf4j.Logger;
import org.slf4j.Marker;

import org.eclipse.jetty.server.Server;

/**
 * Modified from Jetty 6.1.26 StdErrLog.java and Slf4jLog.java
 *
 * Usage: org.eclipse.log.Log.setLog(new I2PLogger(ctx));
 *
 * @since Jetty 6
 */
public class I2PLogger implements Logger
{    
    private final Log _log;
    
    private final StringBuilder _buffer = new StringBuilder();
    
    //static {
        // So people don't wonder where the logs went
        //System.out.println("INFO: Jetty " + Server.getVersion() + " logging to I2P logs using class " + Server.class.getName());
    //}

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
        } else if (arg0 != null && arg1 == null && arg0 instanceof Throwable) {
            _log.info(msg, (Throwable) arg0);
        } else if (_log.shouldLog(Log.INFO)) {
            synchronized(_buffer) {
                format(msg,arg0,arg1);
                if (arg1 != null && arg1 instanceof Throwable)
                    _log.info(_buffer.toString(), (Throwable) arg1);
                else
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
        } else if (arg0 != null && arg1 == null && arg0 instanceof Throwable) {
            _log.debug(msg, (Throwable) arg0);
        } else if (_log.shouldLog(Log.DEBUG)) {
            synchronized(_buffer) {
                format(msg,arg0,arg1);
                if (arg1 != null && arg1 instanceof Throwable)
                    _log.debug(_buffer.toString(), (Throwable) arg1);
                else
                    _log.debug(_buffer.toString());
            }
        }
    }
    
    public void warn(String msg,Object arg0, Object arg1)
    {
        if (arg0 == null && arg1 == null) {
            _log.warn(msg);
        } else if (arg0 != null && arg1 == null && arg0 instanceof Throwable) {
            warn(msg, (Throwable) arg0);
        } else if (_log.shouldLog(Log.WARN)) {
            synchronized(_buffer) {
                format(msg,arg0,arg1);
                if (arg1 != null && arg1 instanceof Throwable)
                    _log.warn(_buffer.toString(), (Throwable) arg1);
                else
                    _log.warn(_buffer.toString());
            }
        }
    }
    
    public void warn(String msg, Throwable th)
    {
        // some of these are serious, some aren't
        // no way to get it right
        if (th != null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(msg, th);
            else if (!(th instanceof ClosedChannelException))
                _log.logAlways(Log.WARN, msg + ": " + th);
        } else {
            _log.logAlways(Log.WARN, msg);
        }
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
    
    /**
     *  @since Jetty 7
     */
    public void ignore(Throwable ignored)
    {
            debug("IGNORED", ignored);
    }
    
    /**
     *  @since Jetty 7
     */
    public void debug(Throwable thrown)
    {
            debug("", thrown);
    }
    
    /**
     *  @since Jetty 7
     */
    public void debug(String msg, Object... args)
    {
            Object a1 = args.length > 0 ? args[0] : null;
            Object a2 = args.length > 1 ? args[1] : null;
            debug(msg, a1, a2);
    }
    
    /**
     *  @since Jetty 7
     */
    public void info(Throwable thrown)
    {
            info("", thrown);
    }
    
    /**
     *  @since Jetty 7
     */
    public void info(String msg, Object... args)
    {
            Object a1 = args.length > 0 ? args[0] : null;
            Object a2 = args.length > 1 ? args[1] : null;
            info(msg, a1, a2);
    }
    
    /**
     *  @since Jetty 7
     */
    public void info(String msg, Throwable th)
    {
        _log.info(msg,th);
    }

    /**
     *  @since Jetty 7
     */
    public void warn(Throwable thrown)
    {
            warn("", thrown);
    }
    
    /**
     *  @since Jetty 7
     */
    public void warn(String msg, Object... args)
    {
            Object a1 = args.length > 0 ? args[0] : null;
            Object a2 = args.length > 1 ? args[1] : null;
            warn(msg, a1, a2);
    }

    /**
     *  @since Jetty 7
     */
    public String getName() {
        return "net.i2p.jetty.I2PLogger";
    }

    /**
     *  @since Jetty 9
     */
    public void debug(String msg, long arg) {
        debug(msg, Long.valueOf(arg), null);
    }

    /**
     *  All of the following
     *  @since Jetty 12
     */
    public void debug(Marker marker, String msg) { debug(msg); }
    public void debug(Marker marker, String format, Object arg) { debug(format, arg); }
    public void debug(Marker marker, String format, Object... arguments) { debug(format, arguments); }
    public void debug(Marker marker, String format, Object arg1, Object arg2) { debug(format, arg1, arg2); }
    public void debug(Marker marker, String msg, Throwable t) { debug(msg, t); }

    public void error(Marker marker, String msg) { error(msg); }
    public void error(Marker marker, String format, Object arg) { error(format, arg); }
    public void error(Marker marker, String format, Object... arguments) { error(format, arguments); }
    public void error(Marker marker, String format, Object arg1, Object arg2) { error(format, arg1, arg2); }
    public void error(Marker marker, String msg, Throwable t) { error(msg, t); }

    public void info(Marker marker, String msg) { info(msg); }
    public void info(Marker marker, String format, Object arg) { info(format, arg); }
    public void info(Marker marker, String format, Object... arguments) { info(format, arguments); }
    public void info(Marker marker, String format, Object arg1, Object arg2) { info(format, arg1, arg2); }
    public void info(Marker marker, String msg, Throwable t) { info(msg, t); }

    public void trace(Marker marker, String msg) { trace(msg); }
    public void trace(Marker marker, String format, Object arg) { trace(format, arg); }
    public void trace(Marker marker, String format, Object... arguments) { trace(format, arguments); }
    public void trace(Marker marker, String format, Object arg1, Object arg2) { trace(format, arg1, arg2); }
    public void trace(Marker marker, String msg, Throwable t) { trace(msg, t); }

    public void warn(Marker marker, String msg) { warn(msg); }
    public void warn(Marker marker, String format, Object arg) { warn(format, arg); }
    public void warn(Marker marker, String format, Object... arguments) { warn(format, arguments); }
    public void warn(Marker marker, String format, Object arg1, Object arg2) { warn(format, arg1, arg2); }
    public void warn(Marker marker, String msg, Throwable t) { warn(msg, t); }

    public boolean isDebugEnabled(Marker marker) { return isDebugEnabled(); }
    public boolean isErrorEnabled(Marker marker) { return isErrorEnabled(); }
    public boolean isInfoEnabled(Marker marker) { return isInfoEnabled(); }
    public boolean isTraceEnabled(Marker marker) { return isTraceEnabled(); }
    public boolean isWarnEnabled(Marker marker) { return isWarnEnabled(); }

    public boolean isErrorEnabled() { return _log.shouldError(); }
    public boolean isInfoEnabled() { return _log.shouldInfo(); }
    public boolean isTraceEnabled() { return _log.shouldDebug(); }
    public boolean isWarnEnabled() { return _log.shouldWarn(); }

    public void trace(String msg) { debug(msg); }
    public void trace(String format, Object arg) { debug(format, arg); }
    public void trace(String format, Object... arguments) { debug(format, arguments); }
    public void trace(String format, Object arg1, Object arg2) { debug(format, arg1, arg2); }
    public void trace(String msg, Throwable t) { debug(msg, t); }

    public void debug(String msg, Object arg) { debug(msg, arg, null); }
    public void info(String msg, Object arg) { info(msg, arg, null); }
    public void warn(String msg, Object arg) { warn(msg, arg, null); }

    public void debug(String msg) { debug(msg, null, null); }
    public void info(String msg) { info(msg, null, null); }
    public void warn(String msg) { warn(msg, null, null); }

    
    public void error(String msg,Object arg0, Object arg1)
    {
        if (arg0 == null && arg1 == null) {
            _log.error(msg);
        } else if (arg0 != null && arg1 == null && arg0 instanceof Throwable) {
            error(msg, (Throwable) arg0);
        } else {
            format(msg,arg0,arg1);
            if (arg1 != null && arg1 instanceof Throwable)
                _log.error(_buffer.toString(), (Throwable) arg1);
            else
                _log.error(_buffer.toString());
        }
    }
    
    public void error(String msg)
    {
        _log.error(msg);
    }
    
    public void error(String msg, Throwable th)
    {
        _log.error(msg, th);
    }

    public void error(Throwable thrown)
    {
        error("", thrown);
    }
    
    public void error(String msg, Object arg)
    {
        error(msg, arg, null);
    }

    public void error(String msg, Object... args)
    {
        Object a1 = args.length > 0 ? args[0] : null;
        Object a2 = args.length > 1 ? args[1] : null;
        error(msg, a1, a2);
    }
}
