# SusiMail: validate sender identity and verify pinned whole-message DKIM

This commit is one layer of the SusiMail security series. Apply its documented parent before testing.

```sh
python3 tests/scripts/check_mail_layer.py --suite all identity frame
```

Validated with Java 17 compilation on OpenJDK 25 using normal locally built I2P/Jetty jars. Results: all=111, frame=6, identity=105; no failures. Fixtures use synthetic accounts/messages and no external sockets.

Live I2P/provider delivery, hosted CI and real-browser visual enforcement have not been verified in this environment.
