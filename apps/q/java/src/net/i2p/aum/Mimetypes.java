package net.i2p.aum;

/** 
 * creates a convenient map of file extensions <-> mimetypes
 */

public class Mimetypes
{
    public static String [][] _map = {

        { ".bz2", "application/x-bzip2" },
        { ".csm", "application/cu-seeme" },
        { ".cu", "application/cu-seeme" },
        { ".tsp", "application/dsptype" },
        { ".xls", "application/excel" },
        { ".spl", "application/futuresplash" },
        { ".hqx", "application/mac-binhex40" },
        { ".doc", "application/msword" },
        { ".dot", "application/msword" },
        { ".bin", "application/octet-stream" },
        { ".oda", "application/oda" },
        { ".pdf", "application/pdf" },
        { ".asc", "application/pgp-keys" },
        { ".pgp", "application/pgp-signature" },
        { ".ps", "application/postscript" },
        { ".ai", "application/postscript" },
        { ".eps", "application/postscript" },
        { ".ppt", "application/powerpoint" },
        { ".rtf", "application/rtf" },
        { ".wp5", "application/wordperfect5.1" },
        { ".zip", "application/zip" },
        { ".wk", "application/x-123" },
        { ".bcpio", "application/x-bcpio" },
        { ".pgn", "application/x-chess-pgn" },
        { ".cpio", "application/x-cpio" },
        { ".deb", "application/x-debian-package" },
        { ".dcr", "application/x-director" },
        { ".dir", "application/x-director" },
        { ".dxr", "application/x-director" },
        { ".dvi", "application/x-dvi" },
        { ".pfa", "application/x-font" },
        { ".pfb", "application/x-font" },
        { ".gsf", "application/x-font" },
        { ".pcf", "application/x-font" },
        { ".pcf.Z", "application/x-font" },
        { ".gtar", "application/x-gtar" },
        { ".tgz", "application/x-gtar" },
        { ".hdf", "application/x-hdf" },
        { ".phtml", "application/x-httpd-php" },
        { ".pht", "application/x-httpd-php" },
        { ".php", "application/x-httpd-php" },
        { ".php3", "application/x-httpd-php3" },
        { ".phps", "application/x-httpd-php3-source" },
        { ".php3p", "application/x-httpd-php3-preprocessed" },
        { ".class", "application/x-java" },
        { ".latex", "application/x-latex" },
        { ".frm", "application/x-maker" },
        { ".maker", "application/x-maker" },
        { ".frame", "application/x-maker" },
        { ".fm", "application/x-maker" },
        { ".fb", "application/x-maker" },
        { ".book", "application/x-maker" },
        { ".fbdoc", "application/x-maker" },
        { ".mif", "application/x-mif" },
        { ".nc", "application/x-netcdf" },
        { ".cdf", "application/x-netcdf" },
        { ".pac", "application/x-ns-proxy-autoconfig" },
        { ".o", "application/x-object" },
        { ".pl", "application/x-perl" },
        { ".pm", "application/x-perl" },
        { ".shar", "application/x-shar" },
        { ".swf", "application/x-shockwave-flash" },
        { ".swfl", "application/x-shockwave-flash" },
        { ".sit", "application/x-stuffit" },
        { ".sv4cpio", "application/x-sv4cpio" },
        { ".sv4crc", "application/x-sv4crc" },
        { ".tar", "application/x-tar" },
        { ".gf", "application/x-tex-gf" },
        { ".pk", "application/x-tex-pk" },
        { ".PK", "application/x-tex-pk" },
        { ".texinfo", "application/x-texinfo" },
        { ".texi", "application/x-texinfo" },
        { ".~", "application/x-trash" },
        { ".%", "application/x-trash" },
        { ".bak", "application/x-trash" },
        { ".old", "application/x-trash" },
        { ".sik", "application/x-trash" },
        { ".t", "application/x-troff" },
        { ".tr", "application/x-troff" },
        { ".roff", "application/x-troff" },
        { ".man", "application/x-troff-man" },
        { ".me", "application/x-troff-me" },
        { ".ms", "application/x-troff-ms" },
        { ".ustar", "application/x-ustar" },
        { ".src", "application/x-wais-source" },
        { ".wz", "application/x-wingz" },
        { ".au", "audio/basic" },
        { ".snd", "audio/basic" },
        { ".mid", "audio/midi" },
        { ".midi", "audio/midi" },
        { ".mpga", "audio/mpeg" },
        { ".mpega", "audio/mpeg" },
        { ".mp2", "audio/mpeg" },
        { ".mp3", "audio/mpeg" },
        { ".m3u", "audio/mpegurl" },
        { ".aif", "audio/x-aiff" },
        { ".aiff", "audio/x-aiff" },
        { ".aifc", "audio/x-aiff" },
        { ".gsm", "audio/x-gsm" },
        { ".ra", "audio/x-pn-realaudio" },
        { ".rm", "audio/x-pn-realaudio" },
        { ".ram", "audio/x-pn-realaudio" },
        { ".rpm", "audio/x-pn-realaudio-plugin" },
        { ".wav", "audio/x-wav" },
        { ".gif", "image/gif" },
        { ".ief", "image/ief" },
        { ".jpeg", "image/jpeg" },
        { ".jpg", "image/jpeg" },
        { ".jpe", "image/jpeg" },
        { ".png", "image/png" },
        { ".tiff", "image/tiff" },
        { ".tif", "image/tiff" },
        { ".ras", "image/x-cmu-raster" },
        { ".bmp", "image/x-ms-bmp" },
        { ".pnm", "image/x-portable-anymap" },
        { ".pbm", "image/x-portable-bitmap" },
        { ".pgm", "image/x-portable-graymap" },
        { ".ppm", "image/x-portable-pixmap" },
        { ".rgb", "image/x-rgb" },
        { ".xbm", "image/x-xbitmap" },
        { ".xpm", "image/x-xpixmap" },
        { ".xwd", "image/x-xwindowdump" },
        { ".csv", "text/comma-separated-values" },
        { ".html", "text/html" },
        { ".htm", "text/html" },
        { ".mml", "text/mathml" },
        { ".txt", "text/plain" },
        { ".rtx", "text/richtext" },
        { ".tsv", "text/tab-separated-values" },
        { ".h++", "text/x-c++hdr" },
        { ".hpp", "text/x-c++hdr" },
        { ".hxx", "text/x-c++hdr" },
        { ".hh", "text/x-c++hdr" },
        { ".c++", "text/x-c++src" },
        { ".cpp", "text/x-c++src" },
        { ".cxx", "text/x-c++src" },
        { ".cc", "text/x-c++src" },
        { ".h", "text/x-chdr" },
        { ".csh", "text/x-csh" },
        { ".c", "text/x-csrc" },
        { ".java", "text/x-java" },
        { ".moc", "text/x-moc" },
        { ".p", "text/x-pascal" },
        { ".pas", "text/x-pascal" },
        { ".etx", "text/x-setext" },
        { ".sh", "text/x-sh" },
        { ".tcl", "text/x-tcl" },
        { ".tk", "text/x-tcl" },
        { ".tex", "text/x-tex" },
        { ".ltx", "text/x-tex" },
        { ".sty", "text/x-tex" },
        { ".cls", "text/x-tex" },
        { ".vcs", "text/x-vCalendar" },
        { ".vcf", "text/x-vCard" },
        { ".dl", "video/dl" },
        { ".fli", "video/fli" },
        { ".gl", "video/gl" },
        { ".mpeg", "video/mpeg" },
        { ".mpg", "video/mpeg" },
        { ".mpe", "video/mpeg" },
        { ".qt", "video/quicktime" },
        { ".mov", "video/quicktime" },
        { ".asf", "video/x-ms-asf" },
        { ".asx", "video/x-ms-asf" },
        { ".avi", "video/x-msvideo" },
        { ".movie", "video/x-sgi-movie" },
        { ".vrm", "x-world/x-vrml" },
        { ".vrml", "x-world/x-vrml" },
        { ".wrl", "x-world/x-vrml" },

    };

