-- Spring Modulith event publication registry (spring-modulith-events-jdbc 1.4, schema-postgresql.sql).
-- A row per (event, listener) is written in the publishing transaction and removed when the listener
-- completes (completion-mode: delete); rows left behind are failed handlers awaiting a retry.
CREATE TABLE IF NOT EXISTS event_publication
(
  id               UUID NOT NULL,
  listener_id      TEXT NOT NULL,
  event_type       TEXT NOT NULL,
  serialized_event TEXT NOT NULL,
  publication_date TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date  TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);
