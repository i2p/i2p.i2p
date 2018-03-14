<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
  /*
   *   Do not tag this file for translation - copy it to help_xx.jsp and translate inline.
   */
%>
<html><head><title>Console du routeur I2P - Aide</title>
<%@include file="css.jsi" %>
<script src="/js/ajax.js" type="text/javascript"></script>
<%@include file="summaryajax.jsi" %>
</head><body onload="initAjax()">
<%@include file="summary.jsi" %>
<!-- Traduction de mars 2011 (magma@mail.i2p) -->
<h1>Aide et assistance du routeur I2P</h1>
<div class="main" id="help">

<div class="confignav">
<span class="tab"><a href="#sidebarhelp">Barre latérale</a></span>
<span class="tab"><a href="#configurationhelp">Configuration</a></span>
<span class="tab"><a href="#reachabilityhelp">Joignabilité</a></span>
<span class="tab"><a href="#advancedsettings">Réglages avancés</a></span>
<span class="tab"><a href="#faq">FAQ</a></span>
<span class="tab"><a href="#legal">Légal</a></span>
<span class="tab"><a href="#changelog">Historique</a></span>
</div>

<div id="volunteer">
<h2>Assistance Supplémentaire</h2>
<p>Si vous souhaitez améliorer ou traduire la documentation ou d'autres versants du projet, merci de vous reporter à 
la page consacrée aux <a href="http://i2p-projekt.i2p/fr/get-involved">volontaires</a>.
</p>D'autres détails sont disponibles ici:
<ul class="links">
<li><a href="http://i2p-projekt.i2p/en/faq">FAQ en anglais sur i2p-projekt.i2p</a></li>
<li><a href="http://i2p-projekt.i2p/fr/faq">les FAQ en français</a>.</li>
</ul><br>
</div>

<div id="sidebarhelp">
<h2>Informations du panneau de surveillance</h2><p>
Plusieurs des statistiques affichées dans le panneau de surveillance peuvent être 
<a href="configstats.jsp">configurées</a> pour être affichées sous forme de <a href="graphs.jsp">graphiques</a> pour 
analyse sur la durée.
</p><h3>GÉNÉRAL</h3><ul>
<li><b>Identité locale:</b>
Cliquez sur "Afficher" pour voir l'empreinte Base64 à 44 caractères (256 bits) de votre routeur. Le hachage 
complet est affiché sur votre <a href="netdb.jsp?r=.">page d'infos routeur</a>. Ne la divulguez jamais à personne, 
car l'info routeur contient votre adresse IP.</li>
<li><b>Version:</b>
La version d'I2P qui vous affiche actuellement cette page.</li>
<li><b>Lancé depuis:</b>
Indique depuis combien de temps le routeur tourne.</li>
<li><b>Réseau:</b>
Statut de joignabilité du routeur par les autres routeurs.
Plus d'infos sur la page de <a href="confignet#help">configuration</a>.
</li></ul><h3>Pairs</h3><ul>
<li><b>Actifs:</b>
le premier nombre est celui des routeurs avec qui le votre a communiqué dans les dernières minutes. Ça peut varier de 
8-10 à plusieurs centaines, selon votre bande passante et son rapport de partage, et le trafic généré localement. Le 
second est celui des pairs vus dans les dernières heures. Ces nombres penvent varier sensiblement sans conséquence.
<a href="configstats.jsp#router.activePeers">[Activer le graphique]</a></li>
<li><b>Rapides:</b>
le nombre de pairs que vous mettez à contribution pour construire vos tunnels clients. En général dans une tranche de 
8 à 30. Vos pairs rapides sont détaillés sur la page <a href="profiles.jsp">profils</a>.
<a href="configstats.jsp#router.fastPeers">[Activer le graphique]</a>.</li>
<li><b>Hautes capacités:</b>
nombre des pairs que vous utilisez pour construire quelques uns de vos tunnels exploratoires. Habituellement de 8 à 75. 
Les pairs rapides font partie du groupe des "Hautes capacités". Vos pairs à hautes capacités sont aussi listés sur 
la page <a href="profiles.jsp">profils</a>.
<a href="configstats.jsp#router.highCapacityPeers">[Activer le graphique]</a></li>
<li><b>Bien intégrés:</b>
vous utilisez ce groupe pour vos requêtes à la base de données du réseau. Ils sont souvent des pairs de remplissage par 
diffusion ("floodfill"). Vos pairs "bien intégrés" sont affichés en bas de la même page 
<a href="profiles.jsp">profils</a>.</li>
<li><b>Connus:</b> c'est tous les routeurs dont vous connaissez l'existance. Il sont listés sur la
page <a href="netdb.jsp">base de données du réseau</a>. De moins de 100 à 1000 ou plus. Ce nombre ne représente pas la 
taille totale du réseau; il varie en fonction de votre bande passante totale et son rapport de partage, et du trafic 
local. I2P n'a pas besoin que chaque routeur connaisse tous les autres.
</li></ul><h3>Bande passante entrée/sortie</h3>
<p>Ça parle tout seul. Toutes les valeurs sont en octets par seconde (o/s), pas en bits par seconde (b/s). Modifiez vos 
limites de bande passante sur la page de <a href="confignet#help">configuration</a>.
Le <a href="graphs.jsp">graphique de bande passante</a> est activé par défaut.</p>

