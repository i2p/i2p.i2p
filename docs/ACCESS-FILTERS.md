# Server access filters

Set the server tunnel's **Access Filter** option to a rule-file path. The rules
are read when the tunnel starts. Restart the tunnel after changing its rules.
Destination lists referenced by `file` rules reload automatically every ten
seconds; recorder files update at the same interval.

Each rule starts with `allow`, `deny`, or `connections/seconds`, followed by a
rule keyword and, where required, a destination or filename:

```
# Block the fifteenth attempt from a destination within five seconds.
15/5 default
# Let friends connect without that limit.
allow file /path/to/friends.txt
# Reject destinations in this list.
deny file /path/to/blocked.txt
# Record unknown destinations reaching sixty attempts within five seconds.
60/5 record /path/to/abusive.txt
```

The attempt reaching a numeric threshold is rejected: `1/1` rejects the first
attempt. Rejected attempts count too. An omitted default rule means `allow`.
Only one default rule is permitted. The first matching explicit or file rule
takes precedence over later matches, and these rules override the default.
Recorders examine destinations that are not already covered by those rules.

Use `deny explicit <destination>.b32.i2p` for a single destination. A destination
list contains one full 52-character Base32 address plus `.b32.i2p` per line.
For a list shared with a recorder, use its path in both a `record` rule and a
`file` rule.

Keywords accept either case. Separate columns with one or more spaces or tabs.
The remainder of a `file` or `record` line is the filename, so paths may contain
spaces; do not add quotation marks. Blank lines and lines starting with `#`
are ignored. Parse errors report physical line numbers, including comments and
blank lines, together with the reason the rule was rejected.
