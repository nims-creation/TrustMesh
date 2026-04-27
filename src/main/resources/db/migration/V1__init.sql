CREATE TABLE accounts (
    vpa VARCHAR(255) PRIMARY KEY,
    holder_name VARCHAR(255) NOT NULL,
    balance NUMERIC(19, 2) NOT NULL,
    version BIGINT
);

CREATE TABLE transactions (
    id BIGSERIAL PRIMARY KEY,
    packet_hash VARCHAR(64) NOT NULL UNIQUE,
    sender_vpa VARCHAR(255) NOT NULL,
    receiver_vpa VARCHAR(255) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    signed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    settled_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    bridge_node_id VARCHAR(255) NOT NULL,
    hop_count INT NOT NULL,
    status VARCHAR(50) NOT NULL
);

CREATE INDEX idx_packet_hash ON transactions(packet_hash);
