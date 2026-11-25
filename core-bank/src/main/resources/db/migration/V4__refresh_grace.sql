-- Token emitido no lugar deste. Permite repetir uma renovação cuja resposta se perdeu no caminho.
ALTER TABLE core.refresh_token ADD COLUMN successor_id uuid REFERENCES core.refresh_token (id);
