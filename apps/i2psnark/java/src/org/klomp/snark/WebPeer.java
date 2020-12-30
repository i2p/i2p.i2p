package org.klomp.snark;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketEepGet;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.EepGet;
import net.i2p.util.Log;

/**
 *  BEP 19.
 *  Does not have an associated PeerState.
 *  All request tracking is done here.
 *  @since 0.9.49
 */
class WebPeer extends Peer implements EepGet.StatusListener {

  private final PeerCoordinator _coordinator;
  private final URI _uri;
  // as received from coordinator
  private final List<Request> outstandingRequests = new ArrayList<Request>();
  private final boolean isMultiFile;
  // needed?
  private Request lastRequest;
  private PeerListener listener;
  private BitField bitfield;
  private Thread thread;
  private boolean connected;
  private long lastRcvd;
  private int maxRequests;

  // to be recognized by the UI
  public static final byte[] IDBytes = DataHelper.getASCII("WebSeedBEP19");
  private static final long HEADER_TIMEOUT = 60*1000;
  private static final long TOTAL_TIMEOUT = 10*60*1000;
  private static final long INACTIVITY_TIMEOUT = 2*60*1000;
  private static final long TARGET_FETCH_TIME = 2*60*1000;
  // 128 KB
  private static final int ABSOLUTE_MIN_REQUESTS = 8;
  // 2 MB
  private static final int ABSOLUTE_MAX_REQUESTS = 128;
  private final int MIN_REQUESTS;
  private final int MAX_REQUESTS;

  /**
   * Outgoing connection.
   * Creates a disconnected peer given a PeerID, your own id and the
   * relevant MetaInfo.
   * @param uri must be http with .i2p host
   * @param metainfo non-null
   */
  public WebPeer(PeerCoordinator coord, URI uri, PeerID peerID, MetaInfo metainfo) {
      super(peerID, null, null, metainfo);
      // no use asking for more than the number of chunks in a piece
      MAX_REQUESTS = Math.max(1, Math.min(ABSOLUTE_MAX_REQUESTS, metainfo.getPieceLength(0) / PeerState.PARTSIZE));
      MIN_REQUESTS = Math.min(ABSOLUTE_MIN_REQUESTS, MAX_REQUESTS);
      maxRequests = MIN_REQUESTS;
      isMultiFile = metainfo.getLengths() != null;
      _coordinator = coord;
      // We'll assume the base path is already encoded, because
      // it would have failed the checks in TrackerClient.getHostHash()
      _uri = uri;
  }

  @Override
  public String toString() {
      return "WebSeed " + _uri;
  }

  /**
   * @return socket debug string (for debug printing)
   */
  @Override
  public synchronized String getSocket() {
      return toString() + ' ' + outstandingRequests.toString();
  }

  /**
   * The hash code of a Peer is the hash code of the peerID.
   */
  @Override
  public int hashCode() {
      return super.hashCode();
  }

  /**
   * Two Peers are equal when they have the same PeerID.
   * All other properties are ignored.
   */
  @Override
  public boolean equals(Object o) {
      if (o instanceof WebPeer) {
          WebPeer p = (WebPeer)o;
          // TODO
          return getPeerID().equals(p.getPeerID());
      }
      return false;
  }

