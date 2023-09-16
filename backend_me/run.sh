#!/bin/bash
mvn -q exec:java -Dexec.mainClass="sithmaster.MileroticosBotSeleniumApp" -Dwebdriver.chrome.driver="/usr/local/bin/chromedriver" -Dexec.args=$@ &> a
