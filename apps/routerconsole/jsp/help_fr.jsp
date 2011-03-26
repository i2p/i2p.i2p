<%@page contentType="text/html"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
  /*
   *   Do not tag this file for translation - copy it to help_xx.jsp and translate inline.
   */
%>
<html><head><title>Console du routeur I2P - Aide</title>
<%@include file="css.jsi" %>
</head><body>
<%@include file="summary.jsi" %>
Traduction de mars 2011 (magma@mail.i2p)
<h1>Aide et assistance du routeur I2P</h1>
<div class="main" id="main"><p>
Si vous souhaitez améliorer ou traduire la documentation ou d'autres versants du projet, merci de vous reporter à 
la page consacrée aux <a href="http://www.i2p2.i2p/getinvolved_fr.html">volontaires</a>.
</p><p>D'autres détails sont disponibles ici:
<ul class="links">
<li class="tidylist"><a href="http://www.i2p2.i2p/faq.html">FAQ en anglais sur www.i2p2.i2p</a>
<li class="tidylist"><a href="http://www.i2p2.i2p/faq_fr.html">les FAQ en français</a>.</ul>
<br>Il y a aussi le <a href="http://forum.i2p/">forum I2P</a>
et l'IRC.</p>

<h2>Informations du panneau de contrôle</h2><p>
Plusieurs des statistiques du panneau de contrôle peuvent être <a href="configstats.jsp">configurées</a> pour être 
affichées sous forme de <a href="graphs.jsp">graphiques</a> pour l'analyse à postériori.
</p><h3>GÉNÉRAL</h3><ul>
<li class="tidylist"><b>Identité locale:</b>
Cliquez sur "Afficher" pour voir l'empreinte Base64 à 44 caractères (256 bits) de votre routeur. Le hachage 
complet est affiché sur votre <a href="netdb.jsp?r=.">page d'infos routeur</a>. Ne la divulguez jamais à personne, 
car l'info routeur contient votre adresse IP.
<li class="tidylist"><b>Version:</b>
La version d'I2P qui vous affiche actuellement cette page.
<li class="tidylist"><b>Lancé depuis:</b>
Indique depuis combien de temps le routeur tourne.
<li class="tidylist"><b>Réseau:</b>
Statut de joignabilité du routeur par les autres routeurs.
Plus d'infos sur la page de <a href="config.jsp#help">configuration</a>.
</ul><h3>Pairs</h3><ul>
<li class="tidylist"><b>Actifs:</b>
le premier nombre est celui des routeurs avec qui le votre a communiqué dans les dernières minutes. Ça peut varier de 
8-10 à plusieurs centaines, selon votre bande passante et son rapport de partage, et le trafic généré localement. Le 
second est celui des pairs vus dans les dernières heures. Ces nombres penvent varier sensiblement sans conséquence.
<a href="configstats.jsp#router.activePeers">[Activer les courbes]</a>.
<li class="tidylist"><b>Rapides:</b>
le nombre de pairs que vous mettez à contribution pour construire vos tunnels clients. En général dans une tranche de 
8 à 30. Vos pairs rapides sont détaillés sur la page <a href="profiles.jsp">profils</a>.
<a href="configstats.jsp#router.fastPeers">[Activer les courbes]</a>.
<li class="tidylist"><b>Hautes capacités:</b>
nombre des pairs que vous utilisez pour construire quelques uns de vos tunnels exploratoires. Habituellement de 8 à 75. 
Les pairs rapides font partie du groupe des \"Hautes capacités\". Vos pairs à hautes capacités sont aussi listés sur 
la page <a href="profiles.jsp">profils</a>.
<a href="configstats.jsp#router.highCapacityPeers">[Activer les courbes]</a>.
<li class="tidylist"><b>Bien intégrés:</b>
vous utilisez ce groupe pour vos requêtes à la base de données du réseau. Ils sont souvent des pairs de remplissage par 
diffusion ("floodfill"). Vos pairs "bien intégrés" sont affichés en bas de la même page 
<a href="profiles.jsp">profils</a>.
<li class="tidylist"><b>Connus:</b> c'est tous les routeurs dont vous connaissez l'existance. Il sont listés sur la 
page <a href="netdb.jsp">base de données du réseau</a>. De moins de 100 à 1000 ou plus. Ce nombre ne représente pas la 
taille totale du réseau; il varie en fonction de votre bande passante totale et son rapport de partage, et du trafic 
local. I2P n'a pas besoin que chaque routeur connaisse tous les autres.
</ul><h3>Bande passante entrée/sortie</h3><div align="justify"> 
Ça parle tout seul. Toutes les valeurs sont en octets par seconde (o/s), pas en bits par seconde (b/s). Modifiez vos 
limites de bande passante sur la page de <a href="config.jsp#help">configuration</a>.
Le graphique de <a href="graphs.jsp">bande passante</a> est activé par défaut.</div>

