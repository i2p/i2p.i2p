package net.i2p.router.web.helpers;
import java.lang.reflect.Method;
import java.util.Properties;
import net.i2p.router.RouterContext;
public class NetworkStatusPresentationRegression {
 public static void main(String[] args) throws Exception {
  Properties properties = new Properties(); properties.setProperty("time.disabled", "true"); properties.setProperty("routerconsole.lang", "en");
  RouterContext context = new RouterContext(null, properties);
  Method render = SummaryBarRenderer.class.getDeclaredMethod("renderNetworkReachabilityHTML"); render.setAccessible(true);
  int checked = 0;
  try {
   for (SummaryHelper.NetworkState state : SummaryHelper.NetworkState.values()) {
    for (int active : new int[] {0, 3}) {
     SummaryHelper helper = new SummaryHelper() {
      public NetworkStateMessage getReachability() { return new NetworkStateMessage(state, state == NetworkState.FIREWALLED ? "Firewalled" : "Original " + state); }
      public int getActivePeers() { return active; }
     };
     String html = (String) render.invoke(new SummaryBarRenderer(context, helper));
     if (state == SummaryHelper.NetworkState.FIREWALLED && active > 0) {
      if (!html.contains("Network: Connected (Firewalled)") || !html.contains("Incoming connections are restricted.")) throw new AssertionError("Missing connected-firewalled explanation");
     } else {
      if (html.contains("Connected (") || html.contains("sb_netstatus_detail")) throw new AssertionError("Misleading connected status: " + state + " peers=" + active);
      String expected = state == SummaryHelper.NetworkState.FIREWALLED ? "Firewalled" : "Original " + state;
      if (!html.contains("Network: " + expected)) throw new AssertionError("Original status lost");
     }
     checked++;
    }
   }
   System.out.println("STATUS_PRESENTATION_CASES=" + checked + "; FAILURES=0");
  } finally { context.logManager().shutdown(); }
 }
}
