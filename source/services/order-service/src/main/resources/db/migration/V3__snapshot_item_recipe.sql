ALTER TABLE order_item
  ADD COLUMN recipe_version bigint NOT NULL DEFAULT 0 CHECK (recipe_version >= 0);

CREATE TABLE order_item_recipe (
  order_item_id uuid NOT NULL REFERENCES order_item(id),
  ingredient_id uuid NOT NULL,
  quantity bigint NOT NULL CHECK (quantity > 0),
  PRIMARY KEY (order_item_id, ingredient_id)
);
