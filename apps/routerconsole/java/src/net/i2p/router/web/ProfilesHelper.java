package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;

import net.i2p.router.RouterContext;

public class ProfilesHelper extends HelperBase {
    public ProfilesHelper() {}
    
    public String getProfileSummary() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(16*1024);
        try {
            _context.profileOrganizer().renderStatusHTML(new OutputStreamWriter(baos));
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return new String(baos.toByteArray());
    }
    
    public String getShitlistSummary() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4*1024);
        try {
            _context.shitlist().renderStatusHTML(new OutputStreamWriter(baos));
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return new String(baos.toByteArray());
    }
}
