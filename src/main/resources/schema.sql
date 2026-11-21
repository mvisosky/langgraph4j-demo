-- Transactional domain for the demo. Vector tables (kb_embeddings, customer_memory) are created by
-- LangChain4j's PgVectorEmbeddingStore and the checkpoint tables by LangGraph4j's PostgresSaverV2.

CREATE TABLE IF NOT EXISTS customers (
    id          VARCHAR(16) PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    email       VARCHAR(200) NOT NULL,
    tier        VARCHAR(16)  NOT NULL DEFAULT 'STANDARD',   -- STANDARD | GOLD | PLATINUM
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS orders (
    id              VARCHAR(16) PRIMARY KEY,
    customer_id     VARCHAR(16)   NOT NULL REFERENCES customers(id),
    status          VARCHAR(16)   NOT NULL,                  -- PROCESSING | SHIPPED | DELIVERED | CANCELLED
    total           NUMERIC(10,2) NOT NULL,
    placed_at       TIMESTAMPTZ   NOT NULL,
    delivered_at    TIMESTAMPTZ,
    carrier         VARCHAR(32),
    tracking_number VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS order_items (
    order_id    VARCHAR(16)   NOT NULL REFERENCES orders(id),
    sku         VARCHAR(32)   NOT NULL,
    name        VARCHAR(200)  NOT NULL,
    quantity    INT           NOT NULL,
    unit_price  NUMERIC(10,2) NOT NULL,
    final_sale  BOOLEAN       NOT NULL DEFAULT FALSE,
    PRIMARY KEY (order_id, sku)
);

CREATE TABLE IF NOT EXISTS refunds (
    id          BIGSERIAL PRIMARY KEY,
    order_id    VARCHAR(16)   NOT NULL REFERENCES orders(id),
    customer_id VARCHAR(16)   NOT NULL REFERENCES customers(id),
    amount      NUMERIC(10,2) NOT NULL,
    reason      TEXT          NOT NULL,
    status      VARCHAR(16)   NOT NULL,                      -- ISSUED | REJECTED
    decided_by  VARCHAR(120)  NOT NULL,                      -- 'auto-policy' or the human approver
    thread_id   VARCHAR(64),
    -- one refund per (thread, turn, order): replaying or retrying the graph can never pay twice
    idempotency_key VARCHAR(200) UNIQUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS tickets (
    id          BIGSERIAL PRIMARY KEY,
    customer_id VARCHAR(16)  NOT NULL REFERENCES customers(id),
    thread_id   VARCHAR(64),
    priority    VARCHAR(8)   NOT NULL,                       -- LOW | MEDIUM | HIGH | URGENT
    category    VARCHAR(32)  NOT NULL,
    summary     TEXT         NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Tracks which knowledge-base documents (and which version of them) are already embedded
CREATE TABLE IF NOT EXISTS kb_ingestion (
    source      VARCHAR(200) PRIMARY KEY,
    sha256      VARCHAR(64)  NOT NULL,
    segments    INT          NOT NULL,
    ingested_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Conversation threads (thread id = LangGraph4j checkpoint thread id) and the customer that owns each one
CREATE TABLE IF NOT EXISTS conversations (
    thread_id   VARCHAR(64) PRIMARY KEY,
    customer_id VARCHAR(16) NOT NULL REFERENCES customers(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Approval queue for human-in-the-loop: runs paused before the human_approval node
CREATE TABLE IF NOT EXISTS pending_approvals (
    thread_id    VARCHAR(64) PRIMARY KEY REFERENCES conversations(thread_id),
    customer_id  VARCHAR(16)   NOT NULL,
    order_id     VARCHAR(16),
    amount       NUMERIC(10,2),
    reason       TEXT,
    policy_note  TEXT,
    requested_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);
