// ========================================================================
// Copyright 2000-2005 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.klomp.snark.web;

import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;


/* ------------------------------------------------------------ */
/**
 * Based on MimeTypes from Jetty 6.1.26, heavily simplified
 * and modified to remove all dependencies on Jetty libs.
 *
 * Supports mime types only, not encodings.
 * Does not support a default "*" mapping.
 *
 *  This is only for local mappings.
 *  Caller should use getServletContext().getMimeType() if this returns null.
 *
 *
 * ------------------------------------------------------------ 
 *
 * @author Greg Wilkins
 *
 * @since Jetty 7
 */
class MimeTypes
{
    
    private final Map<String, String> _mimeMap;

    public MimeTypes() {
        _mimeMap = new ConcurrentHashMap<String, String>();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param resourcePath A Map of file extension to mime-type.
     */
    public void loadMimeMap(String resourcePath) {
        loadMimeMap(_mimeMap, resourcePath);
    }

    /**
     *  Tries both webapp and system class loader, since Jetty blocks
     *  its classes from the webapp class loader.
     */
    private static void loadMimeMap(Map<String, String> map, String resourcePath) {
        try
        {
            ResourceBundle mime;
            try {
                mime = ResourceBundle.getBundle(resourcePath);
            } catch(MissingResourceException e) {
                // Jetty 7 webapp classloader blocks jetty classes
                // http://wiki.eclipse.org/Jetty/Reference/Jetty_Classloading
                //System.out.println("No mime types loaded from " + resourcePath + ", trying system classloader");
                mime = ResourceBundle.getBundle(resourcePath, Locale.getDefault(), ClassLoader.getSystemClassLoader());
            }
            Enumeration<String> i = mime.getKeys();
            while(i.hasMoreElements())
            {
                String ext = i.nextElement();
                String m = mime.getString(ext);
                map.put(ext.toLowerCase(Locale.US), m);
            }
            //System.out.println("Loaded " + map.size() + " mime types from " + resourcePath);
        } catch(MissingResourceException e) {
            //System.out.println("No mime types loaded from " + resourcePath);
        }
    }

    /* ------------------------------------------------------------ */
    /** Get the MIME type by filename extension.
     *
     *  Returns ONLY local mappings.
     *  Caller should use getServletContext().getMimeType() if this returns null.
     *
     * @param filename A file name
     * @return MIME type matching the longest dot extension of the
     * file name.
     */
    public String getMimeByExtension(String filename)
    {
        String type=null;

        if (filename!=null)
        {
            int i=-1;
            while(type==null)
            {
                i=filename.indexOf('.',i+1);

                if (i<0 || i>=filename.length())
                    break;

                String ext=filename.substring(i+1).toLowerCase(Locale.US);
                type = _mimeMap.get(ext);
            }
        }
        return type;
    }

    /* ------------------------------------------------------------ */
    /** Set a mime mapping
     * @param extension
     * @param type
     */
    public void addMimeMapping(String extension, String type)
    {
        _mimeMap.put(extension.toLowerCase(Locale.US), type);
    }
}
