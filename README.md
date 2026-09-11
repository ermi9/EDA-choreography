# EDA Choreography Engine

A choreography layer for event-driven microservices: messages carry their own execution
trace, parallel branches are joined with a set-based state machine, and failures are
undone by reverse-topological compensation — including the case where one parallel branch
fails after its siblings succeeded.

It sits on top of the discovery platform (`microservice-discovery-k8s`), which is treated
as a **black box**: the only dependency is the Kafka `service-events` stream, consumed by
JSON schema into a local DTO. Nothing here imports discovery code.

## Build

```bash
mvn test     # unit tests, no Docker
mvn verify   # + container-backed *IT tests (Testcontainers, needs Docker)
```

Java 17, Spring Boot 4.0.5, Testcontainers 1.21.4 — the same toolchain as the discovery
platform, so both ends of the wire contract are built alike.

## Layout

```
com.eda.choreography
├── domain/          pure Java — no Kafka, Redis, or Spring (enforced by ArchitectureTest)
│   ├── trace/         execution trace as a DAG                       (INC-1)
│   ├── compensation/  reverse-topological compensation walk          (INC-1)
│   └── join/          set-based join behind JoinStateStore           (INC-2)
└── infra/           adapters that call into the domain
    ├── kafka/         consume → domain → publish loop                (INC-3)
    └── redis/         durable JoinStateStore + join deadlines        (INC-4)
```

The domain/infra split is the load-bearing design decision: it is what lets the
contribution (INC-1, INC-2) be proven with plain unit tests before any broker exists, and
what lets INC-4 swap the state store without touching join logic.
`src/test/.../architecture/ArchitectureTest` fails the build if the domain reaches into
infra or a framework package.
