package net.i2p.myi2p.address;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Component for loading and storing the service data to disk
 *
 */
public class AddressBookServiceData {
    private I2PAppContext _context;
    private Log _log;
    private AddressBook _addressBook;
    private Map _activityLog;
    private Exception _error;
    private String _errorMessage;
    
    public AddressBookServiceData(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(AddressBookServiceData.class);
        _addressBook = null;
        _activityLog = null;
        _error = null;
        _errorMessage = null;
    }
    
    public AddressBook getAddressBook() { return _addressBook; }
    public void setAddressBook(AddressBook book) { _addressBook = book; }
    public Map getActivityLog() { return _activityLog; }
    public void setActivityLog(Map log) { _activityLog = log; }
    
    public Exception getError() { return _error; }
    public String getErrorMessage() { return _errorMessage; }
    
    public void load(File from) {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(from);
            AddressBook addressBook = new AddressBook(_context);
            addressBook.read(fis);
            _addressBook = addressBook;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Address book: " + addressBook);
            Properties props = DataHelper.readProperties(fis);
            Map log = new HashMap(props.size());
            for (Iterator iter = props.keySet().iterator(); iter.hasNext(); ) {
                String key = (String)iter.next();
                String event = props.getProperty(key);
                long when = 0;
                try {
                    when = Long.parseLong(key);
                    while (log.containsKey(new Long(when)))
                        when++;
                    log.put(new Long(when), event);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Activity log: on " + new Date(when) + ": " + event);
                } catch (NumberFormatException nfe) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Corrupt activity log entry: when=" + key, nfe);
                }
            }
            _activityLog = log;
        } catch (Exception e) {
            _error = e;
            _errorMessage = "Error reading the address book from " + from;
        }
    }
    
    public void store(File to) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(to);
            _addressBook.write(fos);
            Properties props = new Properties();
            for (Iterator iter = _activityLog.keySet().iterator(); iter.hasNext(); ) {
                Long when = (Long)iter.next();
                String msg = (String)_activityLog.get(when);
                props.setProperty(when.toString(), msg);
            }
            DataHelper.writeProperties(fos, props);
        } catch (Exception e) {
            _error = e;
            _errorMessage = "Error writing the address book to " + to;
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
    }
}
