Here are some config files for packaging in a future mini-release.

They disable the following:
  - Local eepsite
  - I2PSnark
  - SAM
  - SusiDNS
  - SusiMail
  - Don't include the history file

And reduce the following:
  - JVM heap size (64MB)
  - Logging file size (128KB, 2 files)
  - Exploratory tunnel length (2+0)
  - Full stats disabled
  - No graphs enabled by default
