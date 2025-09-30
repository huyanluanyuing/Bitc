#!/bin/sh

taskset -c 4,5 java -cp .:gen-java/:"lib/*" FEServer 10451