/*
 * HttpServerTest.java
 * Created on April 9, 2005, 2:59 AM
 */
package net.i2p.aum.test;

import net.i2p.aum.http.MiniHttpRequestPage;
import net.i2p.aum.http.MiniHttpServer;

public class HttpServerTest extends MiniHttpRequestPage {
    
    public HttpServerTest(MiniHttpServer serv, Object socket) throws Exception {
        super(serv, socket);
    }

    public void on_GET() throws Exception {

        setContentType("text/html");
        setServer("aum's MiniHttpServer demo default - GET");
        head.nest("title").raw("aum's MiniHttpServer demo - GET");
        body.nest("h1")
            .raw("aum MiniHttpServer demo - GET");
        body.raw("You requested: "+reqFile)
            .br().br()
            .nest("form action=foo.html method=POST")
                .add("input type=submit name=doit value=doit")
                .br()
                .add("input type=text name=fred value=blah")
                .br()
                .nest("textarea name=mary")
                    .raw("red green");
        body.br()
            .add(dumpVars());
    }

    public void on_POST() {
        setContentType("text/html");
        setServer("aum's MiniHttpServer demo default - POST");

        head.nest("title").raw("aum's MiniHttpServer demo - POST");
        body.nest("h1").raw("aum MiniHttpServer demo - POST");
        body.raw("You requested: "+reqFile)
            .br().br()
            .nest("form action=foo.html method=POST")
                .add("input type=submit name=doit value=doit")
                .br()
                .add("input type=text name=fred value=blah")
                .br()
                .nest("textarea name=mary")
                    .raw("red green");
        body.br()
            .add(dumpVars());
    }

    public static void main(String[] args) {
        MiniHttpServer serv = new MiniHttpServer(HttpServerTest.class, 18000);
        serv.run();
    }
}
