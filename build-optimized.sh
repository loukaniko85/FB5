#!/bin/bash

# FileBot Optimized Build Script
# Creates a portable Linux package with all optimizations

set -e

# Configuration
VERSION="4.9.0"
PACKAGE_NAME="filebot-${VERSION}-linux-optimized"
BUILD_DIR="build"
DIST_DIR="dist"
JAR_NAME="filebot-${VERSION}-fat.jar"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."
    
    # Check Java
    if ! command -v java &> /dev/null; then
        log_error "Java is not installed"
        exit 1
    fi
    
    # Check Java version
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 17 ]; then
        log_error "Java 17 or later is required (found version $JAVA_VERSION)"
        exit 1
    fi
    
    # Check Gradle
    if ! command -v gradle &> /dev/null; then
        log_warning "Gradle not found, attempting to use wrapper"
        if [ ! -f "./gradlew" ]; then
            log_error "Neither Gradle nor gradlew found"
            exit 1
        fi
        GRADLE_CMD="./gradlew"
    else
        GRADLE_CMD="gradle"
    fi
    
    # Check required tools
    for tool in tar xz; do
        if ! command -v $tool &> /dev/null; then
            log_error "$tool is not installed"
            exit 1
        fi
    done
    
    log_success "Prerequisites check passed"
}

# Clean build directories
clean_build() {
    log_info "Cleaning build directories..."
    rm -rf "$BUILD_DIR" "$DIST_DIR"
    mkdir -p "$BUILD_DIR" "$DIST_DIR"
}

# Build the project
build_project() {
    log_info "Building FileBot optimized version..."
    
    if [ "$GRADLE_CMD" = "./gradlew" ]; then
        chmod +x ./gradlew
    fi
    
    # Build with Gradle
    $GRADLE_CMD clean build shadowJar
    
    # Copy built JAR
    if [ -f "build/libs/$JAR_NAME" ]; then
        cp "build/libs/$JAR_NAME" "$BUILD_DIR/"
        log_success "JAR file built successfully"
    else
        log_error "JAR file not found after build"
        exit 1
    fi
}

# Create package structure
create_package() {
    log_info "Creating package structure..."
    
    PACKAGE_DIR="$BUILD_DIR/$PACKAGE_NAME"
    mkdir -p "$PACKAGE_DIR"
    
    # Copy JAR file
    cp "$BUILD_DIR/$JAR_NAME" "$PACKAGE_DIR/"
    
    # Copy launcher script
    cp "filebot-linux.sh" "$PACKAGE_DIR/"
    chmod +x "$PACKAGE_DIR/filebot-linux.sh"
    
    # Copy desktop file
    cp "filebot.desktop" "$PACKAGE_DIR/"
    
    # Copy documentation
    cp "README-OPTIMIZED.md" "$PACKAGE_DIR/README.md"
    
    # Create directories
    mkdir -p "$PACKAGE_DIR/themes"
    mkdir -p "$PACKAGE_DIR/icons"
    mkdir -p "$PACKAGE_DIR/config"
    
    # Copy CSS theme
    if [ -f "source/net/filebot/ui/css/modern-theme.css" ]; then
        cp "source/net/filebot/ui/css/modern-theme.css" "$PACKAGE_DIR/themes/"
    fi
    
    # Create configuration files
    cat > "$PACKAGE_DIR/config/application.properties" << EOF
# FileBot Optimized Configuration
application.name=FileBot
application.version=${VERSION}
application.optimized=true

# Performance settings
cache.provider=caffeine
cache.maxSize=10000
cache.expireAfterWrite=1d

# UI settings
ui.framework=javafx
ui.theme=modern
ui.accelerated=true

# Java options
java.gc=G1GC
java.memory.initial=256m
java.memory.max=2g
EOF

    # Create installation script
    cat > "$PACKAGE_DIR/install.sh" << 'EOF'
#!/bin/bash

# FileBot Optimized Installation Script

set -e

INSTALL_DIR="/opt/filebot"
BIN_DIR="/usr/local/bin"
DESKTOP_DIR="/usr/share/applications"
ICON_DIR="/usr/share/icons/hicolor/256x256/apps"

echo "Installing FileBot Optimized..."

# Create installation directory
sudo mkdir -p "$INSTALL_DIR"
sudo cp -r * "$INSTALL_DIR/"

# Create symlink
sudo ln -sf "$INSTALL_DIR/filebot-linux.sh" "$BIN_DIR/filebot"

# Install desktop file
sudo cp filebot.desktop "$DESKTOP_DIR/"

# Make executable
sudo chmod +x "$INSTALL_DIR/filebot-linux.sh"
sudo chmod +x "$BIN_DIR/filebot"

# Create application directories
mkdir -p "$HOME/.filebot"
mkdir -p "$HOME/.cache/filebot"

echo "Installation complete!"
echo "Run 'filebot' to start the application"
EOF

    chmod +x "$PACKAGE_DIR/install.sh"
    
    # Create uninstall script
    cat > "$PACKAGE_DIR/uninstall.sh" << 'EOF'
#!/bin/bash

# FileBot Optimized Uninstallation Script

set -e

INSTALL_DIR="/opt/filebot"
BIN_DIR="/usr/local/bin"
DESKTOP_DIR="/usr/share/applications"

echo "Uninstalling FileBot Optimized..."

# Remove symlink
sudo rm -f "$BIN_DIR/filebot"

# Remove desktop file
sudo rm -f "$DESKTOP_DIR/filebot.desktop"

# Remove installation directory
sudo rm -rf "$INSTALL_DIR"

echo "Uninstallation complete!"
EOF

    chmod +x "$PACKAGE_DIR/uninstall.sh"
    
    log_success "Package structure created"
}

