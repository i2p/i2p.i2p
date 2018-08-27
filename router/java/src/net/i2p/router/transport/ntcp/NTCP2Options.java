package net.i2p.router.transport.ntcp;

import net.i2p.data.DataHelper;

/**
 *
 *  NTCP2 Padding/Dummy/Delay configuration for data phase.
 *  Any other options TBD.
 *
 *  @since 0.9.36
 */
class NTCP2Options {

    private final float _sendMin, _sendMax, _recvMin, _recvMax;
    private final int _sendDummy, _recvDummy, _sendDelay, _recvDelay;

    public NTCP2Options(float sendMin, float sendMax, float recvMin, float recvMax,
                        int sendDummy, int recvDummy, int sendDelay, int recvDelay) {
        _sendMin = sendMin;
        _sendMax = sendMax;
        _recvMin = recvMin;
        _recvMax = recvMax;
        _sendDummy = sendDummy;
        _recvDummy = recvDummy;
        _sendDelay = sendDelay;
        _recvDelay = recvDelay;
    }

    public float getSendMin() { return _sendMin; }
    public float getSendMax() { return _sendMax; }
    public float getRecvMin() { return _recvMin; }
    public float getRecvMax() { return _recvMax; }
    public int getSendDummy() { return _sendDummy; }
    public int getRecvDummy() { return _recvDummy; }
    public int getSendDelay() { return _sendDelay; }
    public int getRecvDelay() { return _recvDelay; }

    /**
     *  Get a combined config for this connection.
     *  If incompatible, prefer ours.
     *
     *  @param his far end options (send is his send, recv is his recv)
     *  @return merged options from our perspective (send is our send, recv is our recv)
     */
    public NTCP2Options merge(NTCP2Options his) {
        float xsMin = Math.max(_sendMin, his.getRecvMin());
        float xsMax = Math.min(_sendMax, his.getRecvMax());
        if (xsMin > xsMax)
            xsMin = xsMax;

        float xrMin = Math.max(_recvMin, his.getSendMin());
        float xrMax = Math.min(_recvMax, his.getSendMax());
        if (xrMin > xrMax)
            xrMin = xrMax;

        int xsDummy = Math.min(_sendDummy, his.getRecvDummy());
        int xrDummy = Math.min(_recvDummy, his.getSendDummy());
        int xsDelay = Math.min(_sendDelay, his.getRecvDelay());
        int xrDelay = Math.min(_recvDelay, his.getSendDelay());

        return new NTCP2Options(xsMin, xsMax, xrMin, xrMax,
                                xsDummy, xrDummy, xsDelay, xrDelay);
    }

    /**
     *  @since 0.9.37 consolidated from two places
     *  @return null on error
     */
    public static NTCP2Options fromByteArray(byte[] options) {
        if (options.length < 12)
            return null;
        float tmin = (options[0] & 0xff) / 16.0f;
        float tmax = (options[1] & 0xff) / 16.0f;
        float rmin = (options[2] & 0xff) / 16.0f;
        float rmax = (options[3] & 0xff) / 16.0f;
        int tdummy = (int) DataHelper.fromLong(options, 4, 2);
        int rdummy = (int) DataHelper.fromLong(options, 6, 2);
        int tdelay = (int) DataHelper.fromLong(options, 8, 2);
        int rdelay = (int) DataHelper.fromLong(options, 10, 2);
        return new NTCP2Options(tmin, tmax, rmin, rmax,
                                tdummy, rdummy, tdelay, rdelay);
    }

    @Override
    public String toString() {
        return "Padding options: send min/max %: (" + (_sendMin * 100) + ", " + (_sendMax * 100) +
               ") recv min/max %: (" + (_recvMin * 100) + ", " + (_recvMax * 100) +
               ") dummy send/recv B/s: (" + _sendDummy + ", " + _recvDummy +
               ") delay send/recv ms: (" + _sendDelay + ", " + _recvDelay + ')';
    }
}
