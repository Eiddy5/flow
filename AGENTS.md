# Repository Guidelines

## Project Structure & Module Organization

This is a Micronaut Java application built with Gradle Kotlin DSL. The application entry point is in `src/main/java/org/cses/flow/Application.java`. Runtime configuration lives in `src/main/resources`: `application.yml` contains application settings, `bootstrap.yaml` contains early config-client and Consul settings, and `logback.xml` configures logging. Tests live under `src/test/java`, following the same package layout as production code. Gradle version catalogs are defined in `gradle/libs.versions.toml`.

## Build, Test, and Development Commands

Use the Gradle wrapper for all local commands:

```bash
./gradlew test
```

Runs the JUnit 5 and Micronaut test suite.

```bash
./gradlew run
```

Starts the Micronaut application locally.

```bash
./gradlew build
```

Compiles, tests, and assembles the project artifact.

```bash
./gradlew shadowJar
```

Builds a runnable shaded JAR when needed for deployment.

## Coding Style & Naming Conventions

Use Java 21. Keep packages under `org.cses.flow` unless the project package is intentionally changed. Use 4-space indentation, descriptive class names, and standard Java naming: `PascalCase` for classes, `camelCase` for methods and fields, and uppercase names for constants. Prefer constructor injection for Micronaut beans. Keep configuration keys lowercase and hierarchical in YAML.

## Testing Guidelines

Tests use JUnit 5 with Micronaut Test. Name test classes with the `*Test` suffix and place them under `src/test/java` in the matching package. For Micronaut integration tests, use `@MicronautTest`. Run `./gradlew test` before submitting changes. Add focused tests for new services, configuration behavior, and HTTP endpoints.

## Documentation Workspace

The `docs` directory is the project memory used by both humans and AI agents. Before making non-trivial changes, inspect the relevant files under `docs` and follow the documented project conventions. If a change creates a reusable rule, validation scenario, harness, or architecture decision, update the matching document or add a new Markdown file.

Current documentation structure:

```text
docs/
  agents/       # AI agent roles used in this project and their responsibility boundaries
  verification/ # Scenario-level verification documents, methods, pass rules, and counterexamples
  standards/    # Development standards and project-specific implementation patterns
  harness/      # Reusable test/debug harnesses, local setup notes, scripts, and examples
  decisions/    # ADR records for important technical decisions
```

Use these directories as follows:

- `docs/agents/`: Document the agents used in this project. Each file should describe when to use the agent, what it must read before working, what it may change, and what it must not change. Use names such as `backend-agent.md`, `database-agent.md`, or `review-agent.md`.
- `docs/verification/`: Store scenario-level verification specs. Each document should include scenarios, verification methods, pass rules, and actual counterexamples. Use this directory to define what "done" means for a scenario.
- `docs/standards/`: Store development conventions by topic. Each Markdown file should cover one pattern, such as JOOQ usage, datasource usage, configuration style, logging, testing, or Micronaut bean design.
- `docs/harness/`: Store reusable execution and verification support, such as local environment setup, sample configs, command recipes, test data notes, generator runbooks, or curl examples.
- `docs/decisions/`: Store architecture decision records. Use numbered ADR names such as `0001-use-jooq-for-database-access.md`. Each ADR should include status, context, options considered, decision, rationale, and consequences.

AI agents should use this workflow:

1. Check `docs/agents/` to identify the role and boundaries for the task when an agent document exists.
2. Read relevant files in `docs/standards/` before implementing project-specific code.
3. Check `docs/decisions/` before changing architecture, data access patterns, module boundaries, or build conventions.
4. Use `docs/verification/` to choose the verification methods and pass rules for the change.
5. Add or update `docs/harness/` when a reusable local setup, script, sample, or debugging flow is discovered.
6. Add an ADR in `docs/decisions/` when the change makes or changes a significant technical decision.

## Commit & Pull Request Guidelines

This repository currently has no commit history, so use clear, imperative commit messages such as `Add flow controller` or `Fix Consul bootstrap config`. Keep each commit focused. Pull requests should include a short summary, testing performed, linked issue or task if available, and notes for configuration changes. Include screenshots only for user-visible UI changes.

## Security & Configuration Tips

Do not commit secrets, tokens, or environment-specific credentials. Prefer environment variables for deploy-time values such as `NODE_IP`. Treat `bootstrap.yaml` changes carefully because they affect configuration loading before the application starts.
