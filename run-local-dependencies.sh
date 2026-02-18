#!/bin/bash

docker run -d --name emqx -p 1883:1883 -p 8883:8883 -p 18083:18083 emqx/emqx:5.3.2

docker run -d --name opcua-server -p 4840:4840 ghcr.io/umati/sample-server:main

docker run -d --name nginx -p 9876:80 --rm \
  -v "$PWD"/deployment/assets/issuer/nginx.conf:/etc/nginx/nginx.conf:ro \
  -v "$PWD"/deployment/assets/issuer/did.docker.json:/var/www/.well-known/did.json:ro \
  nginx