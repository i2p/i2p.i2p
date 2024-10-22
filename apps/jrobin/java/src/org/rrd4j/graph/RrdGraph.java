package org.rrd4j.graph;

import java.awt.Graphics;
import java.io.IOException;
import java.util.function.Supplier;

import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.swing.ImageIcon;

import org.rrd4j.data.DataProcessor;

/**
 * Class which actually creates Rrd4j graphs (does the hard work).
 */
public class RrdGraph implements RrdGraphConstants {

    final RrdGraphDef gdef;
    final ImageParameters im;
    private final RrdGraphInfo info;

    /**
     * Creates graph from the corresponding {@link org.rrd4j.graph.RrdGraphDef} object.
     *
     * @param gdef Graph definition
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public RrdGraph(RrdGraphDef gdef) throws IOException {
        this(gdef, () -> RrdGraph.generateImageWorker(gdef));
    }

    /**
     * Create graph from a custom image worker
     * @param gdef
     * @param worker
     * @throws IOException
     */
    public RrdGraph(RrdGraphDef gdef, ImageWorker worker) throws IOException {
        this(gdef, () -> worker);
    }

    /**
     * <p>Creates graph from the corresponding {@link org.rrd4j.graph.RrdGraphDef} object.</p>
     * <p>The graph will be created using customs {@link javax.imageio.ImageWriter} and {@link javax.imageio.ImageWriteParam} given.</p>
     * <p>The ImageWriter type and ImageWriteParam settings have priority other the RrdGraphDef settings.

     * @param gdef Graph definition
     * @param writer
     * @param param
     * @throws IOException Thrown in case of I/O error
     * @since 3.5
     */
    public RrdGraph(RrdGraphDef gdef, ImageWriter writer, ImageWriteParam param) throws IOException {
        this(gdef, () -> RrdGraph.generateImageWorker(gdef, writer, param));
    }

    private RrdGraph(RrdGraphDef gdef, Supplier<ImageWorker> worker) throws IOException {
        this.gdef = gdef;
        RrdGraphGenerator generator = new RrdGraphGenerator(gdef, worker.get(), new DataProcessor(gdef.startTime, gdef.endTime));
        try {
            generator.createGraph();
        }
        finally {
            generator.worker.dispose();
        }
        info = generator.info;
        im = generator.im;
    }

    private static ImageWorker generateImageWorker(RrdGraphDef gdef, ImageWriter writer, ImageWriteParam param) {
        return BufferedImageWorker.getBuilder().setGdef(gdef).setWriter(writer).setImageWriteParam(param).build();
    }

    private static ImageWorker generateImageWorker(RrdGraphDef gdef) {
        return BufferedImageWorker.getBuilder().setGdef(gdef).build();
    }

    /**
     * Returns complete graph information in a single object.
     *
     * @return Graph information (width, height, filename, image bytes, etc...)
     */
    public RrdGraphInfo getRrdGraphInfo() {
        return info;
    }

    /**
     * Renders this graph onto graphing device
     *
     * @param g Graphics handle
     */
    public void render(Graphics g) {
        byte[] imageData = getRrdGraphInfo().getBytes();
        ImageIcon image = new ImageIcon(imageData);
        image.paintIcon(null, g, 0, 0);
    }

}
