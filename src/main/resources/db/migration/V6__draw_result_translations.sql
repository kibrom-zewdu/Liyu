CREATE TABLE draw_result_translations (
    draw_result_id BIGINT NOT NULL,
    language_code CHAR(2) NOT NULL,
    notice TEXT NOT NULL,
    PRIMARY KEY (draw_result_id,language_code),
    CONSTRAINT fk_draw_result_translation_result FOREIGN KEY (draw_result_id) REFERENCES draw_results(id),
    CONSTRAINT chk_draw_result_translation_language CHECK (language_code IN ('en','am','ti'))
) ENGINE=InnoDB;
