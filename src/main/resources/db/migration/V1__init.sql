-- V1: 初始 schema。由 Hibernate 在 MySQL 8 上生成后固化为版本化迁移。
-- 仅在 MySQL(prod)profile 启用;dev/test 使用 H2 并由 Hibernate 自动建表。
-- 列类型与实体映射逐字一致,以通过 spring.jpa.hibernate.ddl-auto=validate。

CREATE TABLE `app_user` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `phone` varchar(32) NOT NULL,
  `display_name` varchar(64) DEFAULT NULL,
  `password_hash` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_user_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `assistant_session` (
  `duration_minutes` int NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `version` bigint NOT NULL,
  `city_key` varchar(32) NOT NULL,
  `companions` varchar(80) DEFAULT NULL,
  `interests` varchar(200) DEFAULT NULL,
  `id` varchar(255) NOT NULL,
  `history_json` tinytext NOT NULL,
  `last_result_json` longtext,
  `route_json` tinytext NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_assistant_owner` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `assistant_trace` (
  `created_at` datetime(6) DEFAULT NULL,
  `elapsed_ms` bigint NOT NULL,
  `user_id` bigint DEFAULT NULL,
  `failure_reason` varchar(256) DEFAULT NULL,
  `id` varchar(255) NOT NULL,
  `model` varchar(255) DEFAULT NULL,
  `prompt_version` varchar(255) DEFAULT NULL,
  `session_id` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `steps_json` longtext,
  PRIMARY KEY (`id`),
  KEY `idx_trace_owner` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `city` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `city_key` varchar(32) NOT NULL,
  `name` varchar(64) NOT NULL,
  `summary` varchar(512) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_city_key` (`city_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `device_session` (
  `active` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_active_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  `device_name` varchar(64) DEFAULT NULL,
  `session_id` varchar(64) NOT NULL,
  `refresh_token_hash` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_session_sid` (`session_id`),
  KEY `idx_session_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `favorite` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `target_type` varchar(16) NOT NULL,
  `target_id` varchar(64) DEFAULT NULL,
  `title` varchar(128) NOT NULL,
  `subtitle` varchar(256) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_favorite_target` (`user_id`,`target_type`,`target_id`),
  KEY `idx_favorite_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `guide_job` (
  `attempts` int NOT NULL DEFAULT '0',
  `generation` int NOT NULL DEFAULT '0',
  `created_at` datetime(6) DEFAULT NULL,
  `lease_until` datetime(6) DEFAULT NULL,
  `team_id` bigint DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `execution_token` varchar(36) DEFAULT NULL,
  `request_hash` varchar(64) DEFAULT NULL,
  `error` varchar(500) DEFAULT NULL,
  `question` varchar(2000) DEFAULT NULL,
  `route_context` varchar(4000) DEFAULT NULL,
  `id` varchar(255) NOT NULL,
  `spot_id` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `style` varchar(255) DEFAULT NULL,
  `content` text,
  PRIMARY KEY (`id`),
  KEY `idx_job_owner` (`user_id`),
  KEY `idx_job_recovery` (`status`,`lease_until`),
  KEY `idx_job_delivery` (`status`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `guide_outbox` (
  `generation` int NOT NULL,
  `publish_attempts` int NOT NULL,
  `available_at` datetime(6) DEFAULT NULL,
  `lease_until` datetime(6) DEFAULT NULL,
  `status` varchar(16) NOT NULL,
  `job_id` varchar(36) NOT NULL,
  `lease_token` varchar(36) DEFAULT NULL,
  `id` varchar(80) NOT NULL,
  `last_error` varchar(200) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_outbox_due` (`status`,`available_at`),
  KEY `fk_outbox_job` (`job_id`),
  CONSTRAINT `fk_outbox_job` FOREIGN KEY (`job_id`) REFERENCES `guide_job` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `realtime_budget` (
  `reservations` int NOT NULL,
  `id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `route` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `intensity` varchar(16) DEFAULT NULL,
  `style` varchar(16) DEFAULT NULL,
  `city_key` varchar(32) NOT NULL,
  `distance_text` varchar(32) DEFAULT NULL,
  `estimated_duration` varchar(32) DEFAULT NULL,
  `route_key` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `tags` varchar(256) DEFAULT NULL,
  `risks` varchar(512) DEFAULT NULL,
  `summary` varchar(512) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_route_key` (`route_key`),
  KEY `idx_route_city` (`city_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `route_point` (
  `latitude` double DEFAULT NULL,
  `longitude` double DEFAULT NULL,
  `order_index` int NOT NULL,
  `stay_minutes` int DEFAULT NULL,
  `trigger_radius` int DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL,
  `route_key` varchar(64) NOT NULL,
  `spot_id` varchar(64) DEFAULT NULL,
  `heading_text` varchar(128) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_point_route` (`route_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `spot` (
  `latitude` double DEFAULT NULL,
  `longitude` double DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `category` varchar(32) DEFAULT NULL,
  `city_key` varchar(32) NOT NULL,
  `source_status` varchar(32) DEFAULT NULL,
  `name` varchar(64) NOT NULL,
  `open_time` varchar(64) DEFAULT NULL,
  `recommended_duration` varchar(64) DEFAULT NULL,
  `source_name` varchar(64) DEFAULT NULL,
  `highlight` varchar(256) DEFAULT NULL,
  `tags` varchar(256) DEFAULT NULL,
  `intro` varchar(512) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_spot_city` (`city_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `team` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `owner_id` bigint NOT NULL,
  `version` bigint DEFAULT NULL,
  `playback_status` varchar(16) DEFAULT NULL,
  `team_code` varchar(16) NOT NULL,
  `current_point_id` varchar(64) DEFAULT NULL,
  `route_id` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_team_code` (`team_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `team_member` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `joined_at` datetime(6) NOT NULL,
  `team_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `role` varchar(16) NOT NULL,
  `member_name` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_team_member_user` (`team_id`,`user_id`),
  KEY `idx_member_team` (`team_id`),
  KEY `idx_member_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