<h3>Destinations locales</h3><div align="justify">
C'est le nom I2P des applications qui se connectent par votre routeur. Elles peuvent être des clients lancés depuis 
<a href="i2ptunnel/index.jsp">I2PTunnel</a> ou des programmes tiers qui se connectent via SAM, BOB ou directement à 
I2CP.
</div><h3>TUNNELS:</h3><div align="justify">
Les tunnels actuels sont affichés sur la page <a href="tunnels.jsp">tunnels</a>.</div><ul>
<li class="tidylist"><div align="justify"><b>Exploratoires:</b> tunnels créés par votre routeur et utilisés avec les 
pairs diffuseurs pour la création des nouveaux tunnels et le test des tunnels existants.</div>
<li class="tidylist"><b>Clients:</b> tunnels créés par votre routeur pour chaque utilisation cliente.
<li class="tidylist"><b>Participants:</b> les tunnels créés par d'autres routeurs et qui passent par le votre. Leur 
nombre dépend largement de la demande du réseau, de votre part de bande passante partagée, et du trafic local. 
La méthode recommandée pour limiter leur nombre est de diminuer le rapport de bande passante partagée dans la 
<a href="config.jsp#help">configuration</a>. Vous pouvez également limiter ce nombre en définissant la variable 
<tt>router.maxParticipatingTunnels=nnn</tt> dans la <a href="configadvanced.jsp">configuration avancée</a>. 
<a href="configstats.jsp#tunnel.participatingTunnels">[Activer les statistiques]</a>.
<li class="tidylist"><b>Rapport de partage:</b> le nombre de tunnels participants que vous routez pour les autres, 
divisé par le nombre total de sauts dans tous vos tunnels exploratoires et clients. S'il est supérieur à 1, cela 
signifie que vous contribuez à plus de tunnels que vous n'en utilisez.
</ul>

<h3>ENCOMBREMENT</h3><div align="justify">
Indications de base sur la charge du routeur:</div><ul>
<li class="tidylist"><b>Retard de tâches:</b>
How long jobs are waiting before execution. The job queue is listed on the <a href="jobs.jsp">jobs page</a>.
Unfortunately, there are several other job queues in the router that may be congested,
and their status is not available in the router console.
The job lag should generally be zero.
If it is consistently higher than 500ms, your computer is very slow, or the
router has serious problems.
<a href="configstats.jsp#jobQueue.jobLag">[Enable graphing]</a>.
<li class="tidylist"><b>Retard de messages:</b>
How long an outbound message waits in the queue.
This should generally be a few hundred milliseconds or less.
If it is consistently higher than 1000ms, your computer is very slow,
or you should adjust your bandwidth limits, or your (bittorrent?) clients
may be sending too much data and should have their transmit bandwidth limit reduced.
<a href="configstats.jsp#transport.sendProcessingTime">[Enable graphing]</a> (transport.sendProcessingTime).
<li class="tidylist"><b>Retard de tunnels:</b>
This is the round trip time for a tunnel test, which sends a single message
out a client tunnel and in an exploratory tunnel, or vice versa.
It should usually be less than 5 seconds.
If it is consistently higher than that, your computer is very slow,
or you should adjust your bandwidth limits, or there are network problems.
<a href="configstats.jsp#tunnel.testSuccessTime">[Enable graphing]</a> (tunnel.testSuccessTime).
<li class="tidylist"><b>En attente:</b>
This is the number of pending requests from other routers to build a
participating tunnel through your router.
It should usually be close to zero.
If it is consistently high, your computer is too slow,
and you should reduce your share bandwidth limits.
<li class="tidylist"><b>Accepting/Rejecting:</b>
Your router's status on accepting or rejecting
requests from other routers to build a
participating tunnel through your router.
Your router may accept all requests, accept or reject a percentage of requests,
or reject all requests for a number of reasons, to control
the bandwidth and CPU demands and maintain capacity for
local clients.</ul>

