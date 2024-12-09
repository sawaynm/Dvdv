#!/bin/bash

# Update and install dependencies
sudo apt-get update
sudo apt-get install -y \
  git \
  openjdk-8-jdk \
  gradle \
  wget \
  zip \
  unzip

# Set JAVA_HOME for Gradle
echo "export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64" >> ~/.bashrc
source ~/.bashrc

# Clone the repository
if [ ! -d "kali-nethunter-app" ]; then
  git clone https://gitlab.com/kalilinux/nethunter/apps/kali-nethunter-app.git
fi

# Navigate to the repository
cd kali-nethunter-app || exit

# Build the app
./gradlew build

# Copy the APK to the output directory
mkdir -p /workspace/output
cp app/build/outputs/apk/debug/app-debug.apk /workspace/output/
