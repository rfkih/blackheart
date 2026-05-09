-- TrainingJob entity uses Double for training_accuracy / validation_accuracy.
-- Hibernate maps Double → DOUBLE PRECISION (FLOAT(53)), but the baseline
-- created them as NUMERIC(24,12). Convert.
ALTER TABLE training_jobs ALTER COLUMN training_accuracy   TYPE DOUBLE PRECISION USING training_accuracy::double precision;
ALTER TABLE training_jobs ALTER COLUMN validation_accuracy TYPE DOUBLE PRECISION USING validation_accuracy::double precision;
