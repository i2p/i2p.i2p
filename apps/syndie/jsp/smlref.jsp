<%@page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" import="java.util.*" %><% 
request.setCharacterEncoding("UTF-8"); 
%><!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 TRANSITIONAL//EN" "http://www.w3c.org/TR/1999/REC-html401-19991224/loose.dtd">
<html>
<head>
<title>SML Quick Reference</title>
<link href="style.jsp" rel="stylesheet" type="text/css" >
</head>
<body>
<h3>SML Quick Reference:</h3>
<ul>
  <li>newlines are newlines are newlines.</li>
  <li>all &lt; and &gt; are replaced with their &amp;symbol;</li>
  <li>[b][/b] = &lt;b&gt;bold&lt;/b&gt;</li>
  <li>[i][/i] = &lt;i&gt;italics&lt;/i&gt;</li>
  <li>[u][/u] = &lt;i&gt;underline&lt;/i&gt;</li>
  <li>[cut]more inside[/cut] = [&lt;a href="#"&gt;more inside...&lt;/a&gt;]</li>
  <li>[quote][/quote] = Quoted text</li>
  <li>[img attachment="1"]alt[/img] = use attachment 1 as an image with 'alt' as the alt text</li>
  <li>[blog name="name" bloghash="base64hash"]description[/blog] = link to all posts in the blog</li>
  <li>[blog name="name" bloghash="base64hash" blogentry="1234"]description[/blog] = link to the specified post in the blog</li>
  <li>[blog name="name" bloghash="base64hash" blogtag="tag"]description[/blog] = link to all posts in the blog with the specified tag</li>
  <li>[blog name="name" blogtag="tag"]description[/blog] = link to all posts in all blogs with the specified tag</li>
  <li>[link schema="eep" location="http://forum.i2p"]text[/link] = offer a link to an external resource (accessible with the given schema)</li>
  <li>[archive name="name" description="they have good stuff" schema="eep" location="http://syndiemedia.i2p/archive/archive.txt"]foo![/archive] = offer an easy way to sync up with a new Syndie archive</li>
</ul>
SML headers are newline delimited key:value pairs.  Example keys are:
<ul>
  <li>bgcolor = background color of the post (e.g. bgcolor:#ffccaa or bgcolor=red)</li>
  <li>bgimage = attachment number to place as the background image for the post (only shown if images are enabled) (e.g. bgimage=1)</li>
  <li>textfont = font to put most text into</li>
</ul>
</body>
