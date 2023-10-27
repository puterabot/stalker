#!/bin/bash

conf_file="./config/dburl.conf"

if [ -f "$conf_file" ]; then
    export MONGO_URL=$(<"$conf_file")
    echo "MONGO_URL variable value loaded from ./config/dburl.conf"
else
    echo "Error: The file $conf_file was not found. Create this file as indicated on README.md before continuing."
    exit 1
fi

meteor --port 3001 run
