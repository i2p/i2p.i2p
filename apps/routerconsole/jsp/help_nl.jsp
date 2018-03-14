<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
  /*
   *   Do not tag this file for translation - copy it to help_xx.jsp and translate inline.
   */
%>
<html><head><title>I2P Router Console - help</title>
<%@include file="css.jsi" %>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<h1>I2P Router Help &amp; Support</h1>
<div class="main" id="help">

<div class="confignav">
<span class="tab"><a href="#sidebarhelp">Summary Bar</a></span>
<span class="tab"><a href="#configurationhelp">Configuratie</a></span>
<span class="tab"><a href="#reachabilityhelp">Bereikbaarheid</a></span>
<span class="tab"><a href="#advancedsettings">Geavanceerde Instellingen</a></span>
<span class="tab"><a href="#faq">FAQ</a></span>
<span class="tab"><a href="#legal">Juridische</a></span>
<span class="tab"><a href="#changelog">Geschiedenis</a></span>
</div>

<div id="volunteer">
<h2>Verdere Assistentie</h2>
<p>Als je wilt helpen om de documentatie te verbeteren of vertalen, of wilt helpen
met andere aspecten van het project, zie dan de documentatie voor 
<a href="http://i2p-projekt.i2p/nl/get-involved">vrijwilligers.</a>
</p><p>Verdere ondersteuning is hier beschikbaar:</p>
<ul class="links">
<li><a href="http://i2p-projekt.i2p/nl/faq">FAQ op i2p-projekt.i2p</a></li>
</ul><br>
</div>

<div id="sidebarhelp">
<h2>Informatie over de Summary Bar</h2><p>
Veel van de statistieken op de summary bar kunnen
<a href="configstats.jsp">geconfigureerd</a> worden om te worden
<a href="graphs.jsp">geplot</a> voor verdere analyse.
</p>

<h3>Algemeen</h3><ul>
<li><b>Lokale Identiteit:</b>
De eerste vier karakters (24 bits) van je 44-karakter (256-bit) Base64 router hash.
De volledige hash is getoond op je <a href="netdb.jsp?r=.">router info pagina</a>.
Vertel deze aan niemand, want de router info bevat je IP.</li>
<li><b>Versie:</b>
De versie van de I2P software die je nu gebruikt.</li>
<li><b>Uptime:</b>
Hoe lang je I2P router al draait.</li>
<li><b>Netwerk Bereikbaarheid:</b>
De bereikbaarheid van je router door andere routers.
Meer informatie is te vinden op de <a href="confignet#help">configuratie pagina</a>.</li>
</ul>

<h3>Peers</h3><ul>
<li><b>Actief:</b>
Het eerste nummer is het aantal peers waar je in de laatste paar minuten
berichten naar verzonden of van ontvangen hebt.  Dit kan vari&euml;ren van 8-10 tot
een paar honderd, afhankelijk van je totale bandbreedte, gedeelde bandbreedte
en lokaal gegenereerd verkeer.  Het tweede nummer is het aantal peers dat je in
het laatste uur hebt gezien.  Wees niet ongerust wanneer deze aantallen erg
vari&euml;ren.

<a href="configstats.jsp#router.activePeers">[Grafieken inschakelen]</a>.</li>
<li><b>Snel:</b>
Dit is het aantal peers dat je gebruikt om client tunnels mee te bouwen. Het ligt over
het algemeen tussen 8 en 30.
Je snelle peers worden getoond op de <a href="profiles.jsp">profielen pagina</a>.
<a href="configstats.jsp#router.fastPeers">[Grafieken inschakelen]</a>.</li>
<li><b>Grote capaciteit:</b>
Dit is het aantal peers dat je gebruikt om sommige van de onderzoekende tunnels mee te maken.
Het ligt over het algemeen tussen de 8 en 75. De snelle peers zijn inbegrepen in de categorie grote capaciteit.
De grote capaciteits peers worden getoond op de <a href="profiles.jsp">profielen pagina</a>.
<a href="configstats.jsp#router.highCapacityPeers">[Grafieken inschakelen]</a>.</li>
<li><b>Ge&iuml;ntegreerd:</b>
Dit is het aantal peers dat je gebruikt bij het opzoeken in de network database.
Dit zijn gebruikelijk de "floodfill" peers.
Je goed ge&iuml;ntegreerde peers worden getoond aan de onderkant van de <a href="profiles.jsp">profielen pagina</a>.</li>
<li><b>Bekend:</b>
Dit is het aantal routers dat bekend is bij je router.
Ze worden getoond op de <a href="netdb.jsp">netwerk database pagina</a>.
Dit kan liggen tussen onder de 100 en 1000 of meer.
Dit aantal is niet de totale grootte van het netwerk;
het kan erg vari&euml;ren afhankelijk van je totale bandbreedte,
gedeelde bandbreedte en lokaal gegenereerd verkeer.
Voor I2P is het niet nodig dat een router alle andere routers kent.
</li></ul>