<h3>Destinations locales</h3>
<p>C'est le nom I2P des applications qui se connectent par votre routeur. Elles peuvent être des clients lancés depuis 
<a href="i2ptunnel/index.jsp">I2PTunnel</a> ou des programmes tiers qui se connectent via SAM, BOB ou directement à 
I2CP.</p>
<h3>TUNNELS:</h3>
<p>Les tunnels actuels sont affichés sur la page <a href="tunnels.jsp">tunnels</a>.</p>
<ul>
<li><b>Exploratoires:</b> tunnels créés par votre routeur et utilisés avec les
pairs diffuseurs pour la création des nouveaux tunnels et le test des tunnels existants.</li>
<li><b>Clients:</b> tunnels créés par votre routeur pour chaque utilisation cliente.</li>
<li><b>Participants:</b> les tunnels créés par d'autres routeurs et qui passent par le votre. Leur
nombre dépend largement de la demande du réseau, de votre part de bande passante partagée, et du trafic local. 
La méthode recommandée pour limiter leur nombre est de diminuer le rapport de bande passante partagée dans la 
<a href="confignet#help">configuration</a>. Vous pouvez également limiter ce nombre en définissant la variable 
<tt>router.maxParticipatingTunnels=nnn</tt> dans la <a href="configadvanced.jsp">configuration avancée</a>. 
<a href="configstats.jsp#tunnel.participatingTunnels">[Activer le graphique]</a>.</li>
<li><b>Rapport de partage:</b> le nombre de tunnels participants que vous routez pour les autres,
divisé par le nombre total de sauts dans tous vos tunnels exploratoires et clients. S'il est supérieur à 1, cela 
signifie que vous contribuez à plus de tunnels que vous n'en utilisez.
</li>
</ul>

<h3>ENCOMBREMENT</h3>
<p>Indications de base sur la charge du routeur:</p>
<ul>
<li><b>Retard de tâches:</b> temps d'attente des tâches avant exécution. La file d'attente est
présentée la pages des <a href="jobs.jsp">tâches</a>. Malheureusement, il y a dans le routeur plusieurs autres files 
d'attentes qui ne peuvent être affichées dans la console. Le retard de tâches devrait rester à zéro en permamence. s'il 
régulièrement supérieur à 500ms, soit votre PC est très lent, soit le routeur a de sérieux problèmes.
<a href="configstats.jsp#jobQueue.jobLag">[Activer le graphique]</a>.</li>
<li><b>Retard de messages:</b> temps de rétention des messages en file d'attente d'envois, normalement
quelques centaines de ms ou moins. Au dessus d'une seconde, votre PC est très lent, vous devriez fignoler vos réglages 
de bande passante, ou vos clients (bittorrent, iMule...?) envoient trop de données et il faudrait voir à leur tenir 
la bride. <a href="configstats.jsp#transport.sendProcessingTime">[Activer le graphique]</a> 
(transport.sendProcessingTime).</li>
<li><b>Retard de tunnels:</b> le temps d'aller-retour pour un test de tunnel (envoi d'un seul message
par un tunnel client et dans un tunnel exploratoire ou vice versa. Normalement inférieur à 5s. Si c'est constamment 
supérieur, votre PC est très lent, vous devriez retoucher vos limites de bande passante, ou il y a un problème réseau.
<a href="configstats.jsp#tunnel.testSuccessTime">[Activer le graphique]</a> (tunnel.testSuccessTime).</li>
<li><b>En attente:</b> nombre de requêtes de création de tunnels participants en attente provenant
d'autres routeurs. Normalement proche de zéro. Sinon, votre ordinateur est trop lent et vous devriez diminuer votre 
limite de bande passante partagée.</li>
<li><b>Accepte/Refuse:</b> c'est le statut de votre routeur au regard de son comportement vis à vis
des demandes de création de tunnels participants provenant d'autres routeurs. Votre routeur peut accepter ou refuser 
tout ou partie des requêtes, ou les refuser en totalité pour des raisons prévues telles que le contrôle de la bande 
passante et des ressources CPU en vue de préserver les performances des clients locaux.</li></ul>
</div>

