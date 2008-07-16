package net.i2p.syndie.sml;

import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 *
 */
public class EventReceiverImpl implements SMLParser.EventReceiver {
    protected I2PAppContext _context;
    private Log _log;
    
    public EventReceiverImpl(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(EventReceiverImpl.class);
    }
    public void receiveHeader(String header, String value) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive header [" + header + "] = [" + value + "]");
    }
    public void receiveLink(String schema, String location, String text) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive link [" + schema + "]/[" + location+ "]/[" + text + "]");
    }
    public void receiveBlog(String name, String blogKeyHash, String blogPath, long blogEntryId, 
                            List blogArchiveLocations, String anchorText) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive blog [" + name + "]/[" + blogKeyHash + "]/[" + blogPath
                           + "]/[" + blogEntryId + "]/[" + blogArchiveLocations + "]/[" + anchorText + "]");
    }
    public void receiveArchive(String name, String description, String locationSchema, String location, 
                               String postingKey, String anchorText) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive archive [" + name + "]/[" + description + "]/[" + locationSchema 
                           + "]/[" + location + "]/[" + postingKey + "]/[" + anchorText + "]");
    }
    public void receiveImage(String alternateText, int attachmentId) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive image [" + alternateText + "]/[" + attachmentId + "]");
    }
    public void receiveAddress(String name, String schema, String protocol, String location, String anchorText) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive address [" + name + "]/[" + schema + "]/[" + location + "]/[" + anchorText+ "]");
    }
    public void receiveBold(String text) { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive bold [" + text+ "]"); 
    }
    public void receiveItalic(String text) { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive italic [" + text+ "]"); 
    }
    public void receiveUnderline(String text) { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive underline [" + text+ "]"); 
    }
    public void receiveQuote(String text, String whoQuoted, String quoteLocationSchema, String quoteLocation) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive quote [" + text + "]/[" + whoQuoted + "]/[" + quoteLocationSchema + "]/[" + quoteLocation + "]");
    }
    public void receiveCode(String text, String codeLocationSchema, String codeLocation) { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive code [" + text+ "]/[" + codeLocationSchema + "]/[" + codeLocation + "]");
    }
    public void receiveCut(String summaryText) { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive cut [" + summaryText + "]"); 
    }
    public void receivePlain(String text) { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive plain [" + text + "]"); 
    }
    public void receiveNewline() { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive NL"); 
    }
    public void receiveLT() { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive LT"); 
    }
    public void receiveGT() { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive GT"); 
    }
    public void receiveBegin() { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive begin"); 
    }
    public void receiveEnd() { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive end"); 
    }
    public void receiveHeaderEnd() { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive header end"); 
    }
    public void receiveLeftBracket() { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive ["); 
    }
    public void receiveRightBracket() { 
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Receive ]"); 
    }
    
    public void receiveH1(String text) {}
    public void receiveH2(String text) {}
    public void receiveH3(String text) {}
    public void receiveH4(String text) {}
    public void receiveH5(String text) {}
    public void receivePre(String text) {}
    public void receiveHR() {}
    public void receiveAttachment(int id, int thumbnail, String anchorText) {}
}