  /**
   * Runs the connection to the other peer. This method does not
   * return until the connection is terminated.
   *
   * @param ignore our bitfield, ignore
   * @param uploadOnly if we are complete with skipped files, i.e. a partial seed
   */
  @Override
  public void runConnection(I2PSnarkUtil util, PeerListener listener, BitField ignore,
                            MagnetState mState, boolean uploadOnly) {
      if (uploadOnly)
          return;
      int fails = 0;
      int successes = 0;
      long dl = 0;
      boolean notify = true;
      ByteArrayOutputStream out = null;
      // current requests per-loop
      List<Request> requests = new ArrayList<Request>(8);
      try {
          if (!util.connected()) {
              boolean ok = util.connect();
              if (!ok)
                  return;
          }

          // This breaks out of the loop after any failure. TrackerClient will requeue eventually.
          loop:
          while (true) {
              I2PSocketManager mgr = util.getSocketManager();
              if (mgr == null)
                  return;
              if (notify) {
                  synchronized(this) {
                      this.listener = listener;
                      bitfield = new BitField(metainfo.getPieces());
                      bitfield.setAll();
                      thread = Thread.currentThread();
                      connected = true;
                  }
                  listener.connected(this);
                  boolean want = listener.gotBitField(this, bitfield);
                  if (!want)
                      return;
                  listener.gotChoke(this, false);
                  notify = false;
              }

              synchronized(this) {
                  // clear out previous requests
                  if (!requests.isEmpty()) {
                      outstandingRequests.removeAll(requests);
                      requests.clear();
                  }
                  addRequest();
                  if (_log.shouldDebug())
                      _log.debug("Requests: " + outstandingRequests);
                  while (outstandingRequests.isEmpty()) {
                      if (_coordinator.getNeededLength() <= 0) {
                          if (_log.shouldDebug())
                              _log.debug("Complete: " + this);
                          break loop;
                      }
                      if (_log.shouldDebug())
                          _log.debug("No requests, sleeping: " + this);
                      connected = false;
                      out = null;
                      try {
                          this.wait();
                      } catch (InterruptedException ie) {
                          if (_log.shouldWarn())
                              _log.warn("Interrupted: " + this, ie);
                          break loop;
                      }
                  }
                  connected = true;
                  // Add current requests from outstandingRequests list and add to requests list.
                  // Do not remove from outstandingRequests until success.
                  lastRequest = outstandingRequests.get(0);
                  requests.add(lastRequest);
                  int piece = lastRequest.getPiece();

                  // Glue together additional requests if consecutive for a single piece.
                  // This will never glue together requests from different pieces,
                  // and the coordinator generally won't give us consecutive pieces anyway.
                  // Servers generally won't support multiple byte ranges anymore.
                  for (int i = 1; i < outstandingRequests.size(); i++) {
                      if (i >= maxRequests)
                          break;
                      Request r = outstandingRequests.get(i);
                      if (r.getPiece() == piece &&
                          lastRequest.off + lastRequest.len == r.off) {
                          requests.add(r);
                          lastRequest = r;
                      } else {
                          // all requests for a piece should be together, but not in practice
                          // as orphaned requests can get in-between
                          //break;
                      }
                  }
              }

              // total values
              Request first = requests.get(0);
              Request last = requests.get(requests.size() - 1);
              int piece = first.getPiece();
              int off = first.off;
              long toff = (((long) piece) * metainfo.getPieceLength(0)) + off;
              int tlen = (last.off - first.off) + last.len;
              long start = System.currentTimeMillis();
              ///// TODO direct to file, not in-memory
              if (out == null)
                  out = new ByteArrayOutputStream(tlen);
              else
                  out.reset();
              int filenum = -1;

              // Loop for each file if multifile and crosses file boundaries.
              // Once only for single file.
              while (out.size() < tlen) {

                  // need these three things:
                  // url to fetch
                  String url;
                  // offset in fetched file
                  long foff;
                  // length to fetch, will be adjusted if crossing a file boundary
                  int flen = tlen - out.size();

                  if (isMultiFile) {
                      // multifile
                      List<Long> lengths = metainfo.getLengths();
                      long limit = 0;
                      if (filenum < 0) {
                          // find the first file number and limit
                          // inclusive
                          long fstart = 0;
                          // exclusive
                          long fend = 0;
                          foff = 0; // keep compiler happy, will always be re-set
                          for (int f = 0; f < lengths.size(); f++) {
                              long filelen = lengths.get(f).longValue();
                              fend = fstart + filelen;
                              if (toff < fend) {
                                  filenum = f;
                                  foff = toff - fstart;
                                  limit = fend - toff;
                                  break;
                              }
                              fstart += filelen;
                          }
                          if (filenum < 0)
                              throw new IllegalStateException(lastRequest.toString());
                      } else {
                          // next file
                          filenum++;
                          foff = 0;
                          limit = lengths.get(filenum).longValue();
                      }

                      if (limit > 0 && flen > limit)
                          flen = (int) limit;

                      if (metainfo.isPaddingFile(filenum)) {
                          for (int i = 0; i < flen; i++) {
                              out.write((byte) 0);
                          }
                          if (_log.shouldDebug())
                              _log.debug("Skipped padding file " + filenum);
                          continue;
                      }

                      // build url
                      String uri = _uri.toString();
                      StringBuilder buf = new StringBuilder(uri.length() + 128);
                      buf.append(uri);
                      if (!uri.endsWith("/"))
                          buf.append('/');
                      // See BEP 19 rules
                      URIUtil.encodePath(buf, metainfo.getName());
                      List<String> path = metainfo.getFiles().get(filenum);
                      for (int i = 0; i < path.size(); i++) {
                           buf.append('/');
                           URIUtil.encodePath(buf, path.get(i));
                      }
                      url = buf.toString();
                  } else {
                      // single file
                      // See BEP 19 rules
                      String uri = _uri.toString();
                      if (uri.endsWith("/"))
                          url = uri + URIUtil.encodePath(metainfo.getName());
                      else
                          url = uri;
                      foff = toff;
                      flen = tlen;
                  }

                  // do the fetch
                  EepGet get = new I2PSocketEepGet(util.getContext(), mgr, 0, flen, flen, null, out, url);
                  get.addHeader("User-Agent", I2PSnarkUtil.EEPGET_USER_AGENT);
                  get.addHeader("Range", "bytes=" + foff + '-' + (foff + flen - 1));
                  get.addStatusListener(this);
                  int osz = out.size();
                  if (_log.shouldDebug())
                      _log.debug("Fetching piece: " + piece + " offset: " + off + " file offset: " + foff + " len: " + flen + " from " + url);
                  if (get.fetch(HEADER_TIMEOUT, TOTAL_TIMEOUT, INACTIVITY_TIMEOUT)) {
                      int resp = get.getStatusCode();
                      if (resp != 200 && resp != 206) {
                          fail(url, resp);
                          return;
                      }
                      int sz = out.size() - osz;
                      if (sz != flen) {
                          if (_log.shouldWarn())
                              _log.warn("Fetch of " + url + " received: " + sz + " expected: " + flen);
                          return;
                      }
                  } else {
                      if (out.size() > 0) {
                          // save any complete chunks received
                          DataInputStream dis = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
                          for (Iterator<Request> iter = requests.iterator(); iter.hasNext(); ) {
                              Request req = iter.next();
                              if (dis.available() < req.len)
                                  break;
                              req.read(dis);
                              iter.remove();
                              if (_log.shouldWarn())
                                  _log.warn("Saved chunk " + req + " recvd before failure");
                          }
                      }
                      int resp = get.getStatusCode();
                      fail(url, resp);
                      return;
                  }

                  successes++;
                  dl += flen;

                  if (!isMultiFile)
                      break;
              } // for each file

              // all data received successfully, now process it
              if (_log.shouldDebug())
                  _log.debug("Fetch of piece: " + piece + " chunks: " + requests.size() + " offset: " + off + " torrent offset: " + toff + " len: " + tlen + " successful");
              DataInputStream dis = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
              for (Request req : requests) {
                  req.read(dis);
              }

              PartialPiece pp = last.getPartialPiece();
              synchronized(pp) {
                  // Last chunk needed for this piece?
                  if (pp.getLength() == pp.getDownloaded()) {
                      if (listener.gotPiece(this, pp)) {
                          if (_log.shouldDebug())
                              _log.debug("Got " + piece + ": " + this);
                      } else {
                          if (_log.shouldWarn())
                              _log.warn("Got BAD " + piece + " from " + this);
                          return;
                      }
                  } else {
                      // piece not complete
                  }
              }

              long time = lastRcvd - start;
              if (time < TARGET_FETCH_TIME)
                  maxRequests = Math.min(MAX_REQUESTS, 2 * maxRequests);
              else if (time >  2 * TARGET_FETCH_TIME)
                  maxRequests = Math.max(MIN_REQUESTS, maxRequests / 2);
          } // request loop
      } catch(IOException eofe) {
          if (_log.shouldWarn())
              _log.warn(toString(), eofe);
      } finally {
          List<Request> pcs = returnPartialPieces();
          synchronized(this) {
              connected = false;
              outstandingRequests.clear();
          }
          requests.clear();
          if (!pcs.isEmpty())
              listener.savePartialPieces(this, pcs);
          listener.disconnected(this);
          disconnect();
          if (_log.shouldWarn())
              _log.warn("Completed, successful fetches: " + successes + " downloaded: " + dl + " for " + this);
      }
  }

