package net.i2p.syndie.data;

/**
 *
 */
public class Entry {
    private String _text;
    
    public Entry(String raw) {
        _text = raw;
    }
    
    public String getText() { return _text; }
}
