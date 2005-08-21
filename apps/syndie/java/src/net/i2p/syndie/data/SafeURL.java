package net.i2p.syndie.data;

/**
 *
 */
public class SafeURL {
    private String _schema;
    private String _location;
    private String _name;
    private String _description;
    
    public SafeURL(String raw) {
        parse(raw);
    }
    
    private void parse(String raw) {
        if (raw != null) {
            int index = raw.indexOf("://");
            if ( (index <= 0) || (index + 1 >= raw.length()) )
                return;
            _schema = raw.substring(0, index);
            _location = raw.substring(index+3);
            _location.replace('>', '_');
            _location.replace('<', '^');
        }
    }
    
    public String getSchema() { return _schema; }
    public String getLocation() { return _location; }
    
    public String toString() { return _schema + "://" + _location; }
}
