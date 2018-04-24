# Monotone cheatsheet

**Slogan:** *Saving you hours reading old manuals*

For most of us developers using git, one of the columns will have an example of 
how certain things are done in git, to easier know what to do in monotone.

## Commands

Command | Git cmd (which give same info) | Description
 ------ |  ----------------------------- | ------------
`mtn ls unknown` | `git status` | List untracked files.
`mtn status` | `git status` | List untracked files. (Please note that `mtn status` do NOT list unknown files.)
`mtn mv [src] [dest]` | `git mv [src] [dest]` | Move a traced directory or file.
`mtn add -R [files..]` | `git add [files..]` | Adds a file to the workspace. Please use -R when it's directories.
`mtn ci [-k devkey] [files..]` | `git commit -s [files...]` | Sign and commit a patch.
`mtn -d [mtndb] pull [-k devkey] [server]` | `git pull [servername] [branchname]` | Pulls new patches from a remote server.
`mtn -d [mtndb] push [-k devkey] [server]` | `git push [servername] [branchname]` | Pushes your patches to a remote server.
`mtn update -r t:TAGNAME` | `git checkout TAGNAME` | Check out an tag in current working directory.
`mtn list tag` | `git tag -l` | List tags in the repo.
`mtn di -r t:TAGNAME` | `git diff TAGNAME` | Show you the diff between the choosen tag and current head.

TBA... 

Contributions are welcome!





