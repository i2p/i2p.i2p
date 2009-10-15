package net.i2p.router.web;

import java.io.IOException;


public class ProfilesHelper extends HelperBase {
    public ProfilesHelper() {}
    
    /** @return empty string, writes directly to _out */
    public String getProfileSummary() {
        try {
            ProfileOrganizerRenderer rend = new ProfileOrganizerRenderer(_context.profileOrganizer(), _context);
            rend.renderStatusHTML(_out);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return "";
    }
    
    /** @return empty string, writes directly to _out */
    public String getShitlistSummary() {
        try {
            _context.shitlist().renderStatusHTML(_out);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return "";
    }
}
