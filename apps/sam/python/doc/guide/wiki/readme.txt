
The documentation was created by using MediaWiki software.

This directory houses the wiki text sources.

Feel free to move to any other documentation system, if
it is efficient and easy to maintain.

Ideally, one could patch pydoc to export only certain
names, in a certain order, like so:

__pydoc__ = ['f', 'g']    # f() and g() documented in order

This could proceed recursively for all namespaces.

Combine this with a second patch to make pydoc create
nice CSS, and this whole guide could be generated
directly from the sources.
