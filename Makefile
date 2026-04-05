.PHONY: help build test clean run lint package

# Default target
help:
	@echo "Yii2 Model Magic Plugin - Makefile"
	@echo ""
	@echo "Available commands:"
	@echo "  make build      - Build the plugin"
	@echo "  make test       - Run tests"
	@echo "  make clean      - Clean build artifacts"
	@echo "  make run        - Run IDE with plugin"
	@echo "  make package    - Build plugin distribution"
	@echo "  make lint       - Run code checks"
	@echo ""

init: clean lint build test package

# Build the project
build:
	./gradlew build

# Run tests
test:
	./gradlew test

# Clean build artifacts
clean:
	./gradlew clean

# Build plugin distribution (ZIP file)
package:
	./gradlew buildPlugin
	@echo ""
	@echo "Plugin ZIP created in build/distributions/"

# Run code quality checks
lint:
	./gradlew check

# Rebuild from scratch
rebuild: clean build

# Run tests with coverage (if plugin available)
test-coverage:
	./gradlew test jacocoTestReport

# Install plugin to local IDE
install: package
	@echo "Plugin package ready at: build/distributions/*.zip"
	@echo "Install via: Settings → Plugins → ⚙️ → Install from Disk"
