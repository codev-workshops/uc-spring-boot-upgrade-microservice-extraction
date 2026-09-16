# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

- **MAJOR** — incompatible API or schema changes
- **MINOR** — backwards-compatible functionality
- **PATCH** — backwards-compatible bug fixes

Add entries under `[Unreleased]` as you work. When cutting a release
(`./gradlew release`), rename `[Unreleased]` to the new version and date, and
add a fresh empty `[Unreleased]` section above it. The release workflow uses the
section matching the pushed tag as the GitHub Release body.

## [Unreleased]

### Added
- Gradle release plugin (`net.researchgate.release`) for semantic versioning
- GitHub Actions release workflow: builds the Spring Boot JAR, creates a GitHub
  Release and attaches the artifact when a `v*` tag is pushed
- This changelog

### Changed

### Deprecated

### Removed

### Fixed

### Security

<!--
## [X.Y.Z] - YYYY-MM-DD

### Added
- New features

### Changed
- Changes in existing functionality

### Deprecated
- Soon-to-be removed features

### Removed
- Removed features

### Fixed
- Bug fixes

### Security
- Vulnerability fixes
-->

[Unreleased]: https://github.com/codev-workshops/uc-spring-boot-upgrade-microservice-extraction/compare/main...HEAD
