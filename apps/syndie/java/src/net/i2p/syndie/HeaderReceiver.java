package net.i2p.syndie;

import java.util.*;
import net.i2p.syndie.sml.SMLParser;

public class HeaderReceiver implements SMLParser.EventReceiver {
    private Properties _headers;
    public HeaderReceiver() { _headers = null; }
    public String getHeader(String name) { return (_headers != null ? _headers.getProperty(name) : null); }
    public void receiveHeader(String header, String value) { 
        if (_headers == null) _headers = new Properties();
        _headers.setProperty(header, value);
    }

    public void receiveAddress(String name, String schema, String protocol, String location, String anchorText) {}
    public void receiveArchive(String name, String description, String locationSchema, String location, String postingKey, String anchorText) {}
    public void receiveAttachment(int id, String anchorText) {}
    public void receiveBegin() {}
    public void receiveBlog(String name, String blogKeyHash, String blogPath, long blogEntryId, List blogArchiveLocations, String anchorText) {}
    public void receiveBold(String text) {}
    public void receiveCode(String text, String codeLocationSchema, String codeLocation) {}
    public void receiveCut(String summaryText) {}
    public void receiveEnd() {}
    public void receiveGT() {}
    public void receiveH1(String text) {}
    public void receiveH2(String text) {}
    public void receiveH3(String text) {}
    public void receiveH4(String text) {}
    public void receiveH5(String text) {}
    public void receiveHR() {}
    public void receiveHeaderEnd() {}
    public void receiveImage(String alternateText, int attachmentId) {}
    public void receiveItalic(String text) {}
    public void receiveLT() {}
    public void receiveLeftBracket() {}
    public void receiveLink(String schema, String location, String text) {}
    public void receiveNewline() {}
    public void receivePlain(String text) {}
    public void receivePre(String text) {}
    public void receiveQuote(String text, String whoQuoted, String quoteLocationSchema, String quoteLocation) {}
    public void receiveRightBracket() {}
    public void receiveUnderline(String text) {}
}
