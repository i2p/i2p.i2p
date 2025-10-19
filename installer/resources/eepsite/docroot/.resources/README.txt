Put your custom error pages here.
The following files are recognized:

- 4nn.html, 5nn.html (for example, 404.html)
  Served for that specific error code

- "4xx.html"
  Served for any error code 400-499 not matched above

- "5xx.html"
  Served for any error code 500-599 not matched above

- "000.html"
  Served for any error code not matched above

These error pages are only for the base context.
The CGI context has its own error handler.
