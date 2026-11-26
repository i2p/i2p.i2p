# Regex source triage

`pattern_scan.py` contains 32 regex rules for this repository's Java and C
source. Every rule includes guidance for reviewing a match. List the actual
regexes, select a category, or scan all categories:

```sh
python3 tests/audit/pattern_scan.py --list
python3 tests/audit/pattern_scan.py --list --category exceptions
python3 tests/audit/pattern_scan.py --category exceptions --category input --output tests/results/patterns/errors-input.json
python3 tests/audit/pattern_scan.py
```

The default report is `tests/results/patterns/report.json`. Findings include
path, line, column, rule and category, without copying source or matched values.
The report also includes regex definitions, review guidance and counts by rule
and category. `--category` can be repeated; the unfiltered report includes:

| Category | Patterns |
| --- | --- |
| `exceptions` | Empty/comment-only catches (including ignored interrupts), broad catches, sentinel returns, throwing only an exception message, immediate return/throw in finally, direct stack traces |
| `memory` | Dynamic array sizes, heap/direct byte buffers, whole-input reads, native allocations, pointer-overwriting realloc, unbounded string functions, buffer copies |
| `input` | Discarded reads/skips, line reads, numeric parsing, offset-plus-length arithmetic, casting read results before EOF checks, default charsets, variable regex patterns, native lossy number parsing and variable format strings |
| `resources` | Stream/socket/file construction, discarded filesystem results, discarded native I/O results |
| `concurrency` | Executors with potentially growing thread counts or work queues |
| `security` | Process execution, deserialization, empty server trust checks, always-true hostname verifier lambdas, concatenated SQL expressions, XML parser configuration sites |

Start with specific shapes such as `exception-message-without-cause`,
`finally-overrides-exit`, `realloc-overwrites-pointer`,
`read-byte-before-eof-check` and `discarded-read-or-skip`. The allocation,
resource-construction, line-read and parser rules are broader discovery aids:
their matches identify where to inspect bounds, callers and cleanup. For
example, an allocation using a bounded constant and a socket managed by
try-with-resources are expected to match.

For an empty `InterruptedException` catch, inspect the thread's cancellation
contract. Restoring interrupt status, propagating the exception, exiting, or
ignoring an intentional wakeup can each be appropriate depending on the loop.
For error returns, verify that callers distinguish failure from valid values.
For length arithmetic, verify both the input slice and any protocol limit
before allocating, copying or calculating an end position.

## Direct ripgrep searches

These are useful individual regexes for ad hoc searches. They operate on raw
text, so they also match comments and literals. The Python scanner masks those
first and provides the full rule pack. `-U` enables multiline matches; `-P`
selects PCRE2. A ripgrep build without PCRE2 can use `-U` alone for these
particular examples.

```sh
# Empty catches (use the scanner for comment-only bodies).
rg -n -U -P -g '*.java' '\bcatch\s*\([^(){}]*\)\s*\{\s*\}' core/java/src router/java/src apps

# Catching Throwable, Error or Exception.
rg -n -U -P -g '*.java' '\bcatch\s*\(\s*(?:final\s+)?(?:java\.lang\.)?(?:Throwable|Error|Exception)\b' core/java/src router/java/src apps

# Losing a cause by throwing only its message.
rg -n -U -P -g '*.java' '\bthrow\s+new\s+[\w.$]*Exception\s*\(\s*\w+\s*\.\s*getMessage\s*\(\s*\)\s*\)\s*;' core/java/src router/java/src apps

# Allocation sizes to trace back to validated limits.
rg -n -P -g '*.java' '\bnew\s+(?:byte|char|int|long)\s*\[[^\]\n]*[A-Za-z_$][^\]\n]*\]' core/java/src router/java/src apps

# Potentially overflow-prone slice ends.
rg -n -U -P -g '*.java' '\b(?:off(?:set)?|start|pos(?:ition)?)\s*\+\s*(?:len(?:gth)?|size)\b' core/java/src router/java/src apps

# Native allocation and string/buffer operations to review.
rg -n -P -g '*.[ch]' '\b(?:malloc|calloc|realloc|MemAlloc|gets|strcpy|strcat|sprintf|vsprintf|memcpy|memmove)\s*\(' core/c installer/c
```

## Coverage and interpretation

The scanner reads `.java`, `.c` and `.h` under `core/java/src`,
`router/java/src`, `apps`, `installer/java/src`, `installer/tools/java/src`,
`core/c` and `installer/c`. Java files must be below a `src` directory. It
excludes test/demo/benchmark and common generated directories, and skips
symlinks. Vendored source inside these trees is included. It does not scan
JSP, JavaScript, dependency binaries or other directories.

The lexical mask preserves line/column positions and handles ordinary
comments, strings, character literals and simple Java text blocks. It is not
a parser: Unicode escapes, preprocessing, macros, complex text blocks,
qualified/aliased API names and less common syntax can hide or alter matches.
The rules do not resolve types, callers, data flow, prior validation or cleanup.
Some declarations and code in inactive native preprocessor branches can match.
Different rules can flag the same site, so counts are **review matches, not
distinct bugs**. They cannot prove leaks, use-after-free, double-free,
injection or exploitability. Validate a suspected defect with its actual caller
and a focused regression before changing production behavior.

Successful scans return exit status 0 with `triage-required` or
`no-rule-matches`; neither is a security pass. Read/decode failures or zero
scanned files produce `incomplete` and exit status 1. Output/write errors also
fail the command. The GitHub Actions security worker calls this same scanner
and retains its report for review.

```sh
python3 -m unittest discover -s tests/audit -p 'test_*.py'
```
