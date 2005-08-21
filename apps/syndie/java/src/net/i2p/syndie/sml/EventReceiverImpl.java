package net.i2p.syndie.sml;

import java.util.List;

/**
 *
 */
public class EventReceiverImpl implements SMLParser.EventReceiver {
    public void receiveHeader(String header, String value) {
        System.out.println("Receive header [" + header + "] = [" + value + "]");
    }
    public void receiveLink(String schema, String location, String text) {
        System.out.println("Receive link [" + schema + "]/[" + location+ "]/[" + text + "]");
    }
    public void receiveBlog(String name, String blogKeyHash, String blogPath, long blogEntryId, 
                            List blogArchiveLocations, String anchorText) {
        System.out.println("Receive blog [" + name + "]/[" + blogKeyHash + "]/[" + blogPath
                           + "]/[" + blogEntryId + "]/[" + blogArchiveLocations + "]/[" + anchorText + "]");
    }
    public void receiveArchive(String name, String description, String locationSchema, String location, 
                               String postingKey, String anchorText) {
        System.out.println("Receive archive [" + name + "]/[" + description + "]/[" + locationSchema 
                           + "]/[" + location + "]/[" + postingKey + "]/[" + anchorText + "]");
    }
    public void receiveImage(String alternateText, int attachmentId) {
        System.out.println("Receive image [" + alternateText + "]/[" + attachmentId + "]");
    }
    public void receiveAddress(String name, String schema, String location, String anchorText) {
        System.out.println("Receive address [" + name + "]/[" + schema + "]/[" + location + "]/[" + anchorText+ "]");
    }
    public void receiveBold(String text) { System.out.println("Receive bold [" + text+ "]"); }
    public void receiveItalic(String text) { System.out.println("Receive italic [" + text+ "]"); }
    public void receiveUnderline(String text) { System.out.println("Receive underline [" + text+ "]"); }
    public void receiveQuote(String text, String whoQuoted, String quoteLocationSchema, String quoteLocation) {
        System.out.println("Receive quote [" + text + "]/[" + whoQuoted + "]/[" + quoteLocationSchema + "]/[" + quoteLocation + "]");
    }
    public void receiveCode(String text, String codeLocationSchema, String codeLocation) { 
        System.out.println("Receive code [" + text+ "]/[" + codeLocationSchema + "]/[" + codeLocation + "]");
    }
    public void receiveCut(String summaryText) { System.out.println("Receive cut [" + summaryText + "]"); }
    public void receivePlain(String text) { System.out.println("Receive plain [" + text + "]"); }
    public void receiveNewline() { System.out.println("Receive NL"); }
    public void receiveLT() { System.out.println("Receive LT"); }
    public void receiveGT() { System.out.println("Receive GT"); }
    public void receiveBegin() { System.out.println("Receive begin"); }
    public void receiveEnd() { System.out.println("Receive end"); }
    public void receiveHeaderEnd() { System.out.println("Receive header end"); }
    public void receiveLeftBracket() { System.out.println("Receive ["); }
    public void receiveRightBracket() { System.out.println("Receive ]"); }
    
    public void receiveH1(String text) {}
    public void receiveH2(String text) {}
    public void receiveH3(String text) {}
    public void receiveH4(String text) {}
    public void receiveH5(String text) {}
    public void receivePre(String text) {}
    public void receiveHR() {}
    public void receiveAttachment(int id, String anchorText) {}
}
