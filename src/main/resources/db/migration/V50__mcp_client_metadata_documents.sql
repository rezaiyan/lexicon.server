-- MCP clients may identify themselves by an https URL (OAuth Client ID Metadata Documents),
-- which can be longer than the 100 characters SAS's schema allows for client_id.
ALTER TABLE oauth2_registered_client ALTER COLUMN client_id TYPE varchar(1000);