    /**
     * Attempts to determine a mimetype
     * @param path - either a file extension string (containing the
     * leading '.') or a full file pathname (in which case, the extension
     * will be extracted).
     * @return the mimetype that corresponds to the file extension, if the
     * file extension is known, or "application/octet-stream" if the
     * file extension is not known.
     */
    public static String guessType(String path) {
        // rip the file extension from the path
        // first - split 'directories', and get last part
        String [] dirs = path.split("/");
        String filename = dirs[dirs.length-1];
        String [] bits = filename.split("\\.");
        String extension = "." + bits[bits.length-1];
        
        // default mimetype applied to unknown file extensions
        String type = "application/octet-stream";

        for (int i=0; i<_map.length; i++) {
            String [] rec = _map[i];
            if (rec[0].equals(extension)) {
                type = rec[1];
                break;
            }
        }
        return type;
    }

    /**
     * Attempts to guess the file extension corresponding to a given
     * mimetype.
     * @param type a mimetype string
     * @return a file extension commonly used for storing files of this type,
     * or defaults to ".bin" if mimetype not known
     */
    public static String guessExtension(String type) {
        // default extension applied to unknown mimetype
        String extension = ".bin";
        for (int i=0; i<_map.length; i++) {
            String [] rec = _map[i];
            if (rec[1].equals(type)) {
                extension = rec[0];
                break;
            }
        }
        return extension;
    }

}

