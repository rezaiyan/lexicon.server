-- Runs before Hibernate creates the schema in the `h2` dev profile (see application-h2.yml).
-- Only PostgreSQL compatibility shims live here; tables come from the JPA entities.

-- Entities declare columnDefinition = "jsonb"
CREATE DOMAIN IF NOT EXISTS JSONB AS JSON;
