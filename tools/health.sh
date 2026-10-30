#!/bin/bash
exec 3<>/dev/tcp/127.0.0.1/${PORT:-8081} || exit 1
printf 'GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
cat <&3 | grep -q '"status":"UP"'
