CREATE TABLE users (
  username VARCHAR(40) PRIMARY KEY,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(10) NOT NULL
);
CREATE TABLE documents (
  id CHAR(36) PRIMARY KEY,
  filename VARCHAR(160) NOT NULL UNIQUE,
  active_version INT NULL,
  available BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE document_versions (
  document_id CHAR(36) NOT NULL,
  version INT NOT NULL,
  source_hash CHAR(64) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  status VARCHAR(20) NOT NULL,
  stage VARCHAR(30) NOT NULL,
  failure VARCHAR(400) NULL,
  profile VARCHAR(64) NOT NULL,
  attempt CHAR(36) NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(document_id,version),
  FOREIGN KEY(document_id) REFERENCES documents(id)
);
CREATE TABLE chunks (
  id CHAR(36) PRIMARY KEY,
  document_id CHAR(36) NOT NULL,
  version INT NOT NULL,
  ordinal INT NOT NULL,
  section VARCHAR(400) NOT NULL,
  content TEXT NOT NULL,
  UNIQUE(document_id,version,ordinal),
  FOREIGN KEY(document_id,version) REFERENCES document_versions(document_id,version)
);
CREATE TABLE chats (
  id CHAR(36) PRIMARY KEY,
  username VARCHAR(40) NOT NULL,
  question TEXT NOT NULL,
  answer TEXT NOT NULL,
  status VARCHAR(30) NOT NULL,
  mode VARCHAR(10) NOT NULL,
  model VARCHAR(160) NOT NULL,
  embedding VARCHAR(160) NOT NULL,
  profile VARCHAR(64) NOT NULL,
  top_k INT NOT NULL,
  min_score DOUBLE NOT NULL,
  latency_ms BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY(username) REFERENCES users(username),
  INDEX ix_chat_owner(username,created_at)
);
CREATE TABLE chat_citations (
  chat_id CHAR(36) NOT NULL,
  chunk_id CHAR(36) NOT NULL,
  ordinal INT NOT NULL,
  PRIMARY KEY(chat_id,chunk_id),
  FOREIGN KEY(chat_id) REFERENCES chats(id),
  FOREIGN KEY(chunk_id) REFERENCES chunks(id)
);
