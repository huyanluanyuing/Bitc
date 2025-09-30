#!/bin/sh

taskset -c 2,3 java -cp .:gen-java/:"lib/*" BEServer localhost 10451 10453