<h3>Bandbreedte in/out</h3>
<p>Dit zou zichzelf moeten verklaren. Alle waarden zijn in bytes per seconde, niet in bits per seconde.
Wijzig je bandbreedte limieten op de <a href="confignet#help">configuratie pagina</a>.
Bandbreedte wordt standaard <a href="graphs.jsp">geplot</a>.</p>

<h3>Tunnels</h3>
<p>De tunnels zelf worden getoond op de <a href="tunnels.jsp">tunnels pagina</a>.</p>
<ul>
<li><b>Onderzoekend:</b>
Tunnels gebouwd door je router en gebruikt om te communiceren met de floodfill peers,
voor het bouwen van nieuwe tunnels en testen van bestaande tunnels.</li>
<li><b>Client:</b>
Tunnels gebouwd door je router voor het gebruik door elke client.</li>
<li><b>Deelnemend:</b>
Tunnels gebouwd door andere routers die door je eigen router heen lopen.
Dit kan erg vari&euml;ren afhankelijk van de vraag vanuit het netwerk,
je gedeelde bandbreedte en hoeveelheid lokaal gegenereerd verkeer.
De aanbevolen methode om het aantal deelnemende tunnels te beperken
is door het share percentage te wijzigen op de <a href="confignet#help">configuratie pagina</a>.
Je kan het totale aantal ook beperken met de instelling <tt>router.maxParticipatingTunnels=nnn</tt> op
de <a href="configadvanced.jsp">geavanceerde configuratie pagina</a>. <a href="configstats.jsp#tunnel.participatingTunnels">[Grafieken inschakelen]</a>.</li>
<li><b>Share rato:</b>
Het aantal deelnemende tunnels dat je voor andere routeert, gedeeld door het totale aantal hops in al je onderzoekende en client tunnels.
Een aantal groter dan 1.00 betekent dat je meer tunnels aan het netwerk bijdraagt dan je gebruikt.
</li>
</ul>

<h3>Verstopping</h3>
<p>Een aantal basis indicatoren voor een router overbelasting:</p>
<ul>
<li><b>Taak vertraging:</b>
Hoe lang taken moeten wachten voordat ze uitgevoerd worden. De taak wachtrij wordt getoond op de <a href="jobs.jsp">taken pagina</a>.
Helaas zijn er ook verschillende andere taak wachtrijen in de router die verstopt kunnen raken,
hun status is niet beschikbaar in de router console.
De taak vertraging zou over het algemeen nul moeten zijn.
Indien dit consequent hoger is dan 500ms, dan is of je computer erg traag of
heeft de router een serieus probleem.
<a href="configstats.jsp#jobQueue.jobLag">[Grafieken inschakelen]</a>.</li>
<li><b>Bericht vertraging:</b>
Hoe lang een uitgaand bericht wacht in de wachtrij.
Dit zou over het algemeen een aantal honderd milliseconden of minder moeten zijn.
Indien dit consequent hoger is dan 1000ms, dan is of je computer erg traag
of moet je je bandbreedte instellingen aanpassen, of je (bittorrent?) clients
versturen mogelijk teveel data en moeten hun transmissie bandbreedte beperkt hebben.
<a href="configstats.jsp#transport.sendProcessingTime">[Grafieken inschakelen]</a> (transport.sendProcessingTime).</li>
<li><b>Tunnel vertraging:</b>
Dit is de rondgangstijd voor een tunnel test, welke een enkel bericht verstuurt
vanuit een client tunnel dat dan bij een onderzoekende tunnel naar binnen gaat, of omgekeerd.
Dit zou over het algemeen minder dan 5 seconden moeten zijn.
Indien dit consequent hoger is, dan is of je computer erg traag of
moet je je bandbreedte instellingen aanpassen of zijn er netwerk problemen.
<a href="configstats.jsp#tunnel.testSuccessTime">[Grafieken inschakelen]</a> (tunnel.testSuccessTime).</li>
<li><b>Achterstand:</b>
Dit is het aantal wachtende aanvragen van andere routers om een deelnemende
tunnel door je eigen router te bouwen.
Dit zou over het algemeen dicht bij de nul moeten zijn.
Indien dit consequent hoog is, dan is of je computer te traag of
moet je je bandbreedte instellingen aanpassen.</li>
<li><b>Accepteren / Weigeren:</b>
De status van de router voor het accepteren of weigeren
van verzoeken van andere routers om een deelnemende tunnel door je router te bouwen.
Je router kan alle aanvragen accepteren, een percentage accepteren of weigeren,
of alle aanvragen weigeren om verschillende redenen, om de bandbreedte en CPU
gebruik te beheren en capaciteit te houden voor lokale clients.</li></ul>

