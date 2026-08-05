-- V11 populated deadlines for legacy active rows, so the expand constraint can now be validated.
ALTER TABLE media_kyb_retention
    VALIDATE CONSTRAINT chk_media_kyb_retention_deadline;
