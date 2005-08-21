[cut]A brief glance at SML[/cut]
[b]General rules[/b]

Newlines are newlines are newlines.  If you include a newline in your SML, you'll get a newline in the rendered HTML.

All < and > characters are replaced by their HTML entity counterparts.

All SML tags are enclosed with [[ and ]] (e.g. [[b]]bold stuff[[/b]]). ([[ and ]] characters are quoted by [[[[ and ]]]], respectively)

Nesting SML tags is [b]not[/b] currently supported (though will be at a later date).

All SML tags must have a beginning and end tag (even for ones without any 'body', such as [[hr]][[/hr]]).  This restriction may be removed later.

Simple formatting tags behave as expected:  [[b]], [[i]], [[u]], [[h1]] through [[h5]], [[hr]], [[pre]].
[hr][/hr]
[b]Tag details[/b]

* To cut an entry so that the summary is before while the details are afterwards:
[[cut]]more inside...[[/cut]]

* To load an attachment as an image with "syndie's logo" as the alternate text:
[[img attachment="0"]]syndie's logo[[/img]]

* To add a download link to an attachment:
[[attachment id="0"]]anchor text[[/img]]

* To quote someone:
[[quote author="who you are quoting" location="blog://ovpBy2mpO1CQ7deYhQ1cDGAwI6pQzLbWOm1Sdd0W06c=/1234567890"]]stuff they said[[/quote]]

* To sample some code:
[[code location="eep://dev.i2p/cgi-bin/cvsweb.cgi/i2p/index.html"]]<html>[[/code]]

* To link to a [blog name="jrandom" bloghash="ovpBy2mpO1CQ7deYhQ1cDGAwI6pQzLbWOm1Sdd0W06c=" blogentry="1124402137773" archive0="eep://dev.i2p/~jrandom/archive" archive1="irc2p://jrandom@irc.postman.i2p/#i2p"]bitchin' blog[/blog]: 
[[blog name="the blogs name" bloghash="ovpBy2mpO1CQ7deYhQ1cDGAwI6pQzLbWOm1Sdd0W06c=" blogtag="tag" blogentry="123456789" archive0="eep://dev.i2p/~jrandom/archive/" archive1="freenet://SSK@blah/archive//"]]description of the blog[[/blog]].  blogentry and blogtag are optional and there can be any number of archiveN locations specified.

* To link to an [link schema="eep" location="http://dev.i2p/"]external resource[/link]:
[[link schema="eep" location="http://dev.i2p/"]]link to it[[/link]].  
[i]The schema should be a network selection tool, such as "eep" for an eepsite, "tor" for a tor hidden service, "web" for a normal website, "freenet" for a freenet key, etc.  The local user's Syndie configuration should include information necessary for the user to access the content referenced through the given schemas.[/i]

* To pass an [address name="dev.i2p" schema="eep" location="NF2RLVUxVulR3IqK0sGJR0dHQcGXAzwa6rEO4WAWYXOHw-DoZhKnlbf1nzHXwMEJoex5nFTyiNMqxJMWlY54cvU~UenZdkyQQeUSBZXyuSweflUXFqKN-y8xIoK2w9Ylq1k8IcrAFDsITyOzjUKoOPfVq34rKNDo7fYyis4kT5bAHy~2N1EVMs34pi2RFabATIOBk38Qhab57Umpa6yEoE~rbyR~suDRvD7gjBvBiIKFqhFueXsR2uSrPB-yzwAGofTXuklofK3DdKspciclTVzqbDjsk5UXfu2nTrC1agkhLyqlOfjhyqC~t1IXm-Vs2o7911k7KKLGjB4lmH508YJ7G9fLAUyjuB-wwwhejoWqvg7oWvqo4oIok8LG6ECR71C3dzCvIjY2QcrhoaazA9G4zcGMm6NKND-H4XY6tUWhpB~5GefB3YczOqMbHq4wi0O9MzBFrOJEOs3X4hwboKWANf7DT5PZKJZ5KorQPsYRSq0E3wSOsFCSsdVCKUGsAAAA"]addressbook entry[/address]:
[[address name="dev.i2p" schema="eep" location="NF2...AAAA"]]add it[[/address]].