<h3>Lokale bestemmingen</h3>
<p>De lokale applicaties die door je router verbinden. 
Dit kunnen clients zijn die gestart zijn door <a href="i2ptunnel/index.jsp">I2PTunnel</a>
of externe programma's die verbinden via SAM, BOB of direct met I2CP.</p>
</div>

<div id="configurationhelp"><%@include file="help-configuration.jsi" %></div>
<div id="reachabilityhelp"><%@include file="help-reachability.jsi" %></div>
<div id="advancedsettings"><%@include file="help-advancedsettings.jsi" %></div> <% /* untranslated */ %>
<div id="faq"><%@include file="help-faq.jsi" %></div> <% /* untranslated */ %>

<div id="legal">
<h2>Juridische zaken</h2>
<p>De I2P router (router.jar) en SDK (i2p.jar) zijn bijna geheel in het publieke domein, met een aantal noemenswaardige uitzonderingen:</p>
<ul>
<li>ElGamal en DSA code, valt onder de BSD licentie, geschreven door TheCrypto</li>
<li>SHA256 en HMAC-SHA256, valt onder de MIT licentie, geschreven door the Legion of the Bouncycastle</li>
<li>AES code, valt onder de MIT licentie, geschreven door het Cryptix team</li>
<li>SNTP code, valt onder de BSD licentie, geschreven door Adam Buckley</li>
<li>De rest is helemaal public domain, geschreven door jrandom, mihi, hypercubus, oOo, ugha, duck, shendaras, en anderen.</li>
</ul>

<p>Bovenop de I2P router zijn een aantal client applicaties gemaakt, elk met
hun eigen set aan licenties en afhankelijkheden.  Deze webpagine is aangeboden
als onderdeel van de I2P routerconsole client applicatie, gebouwd op basis van
een afgeslankte <a href="http://jetty.mortbay.com/jetty/index.html">Jetty</a>
instantie (afgeslankt, als in, we voegen geen demo applicaties of andere add-ons bij,
en hebben de configuratie eenvoudiger gemaakt),
welke je standaard JSP/Servlet web applicaties laat deployen in je router.
Jetty maakt gebruik van Apache's javax.servlet (javax.servlet.jar) implementatie.
Dit product bevat software ontwikkeld door de Apache Software Foundation
(http://www.apache.org/).</p>

<p>Een andere applicatie op deze webpagina is <a
href="http://i2p-projekt.i2p/nl/docs/api/i2ptunnel">I2PTunnel</a> (je <a href="i2ptunnel/"
target="_blank">web interface</a>) - een GPL applicatie geschreven door mihi
die normaal TCP/IP verkeer over I2P laat tunnelen (zoals de eepproxy en de irc
proxy). Er is ook een web based mail
client <a href="/susimail/">beschikbaar</a> op de console, dit is een
GPL applicatie geschreven door susi23. De adresboek applicatie, geschreven door
<a href="http://ragnarok.i2p/">Ragnarok</a> helpt je met het beheren van je
hosts.txt bestanden (zie ./addressbook/ voor meer informatie).</p>

<p>De router bevat ook standaard human's public domain <a
href="http://i2p-projekt.i2p/nl/docs/api/sam">SAM</a> brug, welke andere client applicaties
(zoals de <a href="http://duck.i2p/i2p-bt/">bittorrent port</a>) kan gebruiken.
Er is ook een geoptimaliseerde library voor het uitvoeren van berekeningen met
grote getallen - jbigi - deze gebruikt de LGPL licensed <a
href="http://swox.com/gmp/">GMP</a> library, aangepast voor verschillende PC
architecturen.  Opstarters voor windows gebruikers zijn gemaakt met <a
href="http://launch4j.sourceforge.net/">Launch4J</a>, en de installer is
gemaakt met <a href="http://www.izforge.com/izpack/">IzPack</a>.  Voor details
over andere beschikbare applicaties, en hun licenties, zie het <a
href="http://i2p-projekt.i2p/nl/get-involved/develop/licenses">licentie beleid</a>. Broncode voor I2P en
de meeste gebundelde client applicaties kan gevonden worden op onze <a
href="http://i2p-projekt.i2p/nl/download">download pagina</a>.</p>
</div>

<div id="changelog">
<h2>Release geschiedenis</h2>
 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <% java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "history.txt"); %>
 <jsp:setProperty name="contenthelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="512" />
 <jsp:setProperty name="contenthelper" property="startAtBeginning" value="true" />
 <jsp:getProperty name="contenthelper" property="textContent" />

 <p id="fullhistory"><a href="/history.txt" target="_blank">Completere lijst met wijzigingen</a></p>
</div>
</div></body></html>
