package i2p.susi.webmail;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.servlet.*;
import javax.servlet.http.*;
/** Exercise observable request admission without network sockets or production accounts. */
public final class SusiMailRequestRegression {
    private static int checks, failures;
    private static void check(boolean ok, String label) { checks++; if (!ok) { failures++; System.out.println("FAIL: " + label); } }
    private static HttpServletRequest request(String verb, long size, String query, String type, Map<String,String[]> fields) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class}, (proxy, call, args) -> {
            switch(call.getName()) {
                case "getMethod": return verb;
                case "getContentLengthLong": return size;
                case "getQueryString": return query;
                case "getContentType": return type;
                case "getParameterMap": return fields;
                default: return null;
            }
        });
    }
    private static final class Response {
        int status = 200;
        final Map<String,String> headers = new HashMap<String,String>();
        final HttpServletResponse value = (HttpServletResponse) Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class}, (proxy, call, args) -> {
            if (call.getName().equals("sendError")) status=(Integer)args[0];
            if (call.getName().equals("setHeader")) headers.put(args[0].toString(),args[1].toString());
            if (call.getName().equals("isCommitted")) return false;
            return null;
        });
    }
    public static void main(String[] args) throws Exception {
        MailRequestFilter filter=new MailRequestFilter();
        String password=" punctuation!<>&'\"$ with spaces ";
        Response good=new Response(); AtomicBoolean forwarded=new AtomicBoolean();
        Map<String,String[]> fields=Map.of("pass",new String[]{password},"new_text",new String[]{"<ordinary mail text>&"});
        filter.doFilter(request("POST",100,null,"application/x-www-form-urlencoded",fields),good.value,(req,res)->forwarded.set(((HttpServletRequest)req).getParameterMap().get("pass")[0].equals(password)));
        check(forwarded.get() && good.status==200,"private form values retain punctuation and spaces");
        check(good.headers.get("Cache-Control").contains("no-store"),"request boundary sets private no-store responses");
        List<HttpServletRequest> bad=List.of(request("DELETE",0,null,null,Map.of()), request("POST",7629903,null,null,Map.of()),request("GET",0,"x".repeat(16385),null,Map.of()), request("POST",0,null,null,Map.of("pass",new String[]{"one","two"})),request("POST",0,null,null,Map.of("bad\nname",new String[]{"text"})),request("POST",0,null,null,Map.of("new_text",new String[]{"x".repeat(65537)})),request("GET",0,null,"multipart/form-data",Map.of()));
        int[] statuses={405,413,413,400,400,400,400};
        for(int i=0;i<bad.size();i++){Response response=new Response();AtomicBoolean passed=new AtomicBoolean();filter.doFilter(bad.get(i),response.value,(req,res)->passed.set(true));check(response.status==statuses[i]&&!passed.get(),"invalid request rejected at boundary "+i);}
        Map<String,String[]> excessive=new HashMap<String,String[]>(); for(int i=0;i<513;i++)excessive.put("field"+i,new String[]{"x"});Response response=new Response();filter.doFilter(request("POST",0,null,null,excessive),response.value,(req,res)->{throw new AssertionError();});check(response.status==400,"field count is bounded");
        CountDownLatch entered=new CountDownLatch(8),release=new CountDownLatch(1);AtomicInteger errors=new AtomicInteger();List<Thread> workers=new ArrayList<Thread>();
        for(int i=0;i<8;i++){Thread t=new Thread(()->{try{filter.doFilter(request("POST",0,null,null,Map.of()),new Response().value,(req,res)->{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new ServletException("fixture timeout");}catch(InterruptedException e){throw new ServletException(e);}});}catch(Exception e){errors.incrementAndGet();}});workers.add(t);t.start();}
        boolean saturated=entered.await(10,TimeUnit.SECONDS);
        try{Response ninth=new Response();filter.doFilter(request("POST",0,null,null,Map.of()),ninth.value,(req,res)->{throw new AssertionError();});check(saturated&&ninth.status==429,"concurrent form parsing work is bounded");}finally{release.countDown();for(Thread t:workers)t.join();}
        check(errors.get()==0,"admitted form work completes without errors");
        System.out.println("SUSIMAIL_REQUEST_CHECKS="+checks+"; FAILURES="+failures);if(failures!=0)System.exit(1);
    }
}
