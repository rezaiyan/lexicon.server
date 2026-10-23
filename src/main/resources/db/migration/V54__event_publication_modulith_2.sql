-- Spring Modulith 2 event publication registry (spring-modulith-events-jdbc 2.x, schemas/v2/schema-postgresql.sql).
--   status                 : lifecycle of the publication (PUBLISHED, PROCESSING, COMPLETED, FAILED, RESUBMITTED)
--   completion_attempts    : how many times a listener tried to handle it
--   last_resubmission_date : when EventPublicationRetry last resubmitted it
-- Nullable as upstream: rows written by Modulith 1.4 have none of these, and incomplete publications are
-- still found by completion_date IS NULL, so pending ones from before the upgrade keep being retried.
ALTER TABLE event_publication ADD COLUMN status TEXT;
ALTER TABLE event_publication ADD COLUMN completion_attempts INT;
ALTER TABLE event_publication ADD COLUMN last_resubmission_date TIMESTAMP WITH TIME ZONE;
