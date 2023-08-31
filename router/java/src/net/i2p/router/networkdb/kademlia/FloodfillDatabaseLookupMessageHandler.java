package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

/**
 * Build a HandleDatabaseLookupMessageJob whenever a DatabaseLookupMessage
 * arrives
 *
 */
public class FloodfillDatabaseLookupMessageHandler implements HandlerJobBuilder {
    private RouterContext _context;
    private FloodfillNetworkDatabaseFacade _facade;
    private Log _log;
    private final long _msgIDBloomXor = RandomSource.getInstance().nextLong(I2NPMessage.MAX_ID_VALUE);

    public FloodfillDatabaseLookupMessageHandler(RouterContext context, FloodfillNetworkDatabaseFacade facade) {
        _context = context;
        _facade = facade;
        _log = context.logManager().getLog(FloodfillDatabaseLookupMessageHandler.class);
        _context.statManager().createRateStat("netDb.lookupsReceived", "How many netDb lookups have we received?", "NetworkDatabase", new long[] { 60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsDropped", "How many netDb lookups did we drop due to throttling?", "NetworkDatabase", new long[] { 60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsDroppedDueToPriorBan", "How many netDb lookups did we drop due to having a prior ban?", "NetworkDatabase", new long[] { 60*60*1000l });
        _context.statManager().createRateStat("netDb.nonFFLookupsDropped", "How many netDb lookups did we drop due to us not being a floodfill?", "NetworkDatabase", new long[] { 60*60*1000l });
        _context.statManager().createRateStat("netDb.repeatedLookupsDropped", "How many netDb lookups are coming in faster than we want?", "NetworkDatabase", new long[] { 60*60*1000l });
        _context.statManager().createRateStat("netDb.repeatedBurstLookupsDropped", "How many netDb lookups did we drop due to burst throttling?", "NetworkDatabase", new long[] { 60*60*1000l });
        // following are for ../HDLMJ
        _context.statManager().createRateStat("netDb.lookupsHandled", "How many netDb lookups have we handled?",
                "NetworkDatabase", new long[] { 60 * 60 * 1000l });
        _context.statManager().createRateStat("netDb.lookupsMatched",
                "How many netDb lookups did we have the data for?", "NetworkDatabase", new long[] { 60 * 60 * 1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedLeaseSet",
                "How many netDb leaseSet lookups did we have the data for?", "NetworkDatabase",
                new long[] { 60 * 60 * 1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedReceivedPublished",
                "How many netDb lookups did we have the data for that were published to us?", "NetworkDatabase",
                new long[] { 60 * 60 * 1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedLocalClosest",
                "How many netDb lookups for local data were received where we are the closest peers?",
                "NetworkDatabase", new long[] { 60 * 60 * 1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedLocalNotClosest",
                "How many netDb lookups for local data were received where we are NOT the closest peers?",
                "NetworkDatabase", new long[] { 60 * 60 * 1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedRemoteNotClosest",
                "How many netDb lookups for remote data were received where we are NOT the closest peers?",
                "NetworkDatabase", new long[] { 60 * 60 * 1000l });
    }

    public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        _context.statManager().addRateData("netDb.lookupsReceived", 1);

        DatabaseLookupMessage dlm = (DatabaseLookupMessage)receivedMessage;
        boolean isBanned = dlm.getFrom() != null
                           && (_context.banlist().isBanlistedHard(dlm.getFrom())
                           || _context.banlist().isBanlisted(dlm.getFrom()));
        if (isBanned) {
            _context.statManager().addRateData("netDb.lookupsDroppedDueToPriorBan", 1);
            return null;
        }
        boolean ourRI = dlm.getSearchKey() != null && dlm.getSearchKey().equals(_context.routerHash());
        if (!_context.floodfillNetDb().floodfillEnabled() && (dlm.getReplyTunnel() == null && !ourRI)) {
            if (_log.shouldLog(Log.WARN)) 
                _log.warn("[dbid: " + _facade._dbid
                          + "] Dropping " + dlm.getSearchType()
                          + " lookup request for " + dlm.getSearchKey()
                          + " (we are not a floodfill), reply was to: "
                          + dlm.getFrom() + " tunnel: " + dlm.getReplyTunnel());
            _context.statManager().addRateData("netDb.nonFFLookupsDropped", 1);
            return null;
        }

        if (_facade.shouldBanLookup(dlm.getFrom(), dlm.getReplyTunnel())) {
            if (_log.shouldLog(Log.WARN)) {
                _log.warn("[dbid: " + _facade._dbid
                          + "] Possibly throttling " + dlm.getSearchType()
                          + " lookup request for " + dlm.getSearchKey()
                          + " because requests are being sent extremely fast, reply was to: "
                          + dlm.getFrom() + " tunnel: " + dlm.getReplyTunnel());
                _context.statManager().addRateData("netDb.repeatedLookupsDropped", 1);
            }
            /*
             * TODO: Keep a close eye on this, if it results in too many bans then just back
             * it out.
             */
        }
        if (_facade.shouldBanBurstLookup(dlm.getFrom(), dlm.getReplyTunnel())) {
            if (_log.shouldLog(Log.WARN)) {
                _log.warn("[dbid: " + _facade._dbid
                          + "] Banning " + dlm.getSearchType()
                          + " lookup request for " + dlm.getSearchKey()
                          + " because requests are being sent extremely fast in a very short time, reply was to: "
                          + dlm.getFrom() + " tunnel: " + dlm.getReplyTunnel());
                _context.statManager().addRateData("netDb.repeatedBurstLookupsDropped", 1);
            }
            _context.banlist().banlistRouter(dlm.getFrom(), " <b>➜</b> Excessive lookup requests, burst", null,
                                             _context.banlist().BANLIST_CODE_HARD, null,
                                             _context.clock().now() + 4*60*60*1000);
            _context.commSystem().mayDisconnect(dlm.getFrom());
            _context.statManager().addRateData("netDb.lookupsDropped", 1);
            return null;
        }
        if (_facade.shouldBanBurstLookup(dlm.getFrom(), dlm.getReplyTunnel())) {
            if (_log.shouldLog(Log.WARN)) {
                _log.warn("Banning " + dlm.getSearchType() + " lookup request for " + dlm.getSearchKey()
                        + " because requests are being sent extremely fast in a very short time, reply was to: "
                        + dlm.getFrom() + " tunnel: " + dlm.getReplyTunnel());
                _context.statManager().addRateData("netDb.repeatedBurstLookupsDropped", 1);
            }
            _context.banlist().banlistRouter(dlm.getFrom(), " <b>➜</b> Excessive lookup requests, burst", null, null,
                    _context.clock().now() + 4 * 60 * 60 * 1000);
            _context.commSystem().mayDisconnect(dlm.getFrom());
            _context.statManager().addRateData("netDb.lookupsDropped", 1);
            return null;
        }
        if ((!_facade.shouldThrottleLookup(dlm.getFrom(), dlm.getReplyTunnel())
                && !_facade.shouldThrottleBurstLookup(dlm.getFrom(), dlm.getReplyTunnel()))
                || _context.routerHash().equals(dlm.getFrom())) {
            Job j = new HandleFloodfillDatabaseLookupMessageJob(_context, dlm, from, fromHash, _msgIDBloomXor);
            // if (false) {
            // // might as well inline it, all the heavy lifting is queued up in later jobs,
            // if necessary
            // j.runJob();
            // return null;
            // } else {
            return j;
            // }
        } else {
            if (_log.shouldLog(Log.WARN)) 
                _log.warn("[dbid: " + _facade._dbid
                          + "] Dropping " + dlm.getSearchType()
                          + " lookup request for " + dlm.getSearchKey()
                          + " (throttled), reply was to: " + dlm.getFrom()
                          + " tunnel: " + dlm.getReplyTunnel());
            _context.statManager().addRateData("netDb.lookupsDropped", 1);
            return null;
        }
    }
}