  private void fail(String url, int resp) {
      if (_log.shouldWarn())
          _log.warn("Fetch of " + url + " failed, rc: " + resp);
      if (resp == 301 || resp == 308 ||
          resp == 401 || resp == 403 || resp == 404 || resp == 410 || resp == 414 || resp == 416 || resp == 451) {
          // ban forever
          _coordinator.banWebPeer(_uri.getHost(), true);
          if (_log.shouldWarn())
              _log.warn("Permanently banning the webseed " + url);
      } else if (resp == 429 || resp == 503) {
          // ban for a while
          _coordinator.banWebPeer(_uri.getHost(), false);
          if (_log.shouldWarn())
              _log.warn("Temporarily banning the webseed " + url);
      }
  }

  @Override
  public int getMaxPipeline() {
      return maxRequests;
  }

  @Override
  public boolean isConnected() {
      synchronized(this) {
          return connected;
      }
  }

  @Override
  synchronized void disconnect() {
      if (thread != null)
          thread.interrupt();
  }

  @Override
  public void have(int piece) {}

  @Override
  void cancel(int piece) {}

  @Override
  void request() {
      addRequest();
  }

  @Override
  public boolean isInterested() {
      return false;
  }

  @Deprecated
  @Override
  public void setInteresting(boolean interest) {}

