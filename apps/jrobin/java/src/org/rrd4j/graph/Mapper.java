package org.rrd4j.graph;

class Mapper {
    private final RrdGraphDef gdef;
    private final ImageParameters im;
    private final double pixieX, pixieY;

    Mapper(RrdGraph rrdGraph) {
        this.gdef = rrdGraph.gdef;
        this.im = rrdGraph.im;
        pixieX = (double) im.xsize / (double) (im.end - im.start);
        if (!gdef.logarithmic) {
            pixieY = im.ysize / (im.maxval - im.minval);
        }
        else {
            pixieY = im.ysize / (ValueAxisLogarithmic.log10(im.maxval) - ValueAxisLogarithmic.log10(im.minval));
        }
    }

    Mapper(RrdGraphDef gdef, ImageParameters im) {
        this.gdef = gdef;
        this.im = im;
        pixieX = (double) im.xsize / (double) (im.end - im.start);
        if (!gdef.logarithmic) {
            pixieY = im.ysize / (im.maxval - im.minval);
        }
        else {
            pixieY = im.ysize / (Math.log10(im.maxval) - Math.log10(im.minval));
        }
    }

    int xtr(double mytime) {
        return (int) (im.xorigin + pixieX * (mytime - im.start));
    }

    int ytr(double value) {
        double yval;
        if (!gdef.logarithmic) {
            yval = im.yorigin - pixieY * (value - im.minval) + 0.5;
        }
        else {
            if (value < im.minval) {
                yval = im.yorigin;
            }
            else {
                yval = im.yorigin - pixieY * (ValueAxisLogarithmic.log10(value) - ValueAxisLogarithmic.log10(im.minval)) + 0.5;
            }
        }
        if (!gdef.rigid) {
            return (int) yval;
        }
        else if ((int) yval > im.yorigin) {
            return im.yorigin + 2;
        }
        else if ((int) yval < im.yorigin - im.ysize) {
            return im.yorigin - im.ysize - 2;
        }
        else {
            return (int) yval;
        }
    }

}
