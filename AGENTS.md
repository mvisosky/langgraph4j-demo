# AGENTS.md

Guidance for AI coding agents working in this repo. See [README.md](README.md) for the full feature tour, API table and
configuration reference; this file covers what you need to change code safely.

## What this is

Acme Support Agent: a customer-support service for a fictional outdoor-gear retailer, demonstrating agentic AI patterns.

- Java 25, Spring Boot 4.1, Maven (use the wrapper: `./mvnw`, or `mvnw.cmd` on Windows)
- LangGraph4j 1.9.x (graph orchestration), LangChain4j 1.23 (model access, AiServices)
- Claude Sonnet 5.5 (`smart-model`) and Haiku 5.5 (`fast-model`); Ollama profile for offline use
- Postgres + pgvector (data, vector store, checkpoints), OpenTelemetry → Jaeger/Prometheus/Loki/Grafana

Base package: `com.acme.support`.

## Commands

```bash
docker compose up -d                  # Postgres/pgvector + observability stack (required to run the app)
export ANTHROPIC_API_KEY=sk-ant-...   # PowerShell: $env:ANTHROPIC_API_KEY = "..."
./mvnw spring-boot:run                # app on :8080 (Studio at /?instance=support)
./mvnw test                           # unit tests
SPRING_PROFILES_ACTIVE=ollama ./mvnw spring-boot:run   # offline models
```

- Manual scenarios live in [demo.http](demo.http); use them to verify behavioral changes end to end.
- Tests must not need an API key or a running database. Existing tests: `RefundPolicyServiceTest`, `GuardrailsTest`,
  `SupportGraphWiringTest`. Run `./mvnw test` after any change to graph wiring, policy or guardrails.
- Running the app calls the real model and costs money; don't start it unless the task needs it.

## Layout

```
src/main/java/com/acme/support
├── ai/             LangChain4j AiServices interfaces (typed prompts, structured outputs)
├── api/            REST controller + SupportAgentService (run / stream / resume / inspect)
├── config/         AgentProperties, AiModelConfig (model routing, caching), StoreConfig, Studio
├── domain/         Domain records, SupportRepository, RefundPolicyService (policy-as-code)
├── graph/          SupportState, SupportGraphFactory, KnowledgeRagSubgraph, Nodes helpers
│   └── nodes/      One class per graph node
├── knowledge/      Idempotent RAG ingestion of src/main/resources/knowledge/*.md
├── memory/         LongTermMemoryService (semantic memory)
├── observability/  GraphTracingHook, LlmTelemetryListener, AgentMetrics
└── tools/          OrderTools (LLM-callable tools)
src/main/resources: application.yml, application-ollama.yml, schema.sql, data.sql, knowledge/
docker/             Collector, Prometheus, Grafana provisioning and dashboard
```

## Architecture rules (important)

- **Graph is the control flow.** Routing lives in `SupportGraphFactory` (nodes, edges, conditional edges). When you
  add or rename a node, update the factory, `SupportState` keys if needed, the Mermaid diagram in the README, and
  `SupportGraphWiringTest`.
- **One class per node** in `graph/nodes/`. Nodes read/write `SupportState` via its schema; use the existing channel
  types and reducers rather than mutating state ad hoc. State must stay serializable: it is checkpointed to Postgres
  after every node.
- **LLM proposes, code decides.** Money movement is governed by `RefundPolicyService` (auto-approve limit, return
  window, final-sale rules). Never let an LLM output directly authorize a refund. Keep `agent.refund.*` rules in code.
- **Idempotent side effects.** Refunds go through `SupportRepository.recordRefund`, which must stay safe under
  retries and replays.
- **Tool authorization.** `OrderTools` are bound to the authenticated customer. Never add a tool parameter that lets
  the model choose a customer id or otherwise widen access.
- **Human-in-the-loop.** `human_approval` is an `interruptBefore` point; runs are persisted and resumed through the
  API, possibly after a restart. Don't introduce non-persistent state that a resume would lose.
- **Model tiers.** Use the smart model for reasoning/generation/critique and the fast model for classification,
  routing, grading and extraction. Don't hard-code model names; use `AiModelConfig` / `AgentProperties`.
- **Guardrails fail safely.** Input guard: deterministic rules first, LLM classifier second (fails open by design).
  Output guard: PII redaction and prompt-leak blocking. Don't weaken these without being asked.
- **Resilience.** LLM-calling nodes use `RetryPolicy` hooks and degrade gracefully (e.g. `IntentRouter` keyword
  fallback). New LLM calls need a fallback path; failures shouldn't crash a conversation.
- **Config is typed.** Add new settings to `AgentProperties` and `application.yml` (and the README table), not
  scattered `@Value` strings.
- **Observability.** Node spans come from `GraphTracingHook`; LLM spans from `LlmTelemetryListener`. Executors are
  wrapped for OTel context propagation: any new thread pool or async branch must be wrapped too, or spans detach
  from the trace. Don't log prompt content or PII outside the gated `capture-content` path.

## Dependency notes

- Versions are managed in `pom.xml` via the `langchain4j-bom` and `langgraph4j-bom`. Bump properties, not individual
  artifacts.
- LangGraph4j's Jackson serializers need Jackson 2 (`com.fasterxml`) alongside Spring Boot 4's Jackson 3; see the
  comment in `pom.xml` before touching Jackson dependencies.
- Embeddings run in-process (all-MiniLM-L6-v2); no embedding API is needed.

## Conventions

- Keep code style consistent with neighbouring files (records for DTOs/structured outputs, constructor injection,
  no Lombok).
- Prompts live next to their AiService interface in `ai/`; keep them concise and keep structured outputs typed
  (records/enums), not free-form text parsing.
- Seed data (`data.sql`) and schema (`schema.sql`) run on every start and must stay idempotent.
- Knowledge base content is Markdown in `src/main/resources/knowledge/`; ingestion is idempotent, so edits are picked
  up on next start.
- Never commit secrets. `ANTHROPIC_API_KEY` comes from the environment.
- Commit only when asked.
