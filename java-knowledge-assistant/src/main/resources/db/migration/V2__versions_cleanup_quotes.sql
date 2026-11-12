ALTER TABLE documents ADD COLUMN latest_version INT NOT NULL DEFAULT 1,
  ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE document_versions ADD COLUMN source_filename VARCHAR(160) NULL,
  ADD COLUMN collection_name VARCHAR(100) NULL,
  ADD COLUMN index_config TEXT NULL,
  ADD COLUMN cleanup_status VARCHAR(12) NOT NULL DEFAULT 'NONE',
  ADD COLUMN cleanup_failure VARCHAR(400) NULL,
  ADD COLUMN cleanup_attempt CHAR(36) NULL,
  ADD COLUMN cleanup_updated_at TIMESTAMP(6) NULL;
UPDATE document_versions v JOIN documents d ON d.id=v.document_id SET v.source_filename=d.filename;
ALTER TABLE chat_citations ADD COLUMN quote TEXT NULL;
CREATE INDEX ix_document_visibility ON documents(deleted,available,created_at,id);