  @Override
  public boolean isInteresting() {
      return true;
  }

  @Override
  public void setChoking(boolean choke) {}

  @Override
  public boolean isChoking() {
      return false;
  }

  @Override
  public boolean isChoked() {
      return false;
  }
  
  @Override
  public long getInactiveTime() {
      if (lastRcvd <= 0)
          return -1;
      long now = System.currentTimeMillis();
      return now - lastRcvd;
  }

  @Override
  public long getMaxInactiveTime() {
      return PeerCoordinator.MAX_INACTIVE;
  }

  @Override
  public void keepAlive() {}

  @Override
  public void retransmitRequests() {}

  @Override
  public int completed() {
      return metainfo.getPieces();
  }

  @Override
  public boolean isCompleted() {
      return true;
  }

  /**
   * @return true
   * @since 0.9.49
   */
  @Override
  public boolean isWebPeer() {
      return false;
  }

  // private methods below here implementing parts of PeerState

  private synchronized void addRequest() {
      boolean more_pieces = true;
      while (more_pieces) {
          more_pieces = outstandingRequests.size() < getMaxPipeline();
          // We want something and we don't have outstanding requests?
          if (more_pieces && lastRequest == null) {
              // we have nothing in the queue right now
              more_pieces = requestNextPiece();
          } else if (more_pieces) {
              // We want something
              int pieceLength;
              boolean isLastChunk;
              pieceLength = metainfo.getPieceLength(lastRequest.getPiece());
              isLastChunk = lastRequest.off + lastRequest.len == pieceLength;

              // Last part of a piece?
              if (isLastChunk) {
                  more_pieces = requestNextPiece();
              } else {
                  PartialPiece nextPiece = lastRequest.getPartialPiece();
                  int nextBegin = lastRequest.off + PeerState.PARTSIZE;
                  int maxLength = pieceLength - nextBegin;
                  int nextLength = maxLength > PeerState.PARTSIZE ? PeerState.PARTSIZE
                                                        : maxLength;
                  Request req = new Request(nextPiece,nextBegin, nextLength);
                  outstandingRequests.add(req);
                  lastRequest = req;
                  this.notifyAll();
              }
          }
      }
  }

