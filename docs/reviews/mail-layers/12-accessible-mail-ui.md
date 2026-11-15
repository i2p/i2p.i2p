# SusiMail: refresh inbox and security UI with accessible responsive styling

This commit is one layer of the SusiMail security series. Apply its documented parent before testing.

```sh
python3 tests/scripts/check_mail_layer.py --suite all identity protection frame request
```

Validated with Java 17 compilation on OpenJDK 25 using normal locally built I2P/Jetty jars. Results: all=111, protection=130, frame=7, identity=105, request=12; no failures. Fixtures use synthetic accounts/messages and no external sockets.

The combined source additionally passes 28 normal-WAR deployment checks. Run `python3 tests/scripts/check_mail_security.py` to rebuild the normal Ant WAR and exercise first login, encrypted uploads/draft reopening, optional MFA/recovery, offline access, sender blocking and message rendering. The complete suite passes 393 checks.

Live I2P/provider delivery, hosted CI and real-browser visual enforcement have not been verified in this environment.
