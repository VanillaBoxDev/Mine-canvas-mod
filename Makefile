.DEFAULT_GOAL := help

GRADLE ?= ./gradlew
GRADLE_FLAGS ?=

.PHONY: help build rebuild compile test clean jars fabric-26.2 fabric-26.3 fabric-1.21.11

help:
	@printf '%s\n' \
		'make build                 Build and test everything' \
		'make rebuild               Clean, build, and test everything' \
		'make compile               Compile all Java modules' \
		'make test                  Run all tests' \
		'make clean                 Remove build outputs' \
		'make jars                  Build distributable jars in build/libs' \
		'make fabric-26.2           Build Fabric mod for Minecraft 26.2' \
		'make fabric-26.3           Build Fabric mod for Minecraft 26.3' \
		'make fabric-1.21.11        Build Fabric mod for Minecraft 1.21.11' \
		'' \
		'Extra Gradle arguments: make build GRADLE_FLAGS="--no-daemon --info"'

build:
	$(GRADLE) build $(GRADLE_FLAGS)

rebuild:
	$(GRADLE) clean build $(GRADLE_FLAGS)

compile:
	$(GRADLE) :mine-canvas-fabric-mc26.2:compileJava :mine-canvas-fabric-mc26.3:compileJava :mine-canvas-fabric-1.21.11:compileJava $(GRADLE_FLAGS)

test:
	$(GRADLE) test $(GRADLE_FLAGS)

clean:
	$(GRADLE) clean $(GRADLE_FLAGS)

jars:
	$(GRADLE) collectJars $(GRADLE_FLAGS)

fabric-26.2:
	$(GRADLE) :mine-canvas-fabric-mc26.2:build $(GRADLE_FLAGS)

fabric-26.3:
	$(GRADLE) :mine-canvas-fabric-mc26.3:build $(GRADLE_FLAGS)

fabric-1.21.11:
	$(GRADLE) :mine-canvas-fabric-1.21.11:build $(GRADLE_FLAGS)
