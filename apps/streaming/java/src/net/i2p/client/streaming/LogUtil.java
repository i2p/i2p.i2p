package net.i2p.client.streaming;

import net.i2p.util.Log;

class LogUtil {
    private LogUtil() {}
    
    /**
     * logs a loop when closing a resource with level WARN
     * @param desc vararg description
     * @param log logger for the class we're intersted in
     */
    static void logCloseLoop(Log log, Object... desc) {
        logCloseLoop(log, Log.WARN, desc);
    }
    
    /**
     * Logs a close loop when closing a resource
     * @param desc vararg description of the resource
     * @param log logger to use
     * @param level level at which to log
     */
    static void logCloseLoop(Log log, int level, Object... desc) {
        if (!log.shouldLog(level)) 
            return;
        
        // catenate all toString()s
        String descString = "close() loop in";
        for (Object o : desc) {
            descString += " ";
            descString += String.valueOf(o);
        }
        
        Exception e = new Exception("check stack trace");
        log.log(level,descString,e);
    }
}
