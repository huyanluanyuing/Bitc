#!/bin/sh

taskset -c 0,1 java -cp .:gen-java/:"lib/*" BEServer localhost 10451 10452
