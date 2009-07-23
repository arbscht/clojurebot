#!/bin/sh
java -cp $CLASSPATH:$PWD/:./classes/ clojure.main -e "(compile 'hiredman.clojurebot)"
