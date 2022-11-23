package net.i2p.router.web.helpers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Properties;

import net.i2p.data.DataHelper;
import net.i2p.router.web.FormHandler;
import net.i2p.util.LogManager;

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
    private String _newLogClass;
    private String _newLogLevel = "WARN";
    private boolean _logCompress;
    
    @Override
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

    /** @since 0.9.57 */
    public void setLogcompress(String foo) {
        _logCompress = true;
    }

    /** @since 0.8.1 */
    public void setNewlogclass(String s) {
        if (s != null && s.length() > 0)
            _newLogClass = s;
    }
    
    /** @since 0.8.1 */
    public void setNewloglevel(String s) {
        if (s != null)
            _newLogLevel = s;
    }
    
    /**
     * The user made changes to the config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        boolean shouldSave = false;
        LogManager mgr = _context.logManager();
        
        if ((_levels != null && _levels.length() > 0) || _newLogClass != null) {
            try {
                Properties props = new Properties();
                if (_levels != null)
                    props.load(new ByteArrayInputStream(DataHelper.getUTF8(_levels)));
                if (_newLogClass != null)
                    props.setProperty(_newLogClass, _newLogLevel);
                if (!props.equals(mgr.getLimits())) {
                    shouldSave = true;
                    mgr.setLimits(props);
                    addFormNotice(_t("Log overrides updated"));
                }
            } catch (IOException ioe) {
                // shouldn't ever happen (BAIS shouldnt cause an IOE)
                mgr.getLog(ConfigLoggingHandler.class).error("Error reading from the props?", ioe);
                addFormError("Error updating the log limits - levels not valid");
            }
        } else if (!mgr.getLimits().isEmpty()) {
            mgr.setLimits(null);
            shouldSave = true;
            addFormNotice("Log limits cleared");
        }
          
        if (_defaultLevel != null) {
            String oldDefault = mgr.getDefaultLimit();
            if (_defaultLevel.equals(oldDefault)) {
                // noop
            } else {
                shouldSave = true;
                mgr.setDefaultLimit(_defaultLevel);
                addFormNotice("Default log level updated from " + oldDefault + " to " + _defaultLevel);
            }
        }
        
        if (_dateFormat != null && !_dateFormat.equals(mgr.getDateFormatPattern())) {
            boolean valid = mgr.setDateFormat(_dateFormat);
            if (valid) {
                shouldSave = true;
                addFormNotice("Date format updated");
            } else {
                addFormError("Specified date format is not valid (" + _dateFormat + ") - not updated");
            }
        }
        
        if (_fileSize != null) {
            int newBytes = LogManager.getFileSize(_fileSize);
            int oldBytes = mgr.getFileSize();
            if (newBytes > 0) {
                if (oldBytes != newBytes) {
                    mgr.setFileSize(newBytes);
                    shouldSave = true;
                    addFormNotice("File size updated");
                } 
            } else {
                addFormError("Specified file size limit is not valid (" + _fileSize + ") - not updated");
            }
        }
        
        if (_logCompress != mgr.shouldGzip()) {
            mgr.setGzip(_logCompress);
            addFormNotice("Compression setting updated");
            shouldSave = true;
        }


     /*** disable
        if ( (_filename != null) && (_filename.trim().length() > 0) ) {
            _filename = _filename.trim();
            String old = mgr.getBaseLogfilename();
            if ( (old != null) && (_filename.equals(old)) ) {
                // noop - don't update since its the same
            } else {
                shouldSave = true;
                mgr.setBaseLogfilename(_filename);
                addFormNotice("Log file name pattern updated to " + _filename 
                              + " (note: will not take effect until next rotation)");
            }
        }
      ***/
        
        if ( (_recordFormat != null) && (_recordFormat.trim().length() > 0) ) {
            _recordFormat = _recordFormat.trim();
            String old = new String(mgr.getFormat());
            if (_recordFormat.equalsIgnoreCase(old)) {
                // noop - no change
            } else {
                char fmt[] = new char[_recordFormat.length()];
                for (int i = 0; i < fmt.length; i++) 
                    fmt[i] = _recordFormat.charAt(i);
                mgr.setFormat(fmt);
                shouldSave = true;
                addFormNotice("Log record format updated");
            }
        }
        
        if (shouldSave) {
            boolean saved = mgr.saveConfig();

            if (saved) 
                addFormNotice(_t("Log configuration saved"));
            else
                addFormError("Error saving the configuration (applied but not saved) - please see the error logs");
        }
    }
}
