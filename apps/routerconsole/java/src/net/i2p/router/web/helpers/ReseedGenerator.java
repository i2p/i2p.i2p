package net.i2p.router.web.helpers;

import java.io.File;
import java.io.IOException;

import net.i2p.router.networkdb.reseed.ReseedBundler;
import net.i2p.router.web.HelperBase;

/**
 *  Handler to create a i2preseed.zip file
 *  @since 0.9.19
 */
public class ReseedGenerator extends HelperBase {

    public File createZip() throws IOException {
        ReseedBundler rb = new ReseedBundler(_context);
        return rb.createZip(100);
    }
}
