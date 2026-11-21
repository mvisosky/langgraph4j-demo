-- Demo seed data (idempotent). Dates are relative to the first start of the database.

INSERT INTO customers (id, name, email, tier) VALUES
    ('C-1001', 'Alice Martin',  'alice.martin@example.com',  'GOLD'),
    ('C-1002', 'Bob Nguyen',    'bob.nguyen@example.com',    'STANDARD'),
    ('C-1003', 'Carol Dubois',  'carol.dubois@example.com',  'PLATINUM')
ON CONFLICT (id) DO NOTHING;

INSERT INTO orders (id, customer_id, status, total, placed_at, delivered_at, carrier, tracking_number) VALUES
    -- Alice: a recent delivered order (small refund -> auto-approved) and an in-transit order
    ('A-5001', 'C-1001', 'DELIVERED',  64.90, now() - interval '12 days', now() - interval '8 days', 'UPS',   '1Z999AA10123456784'),
    ('A-5002', 'C-1001', 'SHIPPED',   129.00, now() - interval '3 days',  NULL,                      'FedEx', '7489 2213 0098'),
    -- Bob: an expensive delivered order (refund > limit -> human approval) and an old one (outside window)
    ('A-5003', 'C-1002', 'DELIVERED', 349.99, now() - interval '9 days',  now() - interval '5 days', 'DHL',   'JD014600006281234567'),
    ('A-5004', 'C-1002', 'DELIVERED',  42.50, now() - interval '70 days', now() - interval '66 days','UPS',   '1Z999AA10198765432'),
    -- Carol: processing order and a delivered order containing a final-sale item
    ('A-5005', 'C-1003', 'PROCESSING', 89.00, now() - interval '1 day',   NULL,                      NULL,    NULL),
    ('A-5006', 'C-1003', 'DELIVERED', 210.00, now() - interval '6 days',  now() - interval '2 days', 'UPS',   '1Z999AA10155555555')
ON CONFLICT (id) DO NOTHING;

INSERT INTO order_items (order_id, sku, name, quantity, unit_price, final_sale) VALUES
    ('A-5001', 'TRL-SOCK-3',  'Merino trail socks (3-pack)',     1,  29.90, FALSE),
    ('A-5001', 'HDL-LAMP-01', 'Rechargeable headlamp',           1,  35.00, FALSE),
    ('A-5002', 'JKT-RAIN-M',  'Packable rain jacket (M)',        1, 129.00, FALSE),
    ('A-5003', 'TNT-2P-ULT',  'Ultralight 2-person tent',        1, 349.99, FALSE),
    ('A-5004', 'BTL-STEEL-1', 'Insulated steel bottle',          1,  42.50, FALSE),
    ('A-5005', 'PCK-DAY-25',  '25L day pack',                    1,  89.00, FALSE),
    ('A-5006', 'BOT-HIKE-42', 'Waterproof hiking boots (EU 42)', 1, 160.00, FALSE),
    ('A-5006', 'SAMPLE-GLV',  'Sample-sale gloves',              1,  50.00, TRUE)
ON CONFLICT (order_id, sku) DO NOTHING;