/**

suffix_map = {
    '.tgz': '.tar.gz',
    '.taz': '.tar.gz',
    '.tz': '.tar.gz',
    }

encodings_map = {
    '.gz': 'gzip',
    '.Z': 'compress',
    }

# Before adding new types, make sure they are either registered with IANA, at
# http://www.isi.edu/in-notes/iana/assignments/media-types
# or extensions, i.e. using the x- prefix

# If you add to these, please keep them sorted!
types_map = {
    '.a'      : 'application/octet-stream',
    '.ai'     : 'application/postscript',
    '.aif'    : 'audio/x-aiff',
    '.aifc'   : 'audio/x-aiff',
    '.aiff'   : 'audio/x-aiff',
    '.au'     : 'audio/basic',
    '.avi'    : 'video/x-msvideo',
    '.bat'    : 'text/plain',
    '.bcpio'  : 'application/x-bcpio',
    '.bin'    : 'application/octet-stream',
    '.bmp'    : 'image/x-ms-bmp',
    '.c'      : 'text/plain',
    # Duplicates :(
    '.cdf'    : 'application/x-cdf',
    '.cdf'    : 'application/x-netcdf',
    '.cpio'   : 'application/x-cpio',
    '.csh'    : 'application/x-csh',
    '.css'    : 'text/css',
    '.dll'    : 'application/octet-stream',
    '.doc'    : 'application/msword',
    '.dot'    : 'application/msword',
    '.dvi'    : 'application/x-dvi',
    '.eml'    : 'message/rfc822',
    '.eps'    : 'application/postscript',
    '.etx'    : 'text/x-setext',
    '.exe'    : 'application/octet-stream',
    '.gif'    : 'image/gif',
    '.gtar'   : 'application/x-gtar',
    '.h'      : 'text/plain',
    '.hdf'    : 'application/x-hdf',
    '.htm'    : 'text/html',
    '.html'   : 'text/html',
    '.ief'    : 'image/ief',
    '.jpe'    : 'image/jpeg',
    '.jpeg'   : 'image/jpeg',
    '.jpg'    : 'image/jpeg',
    '.js'     : 'application/x-javascript',
    '.ksh'    : 'text/plain',
    '.latex'  : 'application/x-latex',
    '.m1v'    : 'video/mpeg',
    '.man'    : 'application/x-troff-man',
    '.me'     : 'application/x-troff-me',
    '.mht'    : 'message/rfc822',
    '.mhtml'  : 'message/rfc822',
    '.mif'    : 'application/x-mif',
    '.mov'    : 'video/quicktime',
    '.movie'  : 'video/x-sgi-movie',
    '.mp2'    : 'audio/mpeg',
    '.mp3'    : 'audio/mpeg',
    '.mpa'    : 'video/mpeg',
    '.mpe'    : 'video/mpeg',
    '.mpeg'   : 'video/mpeg',
    '.mpg'    : 'video/mpeg',
    '.ms'     : 'application/x-troff-ms',
    '.nc'     : 'application/x-netcdf',
    '.nws'    : 'message/rfc822',
    '.o'      : 'application/octet-stream',
    '.obj'    : 'application/octet-stream',
    '.oda'    : 'application/oda',
    '.p12'    : 'application/x-pkcs12',
    '.p7c'    : 'application/pkcs7-mime',
    '.pbm'    : 'image/x-portable-bitmap',
    '.pdf'    : 'application/pdf',
    '.pfx'    : 'application/x-pkcs12',
    '.pgm'    : 'image/x-portable-graymap',
    '.pl'     : 'text/plain',
    '.png'    : 'image/png',
    '.pnm'    : 'image/x-portable-anymap',
    '.pot'    : 'application/vnd.ms-powerpoint',
    '.ppa'    : 'application/vnd.ms-powerpoint',
    '.ppm'    : 'image/x-portable-pixmap',
    '.pps'    : 'application/vnd.ms-powerpoint',
    '.ppt'    : 'application/vnd.ms-powerpoint',
    '.ps'     : 'application/postscript',
    '.pwz'    : 'application/vnd.ms-powerpoint',
    '.py'     : 'text/x-python',
    '.pyc'    : 'application/x-python-code',
    '.pyo'    : 'application/x-python-code',
    '.qt'     : 'video/quicktime',
    '.ra'     : 'audio/x-pn-realaudio',
    '.ram'    : 'application/x-pn-realaudio',
    '.ras'    : 'image/x-cmu-raster',
    '.rdf'    : 'application/xml',
    '.rgb'    : 'image/x-rgb',
    '.roff'   : 'application/x-troff',
    '.rtx'    : 'text/richtext',
    '.sgm'    : 'text/x-sgml',
    '.sgml'   : 'text/x-sgml',
    '.sh'     : 'application/x-sh',
    '.shar'   : 'application/x-shar',
    '.snd'    : 'audio/basic',
    '.so'     : 'application/octet-stream',
    '.src'    : 'application/x-wais-source',
    '.sv4cpio': 'application/x-sv4cpio',
    '.sv4crc' : 'application/x-sv4crc',
    '.swf'    : 'application/x-shockwave-flash',
    '.t'      : 'application/x-troff',
    '.tar'    : 'application/x-tar',
    '.tcl'    : 'application/x-tcl',
    '.tex'    : 'application/x-tex',
    '.texi'   : 'application/x-texinfo',
    '.texinfo': 'application/x-texinfo',
    '.tif'    : 'image/tiff',
    '.tiff'   : 'image/tiff',
    '.tr'     : 'application/x-troff',
    '.tsv'    : 'text/tab-separated-values',
    '.txt'    : 'text/plain',
    '.ustar'  : 'application/x-ustar',
    '.vcf'    : 'text/x-vcard',
    '.wav'    : 'audio/x-wav',
    '.wiz'    : 'application/msword',
    '.xbm'    : 'image/x-xbitmap',
    '.xlb'    : 'application/vnd.ms-excel',
    # Duplicates :(
    '.xls'    : 'application/excel',
    '.xls'    : 'application/vnd.ms-excel',
    '.xml'    : 'text/xml',
    '.xpm'    : 'image/x-xpixmap',
    '.xsl'    : 'application/xml',
    '.xwd'    : 'image/x-xwindowdump',
    '.zip'    : 'application/zip',
    }

# These are non-standard types, commonly found in the wild.  They will only
# match if strict=0 flag is given to the API methods.

# Please sort these too
common_types = {
    '.jpg' : 'image/jpg',
    '.mid' : 'audio/midi',
    '.midi': 'audio/midi',
    '.pct' : 'image/pict',
    '.pic' : 'image/pict',
    '.pict': 'image/pict',
    '.rtf' : 'application/rtf',
    '.xul' : 'text/xul'
    }
**/

