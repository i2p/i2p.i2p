# SusiMail mail protection review

The mail client now protects local cache contents, checks sender identity and message integrity, restricts dangerous message content, and provides private sender rules and optional authenticator protection. These changes reduce specific attack paths; they are not a claim that the client exceeds every enterprise product or detects every malicious message.

| Protection | Result |
| --- | --- |
| Protocol commands and diagnostics | Reject control bytes, including Unicode values that truncate into wire controls. SMTP AUTH uses unwrapped UTF-8 Base64. Command contents, echoed server text, credentials and session identifiers are omitted from the affected diagnostics. |
| Browser privacy and sessions | Private `no-store` responses, fresh mailbox session IDs, 128-bit form nonces, session-bound POST checks and relative redirects. The mail-specific request filter bounds fields and duplicate parameters without rewriting or logging secret values. |
| Identity and integrity | Establish raw header boundaries before decoding encoded words. Show the actual mailbox address. Reject conflicting identity fields and require confirmation for a different Reply-To. Verify complete-body RSA-SHA256 DKIM against explicitly pinned keys; recheck when keys, cached content or signature validity change. |
| Font smuggling and phishing | Rebuild HTML from semantic tags. Remove author CSS, fonts, hidden formatting, active markup, forms and remote images. Raster CID images remain available with limits. Supported external links open a trusted destination check before the user follows them. |
| Attachments | Sandbox HTML without script, form, popup or top-navigation permission. Validate inline types and sanitize ZIP entry names. Draft attachment paths stay within the account. Uploaded and forwarded cache files are encrypted. |
| Spam and sender rules | Encrypted exact-address/domain blocklist, bounded local content scoring, visible reasons, restricted replies/HTML/attachments, and explicit per-message release for false positives. Blocking takes precedence over spam release. Original server copies are retained when safety checks fail. |
| Flood controls | Bounded protocol replies, complete POP3 terminators, slow-response deadlines, 20-message fetch batches, shared account attempt/byte budgets, bounded parsing and cache quotas. Limits pause downloads without silently deleting mail. |
| Local encryption | AES-256-GCM for cached headers, messages, drafts, attachments and the account profile. A random data key is wrapped using the email password initially; interrupted legacy migration resumes using a durable wrapped key. Failed writes preserve the previous ciphertext. |
| Optional TOTP | Off by default. Confirm enrollment with an authenticator code. Enforce six-digit, 30-second codes with replay rejection and rate limits, including offline login. Eight random 128-bit recovery codes are one-use; only their hashes are saved inside the encrypted profile. Sensitive security changes require the encryption password and the enabled second factor. |
| Interface | Local styles, light/dark presentation, responsive account cards and inbox controls, visible keyboard focus, readable sender/risk information and a dedicated security page. |

## Configuration and operation

Normal online first login authenticates with the provider before creating the encryption key. Existing encrypted caches require their encryption password before mailbox contents are exposed. First-time offline migration of an existing legacy cache requires password confirmation; a fresh offline account cannot create an unverified cache.

Open **Mail security** to configure optional TOTP, recovery codes, sender rules and spam scoring. If the provider password changes, enter the previous encryption password in the login options, then update the encryption password in the security page. This rewraps the existing data key and locks other open sessions. It does not rotate the message data key or undo earlier disclosure. Lost encryption passwords cannot be recovered using authenticator/recovery codes. Back up the entire account directory, including its wrapped key.

DKIM uses locally pinned Base64 X.509/SPKI RSA public keys, with no DNS lookup:

```properties
susimail.dkim.key.selector._domainkey.example.test=BASE64_SPKI_PUBLIC_KEY
susimail.dkim.require.example.test=true
```

Obtain keys through a trusted channel. `require` is opt-in and enforces a valid signature for received mail from that exact domain. Local Sent/Draft copies do not falsely fail when the relay signs later. Unsupported, unknown or unpinned signatures do not receive verified status. Strict alignment, complete-body signatures, RSA keys of 2048–8192 bits and signed presentation headers deliberately exclude some otherwise interoperable DKIM profiles. Sender-supplied `Authentication-Results` is not trusted. SPF/DMARC enforcement and provider-side rate controls require mail-server support.

## Resource limits

