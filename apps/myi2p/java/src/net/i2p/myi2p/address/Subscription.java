package net.i2p.myi2p.address;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.Date;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.DataFormatException;

/**
 * Contains the preferences for subscribing to a particular peer's address 
 * book.
 */
public class Subscription {
    private I2PAppContext _context;
    private int _queryFrequencyMinutes;
    private long _lastQueryAttempt;
    private long _lastQuerySuccess;
    
    /** no subscription more often than 4 times a day */
    public static final int MIN_FREQUENCY = 6*60*60*1000;
    
    public Subscription(I2PAppContext context) {
        _context = context;
    }
    
    /** how often do we want to query the peer (in minutes) */
    public int getQueryFrequencyMinutes() { return _queryFrequencyMinutes; }
    public void setQueryFrequencyMinutes(int freq) { _queryFrequencyMinutes = freq; }
    
    /** when did we last successfully query the peer */
    public long getLastQuerySuccess() { return _lastQuerySuccess; }
    public void setLastQuerySuccess(long when) { _lastQuerySuccess = when; }
    
    /** when did we last attempt to query the peer */
    public long getLastQueryAttempt() { return _lastQueryAttempt; }
    public void setLastQueryAttempt(long when) { _lastQueryAttempt = when; }
        
    /** load the data from the stream */
    public void read(InputStream in) throws IOException {
        try {
            int freq = (int)DataHelper.readLong(in, 2);
            Date attempt = DataHelper.readDate(in);
            Date success = DataHelper.readDate(in);
            _queryFrequencyMinutes = (freq < MIN_FREQUENCY ? MIN_FREQUENCY : freq);
            _lastQueryAttempt = (attempt != null ? attempt.getTime() : -1);
            _lastQuerySuccess = (success != null ? success.getTime() : -1);
        } catch (DataFormatException dfe) {
            throw new IOException("Corrupt subscription: " + dfe.getMessage());
        }
    }
    
    /** persist the data to the stream */
    public void write(OutputStream out) throws IOException {
        try {
            DataHelper.writeLong(out, 2, _queryFrequencyMinutes);
            DataHelper.writeDate(out, new Date(_lastQueryAttempt));
            DataHelper.writeDate(out, new Date(_lastQuerySuccess));
        } catch (DataFormatException dfe) {
            throw new IOException("Corrupt subscription: " + dfe.getMessage());
        }
    }
}
