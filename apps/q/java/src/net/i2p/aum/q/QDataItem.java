/*
 * QSSK.java
 *
 * Created on April 4, 2005, 11:35 AM
 */

package net.i2p.aum.q;

import java.io.Serializable;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.TreeSet;

import net.i2p.aum.Mimetypes;
import net.i2p.crypto.DSAEngine;
import net.i2p.data.DataFormatException;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;


/**
 * Singleton class with various static methods
 * for processing and validating metadata
 */
public class QDataItem extends Hashtable implements Serializable {

    /** set to true to enable verbose QUtil.debug output to stdout */
    public static boolean _debug = false;
    //public static boolean _debug = true;

    public byte [] _data;

    public String _path = null;

    /** Number of chars to truncate the 'public SSK key' base64 string to.
     * This weakens cryptographic security of the SSK, but makes for
     * slightly less ugly URIs. Set to -1 to disable truncation (and thus
     * get the full sha256 security
     */
    private static final int _pubHashTruncation = -1;

    public QDataItem(Hashtable h, byte [] data) {
        super(h);
        _data = data;
    }

    public QDataItem(Hashtable h) {
        this(h, null);
    }

    public QDataItem(byte [] data) {
        super();
        _data = data;
    }

    /**
     * Monolithic main method - processes a block of metadata according to the
     * rules in the Q metadata specification
     * @param metadata a block of metadata, received via a getItem or
     * putItem request
     * @param data the raw binary data
     * @param isFromApp true if this metadata has been freshly passed in from
     * a Q application, false if it's in the Q works
     * @throws QException if the metadata is invalid
     */
    public void processAndValidate(boolean isClient) throws QException {

        String uri;
        String ext;

        SigningPrivateKey _privKey = null;
        SigningPublicKey _pubKey = null;

        // -----------------------------------------
        // barf if no data
        if (_data == null) {
            throw new QException("No data");
        }

        // -----------------------------------------
        // barf if privKey given and not from client
        if (containsKey("privateKey") && !isClient) {
            throw new QException("Only Q applications can pass in a privateKey");
        }

        // -----------------------------------------
        // generate dataHash (or verify existing)
        if (containsKey("dataHash")) {
            if (!get("dataHash").equals(QUtil.sha64(_data))) {
                throw new QException("Invalid dataHash");
            }
        } else {
            put("dataHash", QUtil.sha64(_data));
        }
        
        // -----------------------------------------
        // generate size, or verify existing
        if (containsKey("size")) {
            if (!get("size").toString().equals(String.valueOf(_data.length))) {
                throw new QException("Invalid size");
            }
        } else {
            put("size", new Integer(_data.length));
        }

        // -----------------------------------------
        // default 'title' to dataHash
        if (!containsKey("title")) {
            put("title", get("dataHash"));
        }

        // -----------------------------------------
        // default the type
        if (!containsKey("type")) {
            put("type", "other");
        }
        
        // ------------------------------------------------
        // default the path
        if (containsKey("path")) {
            _path = get("path").toString();
            if (_path.length() > 0) {
                if (!_path.startsWith("/")) {
                    _path = "/" + _path;
                    put("path", _path);
                }
            }

            // determine file extension
            String [] bits = _path.split("/");
            String name = bits[bits.length-1];
            bits = name.split("\\.", 2);
            ext = "." + bits[bits.length-1];
        }
        else {
            // path is empty - set to '/<dataHash>.ext' where 'ext' is the
            // file extension guessed from present mimetype value, and dataHash
            // is a shortened hash of the content
            String mime = (String)get("mimetype");
            if (mime == null) {
                mime = "application/octet-stream";
                put("mimetype", mime);
            }

            // determine file extension
            ext = Mimetypes.guessExtension(mime);

            // and determine final path
            _path = "/" + ((String)get("dataHash")).substring(0, 10) + ext;
            put("path", _path);
        }

        // -----------------------------------------
        // default the mimetype
        if (!containsKey("mimetype")) {
            String mimetype = Mimetypes.guessType(ext);
            put("mimetype", mimetype);
        }

        // ------------------------------------------
        // barf if contains mutually-exclusive signed space keys
        if (containsKey("privateKey") && (containsKey("publicKey") || containsKey("signature"))) {
            throw new QException("Metadata must NOT contain privateKey and one of publicKey or signature");
        }

        // ------------------------------------------
        // barf if exactly one of publicKey and signature are present
        if (containsKey("publicKey") ^ containsKey("signature")) {
            throw new QException("Either both or neither of 'publicKey' and 'signature' must be present");
        }

        // -----------------------------------------
        // now discern between plain hash items and 
        // signed space items
        if (containsKey("privateKey") || containsKey("publicKey")) {

            DSAEngine dsa = DSAEngine.getInstance();

            // process/validate remaining data in signed space context

            if (containsKey("privateKey")) {
                // only private key given - uplift, remove, replace with public key
                _privKey = new SigningPrivateKey();
                String priv64 = get("privateKey").toString();
                try {
                    _privKey.fromBase64(priv64);
                } catch (Exception e) {
                    throw new QException("Invalid privateKey", e);
                }

                // ok, got valid privateKey
                
                // expunge privKey from metadata, replace with publicKey
                this.remove("privateKey");
                _pubKey = _privKey.toPublic();
                put("publicKey", _pubKey.toBase64());

                // create and insert a signature
                QUtil.debug("before sig, asSortedString give:\n"+asSortedString());

                Signature sig = dsa.sign(asSortedString().getBytes(), _privKey);
                String sigBase64 = sig.toBase64();
                put("signature", sigBase64);
            }
            else {
                // barf if not both signature and pubkey present
                if (!(containsKey("publicKey") && containsKey("signature"))) {
                    throw new QException("need both publicKey and signature");
                }
                _pubKey = new SigningPublicKey();
                String pub64 = get("publicKey").toString();
                try {
                    _pubKey.fromBase64(pub64);
                } catch (Exception e) {
                    throw new QException("Invalid publicKey", e);
                }
            }

            // now, whether we just signed or not, validate the signature/pubkey
            byte [] thisAsBytes = asSortedString().getBytes();

            String sig64 = get("signature").toString();
            Signature sig1 = new Signature();
            try {
                sig1.fromBase64(sig64);
            } catch (DataFormatException e) {
                throw new QException("Invalid signature string", e);
            }

            if (!dsa.verifySignature(sig1, thisAsBytes, _pubKey)) {
                throw new QException("Invalid signature");
            }

            // last step - determine the correct URI
            String pubHash = QUtil.hashPubKey(_pubKey);
            uri = "Q:"+pubHash+_path;
            
        }       // end of 'signed space' mode processing
        else {
            // -----------------------------------------------------
            // process/validate remaining data in plain hash context
            String thisHashed = QUtil.sha64(asSortedString());
            uri = "Q:"+ thisHashed + ext;

        }       // end of plain hash mode processing

        
        // -----------------------------------------------------
        // final step - add or validate uri
        if (containsKey("uri")) {
            if (!get("uri").toString().equals(uri)) {
                throw new QException("Invalid URI");
            }
        } else {
            put("uri", uri);
        }
        
    }

    /**
     * returns a filename under which this item should be stored
     */
    public String getStoreFilename() throws QException {
        if (!containsKey("uri")) {
            throw new QException("Missing URI");
        }
        return QUtil.sha64((String)get("uri"));
    }

    /**
     * Hashes this set of metadata, excluding any 'signature' key
     * @return Base64 hash of metadata
     */
    public String hashThisAsBase64() {

        return QUtil.sha64(asSortedString());
    }

    public byte [] hashThis() {

        return QUtil.sha(asSortedString());
    }

    /**
     * alphabetise thie metadata to a single string, containing one
     * 'key=value' entry per line. Excludes keys 'uri' and 'signature'
     */
    public String asSortedString() {

        TreeSet t = new TreeSet(keySet());
        Iterator keys = t.iterator();
        int nkeys = t.size();
        int i;
        String metaStr = "";
        for (i = 0; i < nkeys; i++)
        {
            String metaKey = (String)keys.next();
            if (!(metaKey.equals("signature") || metaKey.equals("uri"))) {
                metaStr += metaKey + "=" + get(metaKey) + "\n";
            }
        }
        return metaStr;
    }

}

