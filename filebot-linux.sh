#!/bin/bash

# FileBot Linux Launcher Script
# Optimized version for Linux systems

# Application information
APP_NAME="FileBot"
APP_VERSION="4.9.0"
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_FILE="$APP_DIR/filebot-4.9.0-fat.jar"

# Java options for optimal performance
JAVA_OPTS=(
    # Performance optimizations
    "-XX:+UseG1GC"
    "-XX:MaxGCPauseMillis=200"
    "-XX:+UseStringDeduplication"
    "-XX:+OptimizeStringConcat"
    "-XX:+UseCompressedOops"
    "-XX:+UseCompressedClassPointers"
    
    # Memory settings
    "-Xms256m"
    "-Xmx2g"
    "-XX:MetaspaceSize=128m"
    "-XX:MaxMetaspaceSize=512m"
    
    # FileBot specific options
    "-Dunixfs=true"
    "-DuseExtendedFileAttributes=true"
    "-DuseCreationDate=false"
    "-Djava.net.useSystemProxies=true"
    "-Djna.nosys=true"
    "-Djna.nounpack=true"
    
    # Modern Java options
    "--enable-preview"
    "--add-opens=java.base/java.lang=ALL-UNNAMED"
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED"
    "--add-opens=java.base/java.util=ALL-UNNAMED"
    "--add-opens=java.base/java.util.function=ALL-UNNAMED"
    "--add-opens=java.base/java.util.regex=ALL-UNNAMED"
    
    # UI options
    "-Dprism.order=sw"
    "-Dprism.text=t2k"
    "-Dquantum.multithreaded=false"
)

# Check if Java is available
check_java() {
    if ! command -v java &> /dev/null; then
        echo "Error: Java is not installed or not in PATH"
        echo "Please install OpenJDK 17 or later:"
        echo "  Ubuntu/Debian: sudo apt install openjdk-17-jdk"
        echo "  CentOS/RHEL: sudo yum install java-17-openjdk-devel"
        echo "  Arch: sudo pacman -S jdk-openjdk"
        exit 1
    fi
    
    # Check Java version
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 17 ]; then
        echo "Error: Java 17 or later is required (found version $JAVA_VERSION)"
        echo "Please upgrade your Java installation"
        exit 1
    fi
    
    echo "Using Java version: $(java -version 2>&1 | head -n 1)"
}

# Check if JAR file exists
check_jar() {
    if [ ! -f "$JAR_FILE" ]; then
        echo "Error: JAR file not found: $JAR_FILE"
        echo "Please ensure the FileBot JAR file is in the same directory as this script"
        exit 1
    fi
}

# Set up environment
setup_environment() {
    # Set application data directory
    export FILEDBOT_APP_DATA="$HOME/.filebot"
    mkdir -p "$FILEDBOT_APP_DATA"
    
    # Set cache directory
    export FILEDBOT_CACHE="$HOME/.cache/filebot"
    mkdir -p "$FILEDBOT_CACHE"
    
    # Set temporary directory
    export FILEDBOT_TEMP="/tmp/filebot-$$"
    mkdir -p "$FILEDBOT_TEMP"
    
    # Clean up temp directory on exit
    trap 'rm -rf "$FILEDBOT_TEMP"' EXIT
}

# Launch application
launch_app() {
    echo "Starting $APP_NAME $APP_VERSION..."
    echo "Java options: ${JAVA_OPTS[*]}"
    echo "App data: $FILEDBOT_APP_DATA"
    echo "Cache: $FILEDBOT_CACHE"
    echo ""
    
    # Launch with optimized options
    exec java "${JAVA_OPTS[@]}" -jar "$JAR_FILE" "$@"
}

# Main execution
main() {
    echo "=== $APP_NAME $APP_VERSION Linux Launcher ==="
    echo "Optimized for performance and Linux compatibility"
    echo ""
    
    check_java
    check_jar
    setup_environment
    launch_app "$@"
}

# Run main function with all arguments
main "$@"