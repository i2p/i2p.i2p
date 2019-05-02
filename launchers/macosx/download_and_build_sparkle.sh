#!/usr/bin/env bash
git clone --recursive https://github.com/sparkle-project/Sparkle.git
cd Sparkle
git checkout 1.21.3
xcodebuild -project Sparkle.xcodeproj -configuration Release -target All ARCHS=x86_64 ONLY_ACTIVE_ARCH=YES CONFIGURATION_BUILD_DIR=build clean
xcodebuild -project Sparkle.xcodeproj -configuration Release -target All ARCHS=x86_64 ONLY_ACTIVE_ARCH=YES CONFIGURATION_BUILD_DIR=build


