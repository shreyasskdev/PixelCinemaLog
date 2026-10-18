#!/bin/bash
# A script to extract the CinemaCameraScreen and Raw16CinemaPlayerScreen safely
mkdir -p /home/shreyas/dev/PixelCinemaLog/app/src/main/java/io/github/shreyasskdev/pixelcinemalog/ui
sed -n '97,826p' /home/shreyas/dev/PixelCinemaLog/app/src/main/java/io/github/shreyasskdev/pixelcinemalog/MainActivity.kt > /home/shreyas/dev/PixelCinemaLog/app/src/main/java/io/github/shreyasskdev/pixelcinemalog/ui/CinemaCameraScreen.kt
sed -n '1148,1342p' /home/shreyas/dev/PixelCinemaLog/app/src/main/java/io/github/shreyasskdev/pixelcinemalog/MainActivity.kt > /home/shreyas/dev/PixelCinemaLog/app/src/main/java/io/github/shreyasskdev/pixelcinemalog/ui/Raw16CinemaPlayerScreen.kt
