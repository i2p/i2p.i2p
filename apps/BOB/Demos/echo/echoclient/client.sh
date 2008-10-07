#!/bin/bash
(
cd dist
java -jar echoclient.jar main 37338 testclient $1
)
