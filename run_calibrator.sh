#!/bin/sh

if [ $# -eq 0 ]
then
    echo Using default target
    java -cp .:gen-java/:"lib/*" Calibrator 1d7fffff
else
    echo Using user-specified target
    java -cp .:gen-java/:"lib/*" Calibrator $1
fi
