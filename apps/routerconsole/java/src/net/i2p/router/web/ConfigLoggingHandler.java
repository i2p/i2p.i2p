package net.i2p.router.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Handler to deal with form submissions from the logging config form and act
 * upon the values.
 *
 */
public class ConfigLoggingHandler extends FormHandler {
    private boolean _shouldSave;
    private String _levels;
    private String _defaultLevel;
    private String _filename;
    private String _recordFormat;
    private String _dateFormat;
    private String _fileSize;
    
    protected void processForm() {
        if (_shouldSave) {
            saveChanges();
        } else {
            // noop
        }
    }
    
    public void setShouldsave(String moo) { _shouldSave = true; }
    
    public void setLevels(String levels) {
        _levels = (levels != null ? levels.trim() : null);
    }
    public void setDefaultloglevel(String level) {
        _defaultLevel = (level != null ? level.trim() : null);
    }
    public void setLogfilename(String filename) {
        _filename = (filename != null ? filename.trim() : null);
    }
    public void setLogformat(String format) {
        _recordFormat = (format != null ? format.trim() : null);
    }
    public void setLogdateformat(String format) {
        _dateFormat = (format != null ? format.trim() : null);
    }
    public void setLogfilesize(String size) {
        _fileSize = (size != null ? size.trim() : null);
    }
    
    /**
     * The user made changes to the config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        boolean shouldSave = false;
        
        if (_levels != null) {
            try {
                Properties props = new Properties();
                props.load(new ByteArrayInputStream(_levels.getBytes()));
                _context.logManager().setLimits(props);
                shouldSave = true;
                addFormNotice("Log limits updated");
            } catch (IOException ioe) {
                _context.logManager().getLog(ConfigLoggingHandler.class).error("Error reading from the props?", ioe);
                addFormError("Error updating the log limits - levels not valid");
            }
        } else {
            _context.logManager().setLimits(null);
            addFormNotice("Log limits cleared");
        }
          
        if (_defaultLevel != null) {
            String oldDefault = _context.logManager().getDefaultLimit();
            if (_defaultLevel.equals(oldDefault)) {
                // noop
            } else {
                shouldSave = true;
                _context.logManager().setDefaultLimit(_defaultLevel);
                addFormNotice("Default log level updated from " + oldDefault + " to " + _defaultLevel);
            }
        }
        
        if (_dateFormat != null) {
            boolean valid = _context.logManager().setDateFormat(_dateFormat);
            if (valid) {
                shouldSave = true;
                addFormNotice("Date format updated");
            } else {
                addFormError("Specified date format is not valid (" + _dateFormat + ") - not updated");
            }
        }
        
        if (_fileSize != null) {
            int newBytes = _context.logManager().getFileSize(_fileSize);
            int oldBytes = _context.logManager().getFileSize();
            if (newBytes > 0) {
                if (oldBytes != newBytes) {
                    _context.logManager().setFileSize(newBytes);
                    shouldSave = true;
                    addFormNotice("File size updated");
                } 
            } else {
                addFormError("Specified file size limit is not valid (" + _fileSize + ") - not updated");
            }
        }
        
        if ( (_filename != null) && (_filename.trim().length() > 0) ) {
            _filename = _filename.trim();
            String old = _context.logManager().getBaseLogfilename();
            if ( (old != null) && (_filename.equals(old)) ) {
                // noop - don't update since its the same
            } else {
                shouldSave = true;
                _context.logManager().setBaseLogfilename(_filename);
                addFormNotice("Log file name pattern updated to " + _filename 
                              + " (note: will not take effect until next rotation)");
            }
        }
        
        if ( (_recordFormat != null) && (_recordFormat.trim().length() > 0) ) {
            _recordFormat = _recordFormat.trim();
            String old = new String(_context.logManager().getFormat());
            if (_recordFormat.equalsIgnoreCase(old)) {
                // noop - no change
            } else {
                char fmt[] = new char[_recordFormat.length()];
                for (int i = 0; i < fmt.length; i++) 
                    fmt[i] = _recordFormat.charAt(i);
                _context.logManager().setFormat(fmt);
                shouldSave = true;
                addFormNotice("Log record format updated");
            }
        }
        
        if (shouldSave) {
            boolean saved = _context.logManager().saveConfig();

            if (saved) 
                addFormNotice("Log configuration saved and applied successfully");
            else
                addFormNotice("Error saving the configuration (applied but not saved) - please see the error logs");
        }
    }
}
