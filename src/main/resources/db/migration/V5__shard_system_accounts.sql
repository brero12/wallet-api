-- 15 extra sinks and faucets; the originals (…0002 and …0001) are shard 0
INSERT INTO accounts (id, player_id, currency, type, allow_negative)
SELECT ('00000000-0000-0000-0001-' || lpad(to_hex(i), 12, '0'))::uuid,
    'SYSTEM-' || i, 'GOLD', 'SYSTEM_SINK', false
FROM generate_series(1, 15) AS i;

INSERT INTO accounts (id, player_id, currency, type, allow_negative)
SELECT ('00000000-0000-0000-0002-' || lpad(to_hex(i), 12, '0'))::uuid,
    'SYSTEM-' || i, 'GOLD', 'SYSTEM_FAUCET', true
FROM generate_series(1, 15) AS i;