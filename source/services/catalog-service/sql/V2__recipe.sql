CREATE TABLE variant_recipe (
  variant_id uuid NOT NULL REFERENCES variant(id),
  ingredient_id uuid NOT NULL,
  quantity bigint NOT NULL CHECK (quantity > 0),
  version bigint NOT NULL CHECK (version > 0),
  active boolean NOT NULL DEFAULT true,
  actor_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (variant_id, ingredient_id, version)
);

CREATE UNIQUE INDEX one_active_recipe_ingredient
  ON variant_recipe(variant_id, ingredient_id)
  WHERE active;