# Create distribution archives
create_archives() {
    log_info "Creating distribution archives..."
    
    cd "$BUILD_DIR"
    
    # Create tar.xz archive
    tar -cf "${PACKAGE_NAME}.tar" "$PACKAGE_NAME"
    xz -9 "${PACKAGE_NAME}.tar"
    
    # Create zip archive
    zip -r "${PACKAGE_NAME}.zip" "$PACKAGE_NAME"
    
    # Move to dist directory
    mv "${PACKAGE_NAME}.tar.xz" "../$DIST_DIR/"
    mv "${PACKAGE_NAME}.zip" "../$DIST_DIR/"
    
    cd ..
    
    log_success "Distribution archives created"
}

# Generate checksums
generate_checksums() {
    log_info "Generating checksums..."
    
    cd "$DIST_DIR"
    
    for archive in *.tar.xz *.zip; do
        if [ -f "$archive" ]; then
            sha256sum "$archive" > "${archive}.sha256"
            log_info "Generated checksum for $archive"
        fi
    done
    
    cd ..
    
    log_success "Checksums generated"
}

# Run performance tests
run_performance_tests() {
    log_info "Running performance tests..."
    
    if [ -f "build/libs/$JAR_NAME" ]; then
        java -jar "build/libs/$JAR_NAME" --performance-test || {
            log_warning "Performance tests failed, continuing with build"
        }
    fi
}

# Main build process
main() {
    log_info "Starting FileBot Optimized build process..."
    log_info "Version: $VERSION"
    log_info "Package: $PACKAGE_NAME"
    
    check_prerequisites
    clean_build
    build_project
    run_performance_tests
    create_package
    create_archives
    generate_checksums
    
    log_success "Build completed successfully!"
    log_info "Distribution files created in: $DIST_DIR/"
    
    # List created files
    echo
    log_info "Created files:"
    ls -la "$DIST_DIR/"
    
    echo
    log_info "Installation instructions:"
    echo "1. Extract the archive: tar -xf $DIST_DIR/${PACKAGE_NAME}.tar.xz"
    echo "2. Run installer: cd $PACKAGE_NAME && ./install.sh"
    echo "3. Launch: filebot"
}

# Run main function
main "$@"