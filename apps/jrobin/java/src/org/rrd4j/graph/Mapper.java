package org.rrd4j.graph;

class Mapper {
    private final RrdGraphDef gdef;
    private final ImageParameters im;
    private final double pixieX, pixieY;

    Mapper(RrdGraphDef gdef, ImageParameters im) {
        this.gdef = gdef;
        this.im = im;
        pixieX = (double) im.xsize / (double) (im.end - im.start);
        if (!gdef.logarithmic) {
            pixieY = im.ysize / (im.maxval - im.minval);
        }
        else {
            pixieY = im.ysize / (im.log.applyAsDouble(im.maxval) - im.log.applyAsDouble(im.minval));
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
                yval = im.yorigin - pixieY * (im.log.applyAsDouble(value) - im.log.applyAsDouble(im.minval)) + 0.5;
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
