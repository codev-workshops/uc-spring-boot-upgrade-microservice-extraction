-- Comments are now owned by comments-service. The table is renamed rather than dropped so
-- existing rows survive until comments-service has imported them (see README).
ALTER TABLE comments RENAME TO comments_legacy;