| Resource | Limit |
| --- | --- |
| POP3 message / header buffer | 16 MiB / 256 KiB |
| POP3 listing | 50,000 rows and 8 MiB |
| SMTP reply | 100 lines and 64 KiB |
| Response time | 120-second response deadline with bounded socket reads |
| Incoming fetch work | 20 messages per batch; 100 attempts and 32 MiB actual bytes per account per minute, shared across sessions |
| Saved cache / pending reception | 512 MiB / 32 MiB; 10,000 saved messages |
| Encrypted blob / decompressed mail | 32 MiB / 64 MiB |
| Message headers / MIME structure | 512 fields, 32 KiB physical header lines, depth 32 and 1,024 MIME parts |
| HTML display | 2 MiB, 20,000 elements, depth 64 and 20 CID images |
| Local spam inspection | First 32 KiB decoded text across up to eight text parts |
| Blocklist / security profile | 5,000 entries / 256 KiB |
| Multipart requests | Existing 7,629,902-byte servlet limit; held in memory until application encryption, with bounded concurrent submissions |

Existing oversized data is preserved. A legacy migration can require the user to move an oversized file aside before retrying. Local limits do not stop delivery to the provider's inbox. Spam scoring is a heuristic and can miss attacks or flag legitimate messages; releasing spam does not override failed integrity checks or sender blocks.

## Validation

```sh
python3 tests/scripts/check_mail_security.py
java -cp 'target/local-i2p/tools/ant/lib/*' org.apache.tools.ant.Main \
  -f apps/susimail/build.xml war
```

The reviewed combined source passes **393 checks**: 111 original protocol/browser checks, 105 identity/integrity checks, 130 encryption/MFA/policy/resource checks, 12 request-boundary checks, 28 installed-WAR workflow checks and seven JavaScript scenarios. Reports record source/dependency hashes and compiler/runtime output under `tests/results/mail-security/`.

The normal Ant WAR build passes, including the default translation step. The deployment fixture loads that WAR's actual classes, descriptor, filter, multipart/session configuration and static assets into Jetty. Its HTTP workflows cover first online authentication, cache encryption, TOTP enrollment, recovery-code consumption, offline enforcement, sender blocking, encrypted multipart uploads/draft reopening and sanitized message links. POP3 uses an in-JVM synthetic provider; SMTP tests cover complete sequential/pipelined byte-stream conversations. No real accounts or external mail servers are used.

Java compilation targets release 17 and runs on OpenJDK 25. Browser startup is blocked in this environment (`setsockopt: Operation not permitted`), so real-browser visual checks and live I2P/provider interoperability remain outstanding. The DOM fixture tests trusted-parent resizing/link behavior; it cannot prove browser enforcement. This work validates the rebuilt mail WAR and its normal deployment configuration, not a new full router installer or a live router restart.

## Cryptographic and trust boundaries

PBKDF2-HMAC-SHA256 uses 600,000 iterations, a random 128-bit salt and an AES-256-GCM wrapped key. File encryption uses fresh 96-bit nonces, 128-bit authentication tags and account/path-bound associated data. Full authentication completes before plaintext is exposed. Atomic replacement includes file synchronization and readback; directory synchronization is attempted where supported. Missing or damaged keys/profiles fail closed.

Local encryption protects cached contents at rest. Account/UIDL filenames, sizes, timestamps, process memory, swap, old backups and explicitly exported/downloaded plaintext are outside that guarantee. TOTP protects access through this SusiMail client; it does not enable MFA at the provider, and an attacker with both the encrypted profile and its password can recover its TOTP secret. Authenticated local files do not provide an external rollback counter.

AES-256 is the local-storage design. This change does not implement post-quantum email transport, interoperable end-to-end encryption, S/MIME or OpenPGP. Provider/recipient protocol support would be required for a separate transport feature. NIST discusses symmetric cryptography and quantum security in its [PQC FAQs](https://csrc.nist.gov/projects/post-quantum-cryptography/faqs).

Design references: [DKIM](https://www.rfc-editor.org/rfc/rfc6376.html), [DKIM algorithm requirements](https://www.rfc-editor.org/rfc/rfc8301.html), [Authentication-Results trust](https://www.rfc-editor.org/rfc/rfc8601.html#section-7.1), [TOTP and test vectors](https://www.rfc-editor.org/rfc/rfc6238.html), [OWASP password storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html), and [OWASP MFA guidance](https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html).

Separate fix/feature PRs and their dependencies are recorded in [the publication index](mail-pull-requests.md).
