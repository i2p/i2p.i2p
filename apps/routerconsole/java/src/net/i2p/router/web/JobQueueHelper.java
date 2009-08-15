package net.i2p.router.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;


public class JobQueueHelper extends HelperBase {
    public JobQueueHelper() {}
    
    public String getJobQueueSummary() {
        try {
            if (_out != null) {
                _context.jobQueue().renderStatusHTML(_out);
                return "";
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(32*1024);
                _context.jobQueue().renderStatusHTML(new OutputStreamWriter(baos));
                return new String(baos.toByteArray());
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return "";
        }
    }
}
