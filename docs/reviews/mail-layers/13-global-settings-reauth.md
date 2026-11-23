# Global mail configuration authorization

Require mailbox unlock before showing global settings and recheck the encryption password plus enabled TOTP/recovery factor before saving connection or signature-trust changes. Page sizes are bounded to 5–200.

The normal installed WAR verifies anonymous rejection, hidden pre-login settings, wrong-password rejection, valid saving and enabled-TOTP enforcement. All 398 combined checks pass; 33 cover installed workflows.

```sh
python3 tests/scripts/check_mail_security.py
```

See [validation](../mail-validation.json) and [security boundaries](../mail-security.md). Live I2P/provider and real-browser validation remain outstanding.
