ALTER TABLE stock_ledger DROP CONSTRAINT stock_ledger_kind_check;
ALTER TABLE stock_ledger ADD CONSTRAINT stock_ledger_kind_check CHECK (kind IN ('IN','OUT','REVERSAL'));