<h2>Legal stuff</h2><p>The I2P router (router.jar) and SDK (i2p.jar) are almost entirely public domain, with
a few notable exceptions:</p><ul>
<li class="tidylist">ElGamal and DSA code, under the BSD license, written by TheCrypto</li>
<li class="tidylist">SHA256 and HMAC-SHA256, under the MIT license, written by the Legion of the Bouncycastle</li>
<li class="tidylist">AES code, under the Cryptix (MIT) license, written by the Cryptix team</li>
<li class="tidylist">SNTP code, under the BSD license, written by Adam Buckley</li>
<li class="tidylist">The rest is outright public domain, written by jrandom, mihi, hypercubus, oOo,
    ugha, duck, shendaras, and others.</li>
</ul>

<p>On top of the I2P router are a series of client applications, each with their own set of
licenses and dependencies.  This webpage is being served as part of the I2P routerconsole
client application, which is built off a trimmed down <a href="http://jetty.mortbay.com/jetty/index.html">Jetty</a>
instance (trimmed down, as in, we do not include the demo apps or other add-ons, and we simplify configuration),
allowing you to deploy standard JSP/Servlet web applications into your router.  Jetty in turn makes use of
Apache's javax.servlet (javax.servlet.jar) implementation.
This product includes software developed by the Apache Software Foundation
(http://www.apache.org/). </p>

<p>Another application you can see on this webpage is <a href="http://www.i2p2.i2p/i2ptunnel">I2PTunnel</a>
(your <a href="i2ptunnel/" target="_blank">web interface</a>) - a GPL'ed application written by mihi that
lets you tunnel normal TCP/IP traffic over I2P (such as the eepproxy and the irc proxy).  There is also a
<a href="http://susi.i2p/">susimail</a> web based mail client <a href="susimail/susimail">available</a> on
the console, which is a GPL'ed application written by susi23.  The addressbook application, written by
<a href="http://ragnarok.i2p/">Ragnarok</a> helps maintain your hosts.txt files (see ./addressbook/ for
more information).</p>

<p>The router by default also includes human's public domain <a href="http://www.i2p2.i2p/sam">SAM</a> bridge,
which other client applications (such the <a href="http://duck.i2p/i2p-bt/">bittorrent port</a>) can use.
There is also an optimized library for doing large number calculations - jbigi - which in turn uses the
LGPL licensed <a href="http://swox.com/gmp/">GMP</a> library, tuned for various PC architectures. 
Launchers for windows users are built 
with <a href="http://launch4j.sourceforge.net/">Launch4J</a>, and the installer is built with 
<a href="http://www.izforge.com/izpack/">IzPack</a>.  For
details on other applications available, as well as their licenses, please see the
<a href="http://www.i2p2.i2p/licenses">license policy</a>.  Source for the I2P code and most bundled
client applications can be found on our <a href="http://www.i2p2.i2p/download">download page</a>.
.</p>

<h2>Change Log</h2>
 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <% java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "history.txt"); %>
 <jsp:setProperty name="contenthelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="256" />
 <jsp:setProperty name="contenthelper" property="startAtBeginning" value="true" />
 <jsp:getProperty name="contenthelper" property="textContent" />

 <p><a href="/history.txt">View the full change log</a>
 </p><hr></div></body></html>
