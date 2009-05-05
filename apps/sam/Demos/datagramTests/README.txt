# test example

#in a first terminal, launch :
 ./samIn.py inTest

#in a second terminal, launch :
 ./samForward.py 25000 forward

#in a third terminal, launch :
l=0
while [ $l -lt 1000 ]
do 
   l=$((l+1))
   ./samOut.py forward this is message n. $l
done
