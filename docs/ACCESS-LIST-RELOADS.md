# Access-list reload behavior

Server access filters reload destination-list files every ten seconds. A reload
builds a complete replacement policy before publishing it. If a previously
loaded list disappears or a read fails, the active policy remains in place.
The cached list and its modification time update only after a successful read
and close. File modification times are compared with the last loaded file
version, so replacements with older timestamps are detected too.

A recorder's list may be absent at startup; it takes effect after the recorder
creates it. Once a list has loaded, write an empty file to clear it. Deleting a
loaded list preserves the active restrictions until a successful reload. Use an
atomic file replacement when editing lists to avoid exposing a partially
written file to readers.

The first matching rule takes precedence. Each file caches all of its entries,
including those currently shadowed by an earlier rule. If an earlier match is
removed, the next matching rule takes effect. A change to the effective
threshold replaces the destination's tracker; an unchanged threshold retains
its connection history so ordinary refreshes do not reset a rate limit.
