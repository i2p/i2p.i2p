package net.i2p.router.transport;

import org.junit.Before;
import org.junit.Test;

import net.i2p.data.Hash;
import net.i2p.router.Job;
import net.i2p.router.MessageSelector;
import net.i2p.router.OutNetMessage;
import net.i2p.router.RouterContext;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A local outage must not leave remote peers banned after connectivity returns. */
public class GetBidsJobTest {
    private RouterContext context;
    private TransportManager transports;
    private OutNetMessage message;
    private Hash peer;

    @Before
    public void setUp() {
        context = mock(RouterContext.class, RETURNS_DEEP_STUBS);
        transports = mock(TransportManager.class);
        message = mock(OutNetMessage.class, RETURNS_DEEP_STUBS);
        peer = Hash.create(new byte[32]);
        when(message.getTarget().getIdentity().getHash()).thenReturn(peer);
        byte[] ourHash = new byte[32];
        ourHash[0] = 1;
        when(context.routerHash()).thenReturn(Hash.create(ourHash));
        when(transports.getTransportCount()).thenReturn(2);
        // Avoid artificial deep-stub callbacks on a failed message.
        when(message.getOnFailedSendJob()).thenReturn(null);
        when(message.getOnFailedReplyJob()).thenReturn(null);
        when(message.getReplySelector()).thenReturn(null);
    }

    @Test
    public void noLocalPeersDoesNotBanRemoteForMissingBid() {
        GetBidsJob.getBids(context, transports, message);
        verify(context.banlist(), never()).banlistRouter(eq(peer), anyString());
        verify(message).discardData();
    }

    @Test
    public void noLocalTransportsDoesNotBanRemoteForMissingBid() {
        when(transports.getTransportCount()).thenReturn(0);
        GetBidsJob.getBids(context, transports, message);
        verify(context.banlist(), never()).banlistRouter(eq(peer), anyString());
        verify(message).discardData();
    }

    @Test
    public void missingRouteStillBansPeerWhenOtherPeersAreReachable() {
        when(transports.countActivePeers()).thenReturn(4);
        GetBidsJob.getBids(context, transports, message);
        verify(context.banlist()).banlistRouter(eq(peer), anyString());
    }

    @Test
    public void outageStillRunsFailureCallbacksAndUnregistersReply() {
        Job sendFailure = mock(Job.class);
        Job replyFailure = mock(Job.class);
        MessageSelector selector = mock(MessageSelector.class);
        when(message.getOnFailedSendJob()).thenReturn(sendFailure);
        when(message.getOnFailedReplyJob()).thenReturn(replyFailure);
        when(message.getReplySelector()).thenReturn(selector);
        GetBidsJob.getBids(context, transports, message);
        verify(context.jobQueue()).addJob(sendFailure);
        verify(context.jobQueue()).addJob(replyFailure);
        verify(context.messageRegistry()).unregisterPending(message);
        verify(context.banlist(), never()).banlistRouter(eq(peer), anyString());
    }

    @Test
    public void establishedAlternateTransportStillSends() {
        Transport transport = mock(Transport.class);
        TransportBid bid = new TransportBid();
        bid.setTransport(transport);
        when(transports.getNextBid(message)).thenReturn(bid);
        GetBidsJob.getBids(context, transports, message);
        verify(transport).send(message);
        verify(message, never()).discardData();
        verify(context.banlist(), never()).banlistRouter(eq(peer), anyString());
    }
}