<div id="configurationhelp"><%@include file="help-configuration.jsi" %></div>
<div id="reachabilityhelp"><%@include file="help-reachability.jsi" %></div>
<div id="advancedsettings"><%@include file="help-advancedsettings.jsi" %></div> <% /* untranslated */ %>
<div id="faq"><%@include file="help-faq.jsi" %></div> <% /* untranslated */ %>

<div id="legal">
<h2>Informations légales</h2><p>Le routeur I2P (router.jar) et le SDK (i2p.jar) sont presque entièrement dans le 
domaine public, à quelques notobles exceptions près:</p><ul>
<li>Le code ElGamal et DSA, sous licence BSD, écrits par TheCrypto</li>
<li>SHA256 et HMAC-SHA256, sous licence MIT, écrits par the Legion of the Bouncycastle</li>
<li>Le code AES, sous licence Cryptix (MIT), écrit pas l'équipe the Cryptix</li>
<li>Le code SNTP, sous licence BSD, écrit par Adam Buckley</li>
<li>Le reste, directement issu du domaine public, est écrit par jrandom, mihi, hypercubus, oOo,
    ugha, duck, shendaras, et d'autres.</li>
</ul>

<p>Au-dessus du routeur I2P on a une série d'applications clientes, ayant chacune ses particularités en termes de 
licences et de dépendances. Cette page est affichée en tant qu'élément de l'application cliente console du routeur I2P, 
qui est une version allégée d'une instance <a href="http://jetty.mortbay.com/jetty/index.html">Jetty</a> (allégée en 
ce que nous n'avons pas inclus les applications de démo et autres compléments, et que nous avons simplifié la 
configuration), vous permettant de déployer dans votre routeur des applications web JSP/Servlet standards. De son côté 
Jetty fait usage de l'implémentation javax.servlet d'Apache. Ce dispositif inclus du logiciel développé par la 
fondation Apache Software (http://www.apache.org/).</p>

<p>Une autre application visible sur cette page: <a href="http://i2p-projekt.i2p/fr/docs/api/i2ptunnel">I2PTunnel</a>
(votre <a href="i2ptunnel/" target="_blank">interface web</a>) sous licence GPL écrite par mihi qui vous permet de 
mettre en tunnels le trafic normal TCP/IP sur I2P (comme les proxy eep et le proxy irc). Il y a aussi un client webmail 
<a href="/susimail/">disponible</a> dans la console, qui est sous 
licence GPL et écrit par susi23. L'application carnet d'adresses, écrite par 
<a href="http://ragnarok.i2p/">Ragnarok</a> gère votre fichier hosts.txt (voir ./addressbook/ pour plus de détails).</p>

<p>Le routeur inclu aussi par défaut le pont <a href="http://i2p-projekt.i2p/fr/docs/api/sam">SAM</a> du domaine public de l'humanité, 
que les autres applications clientes (comme le <a href="http://duck.i2p/i2p-bt/">portage bittorrent</a>) peuvent à leur 
tour utiliser. Il y a aussi une bibliothèque optimisée pour les calculs sur les grand nombres - jbigi - qui de son 
côté utilise la bibliothèque sous licence LGPL <a href="http://swox.com/gmp/">GMP</a>, adaptée à diverses architectures
PC. Les lanceurs pour Windows sont faits avec <a href="http://launch4j.sourceforge.net/">Launch4J</a>, et l'installeur 
avec <a href="http://www.izforge.com/izpack/">IzPack</a>. Les détails sur les autres applications disponibles comme sur 
leurs licences respectives, référez-vous à notre <a href="http://i2p-projekt.i2p/fr/get-involved/develop/licenses">politique de licences</a>. 
Les sources du code I2P et de la plupart des applications jointes est sur notre page de 
<a href="http://i2p-projekt.i2p/fr/download">téléchargements</a>.
.</p>
</div>

<div id="changelog">
<h2>Historique des évolutions</h2>
 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <% java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "history.txt"); %>
 <jsp:setProperty name="contenthelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="512" />
 <jsp:setProperty name="contenthelper" property="startAtBeginning" value="true" />
 <jsp:getProperty name="contenthelper" property="textContent" />

 <p id="fullhistory"><a href="/history.txt" target="_blank">Historique complet</a>
 </p>
</div>
</div></body></html>
