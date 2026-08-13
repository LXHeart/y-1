-- JBE-07: the Node homepage cache is no longer read after intelligence V17.
-- Cache rows are disposable; the Java-owned intelligence_cached_hot_topics table remains.

DROP TABLE IF EXISTS cached_hot_topics;