  /**
   * Starts requesting first chunk of next piece. Returns true if
   * something has been added to the requests, false otherwise.
   */
  private synchronized boolean requestNextPiece() {
      // Check for adopting an orphaned partial piece
      PartialPiece pp = listener.getPartialPiece(this, bitfield);
      if (pp != null) {
          // Double-check that r not already in outstandingRequests
          if (!getRequestedPieces().contains(Integer.valueOf(pp.getPiece()))) {
              Request r = pp.getRequest();
              outstandingRequests.add(r);
              lastRequest = r;
              this.notifyAll();
              return true;
          } else {
              if (_log.shouldLog(Log.WARN))
                  _log.warn("Got dup from coord: " + pp);
              pp.release();
          }
      }

      // failsafe
      // However this is bad as it thrashes the peer when we change our mind
      // Ticket 691 cause here?
      if (outstandingRequests.isEmpty())
          lastRequest = null;

/*
      // If we are not in the end game, we may run out of things to request
      // because we are asking other peers. Set not-interesting now rather than
      // wait for those other requests to be satisfied via havePiece()
      if (interesting && lastRequest == null) {
          interesting = false;
          out.sendInterest(false);
          if (_log.shouldLog(Log.DEBUG))
              _log.debug(peer + " nothing more to request, now uninteresting");
      }
*/
      return false;
  }

  /**
   * @return all pieces we are currently requesting, or empty Set
   */
  private synchronized Set<Integer> getRequestedPieces() {
      Set<Integer> rv = new HashSet<Integer>(outstandingRequests.size() + 1);
      for (Request req : outstandingRequests) {
          rv.add(Integer.valueOf(req.getPiece()));
      }
      return rv;
  }

  /**
   *  @return index in outstandingRequests or -1
   */
  private synchronized int getFirstOutstandingRequest(int piece) {
      for (int i = 0; i < outstandingRequests.size(); i++) {
          if (outstandingRequests.get(i).getPiece() == piece)
              return i;
      }
      return -1;
  }

  private synchronized List<Request> returnPartialPieces() {
      Set<Integer> pcs = getRequestedPieces();
      List<Request> rv = new ArrayList<Request>(pcs.size());
      for (Integer p : pcs) {
          Request req = getLowestOutstandingRequest(p.intValue());
          if (req != null) {
              PartialPiece pp = req.getPartialPiece();
              synchronized(pp) {
                  int dl = pp.getDownloaded();
                  if (req.off != dl)
                      req = new Request(pp, dl);
              }
              rv.add(req);
          }
      }
      outstandingRequests.clear();
      return rv;
  }

  private synchronized Request getLowestOutstandingRequest(int piece) {
      Request rv = null;
      int lowest = Integer.MAX_VALUE;
      for (Request r :  outstandingRequests) {
          if (r.getPiece() == piece && r.off < lowest) {
              lowest = r.off;
              rv = r;
          }
      }
      return rv;
  }

    // EepGet status listeners to maintain the state for the web page

    public void bytesTransferred(long alreadyTransferred, int currentWrite, long bytesTransferred, long bytesRemaining, String url) {
        lastRcvd = System.currentTimeMillis();
        downloaded(currentWrite);
        listener.downloaded(this, currentWrite);
    }

    public void attemptFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt, int numRetries, Exception cause) {}
    public void transferComplete(long alreadyTransferred, long bytesTransferred, long bytesRemaining, String url, String outputFile, boolean notModified) {}
    public void transferFailed(String url, long bytesTransferred, long bytesRemaining, int currentAttempt) {}
    public void headerReceived(String url, int attemptNum, String key, String val) {}
    public void attempting(String url) {}

    // End of EepGet status listeners
}
