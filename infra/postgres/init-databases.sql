-- One database per service: services never read each other's tables,
-- they talk over REST (sync) or Kafka (async).
CREATE DATABASE policy_db;
CREATE DATABASE claim_db;
CREATE DATABASE payment_db;
