package net.i2p.router.web.helpers;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.GenericServlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.router.web.NavHelper;
import net.i2p.util.FileUtil;
 

/**
 * Serve plugin icons, at /Plugins/pluginicon?plugin=foo
 *
 * @author cacapo
 * @since 0.9.25
 */
public class CodedIconRendererServlet extends HttpServlet {
 
    private static final long serialVersionUID = 16851750L;
    
    private static final String base = I2PAppContext.getGlobalContext().getBaseDir().getAbsolutePath();
    private static final String file = "docs" + File.separatorChar + "themes" + File.separatorChar + "console" +  File.separatorChar + "images" + File.separatorChar + "plugin.png";


     @Override
     protected void doGet(HttpServletRequest srq, HttpServletResponse srs) throws ServletException, IOException {
         byte[] data;
         String name = srq.getParameter("plugin");
         data  = NavHelper.getBinary(name);
         
         //set as many headers as are common to any outcome
         
         srs.setContentType("image/png");
         srs.setHeader("X-Content-Type-Options", "nosniff");
         srs.setHeader("Accept-Ranges", "none");
         srs.setDateHeader("Expires", I2PAppContext.getGlobalContext().clock().now() + 86400000l);
         srs.setHeader("Cache-Control", "public, max-age=86400");
         OutputStream os = srs.getOutputStream();
         
         //Binary data is present
         if(data != null){
             srs.setHeader("Content-Length", Integer.toString(data.length));
             int content = Arrays.hashCode(data);
             int chksum = srq.getIntHeader("If-None-Match");//returns -1 if no such header
             //Don't render if icon already present
             if(content != chksum){
                 srs.setIntHeader("ETag", content);
                 try{
                     os.write(data);
                     os.flush();
                     os.close();
                 }catch(IOException e){
                     I2PAppContext.getGlobalContext().logManager().getLog(getClass()).warn("Error writing binary image data for plugin", e);
                 }
             } else {
                 srs.setStatus(304);
                 srs.getOutputStream().close();
             }
         } else {
             //Binary data is not present but must be substituted by file on disk
             File pfile = new File(base, file);
             srs.setHeader("Content-Length", Long.toString(pfile.length()));
             try{
                 long lastmod = pfile.lastModified();
                 if(lastmod > 0){
                     long iflast = srq.getDateHeader("If-Modified-Since");
                     if(iflast >= ((lastmod/1000) * 1000)){
                         srs.sendError(304, "Not Modified");
                     } else {
                         srs.setDateHeader("Last-Modified", lastmod);
                         FileUtil.readFile(file, base, os); 
                     }
                     
                 }
             } catch(IOException e) {
                 if (!srs.isCommitted()) {
                     srs.sendError(403, e.toString());
                 } else {
                     I2PAppContext.getGlobalContext().logManager().getLog(getClass()).warn("Error serving plugin.png", e);
                     throw e;
                 }
             }
             
         }
     }
}
