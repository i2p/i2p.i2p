#!/bin/sh
#
# can't get po4a to do this
#
for i in eepget i2prouter i2prouter-nowrapper
do
	for j in es zh
	do
		po4a-translate -f man -m man/$i.1 -p locale-man/man_$j.po -l man/$i.$j.1 -L UTF-8 -M UTF-8 -k 10 -v -d
	done
done
