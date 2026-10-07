-- V2: 任意地点导览。真实定位附近的 OpenStreetMap 地点可导入为 spot,
-- external_id 记录 OSM 元素 ID(唯一,防重复导入),extra_facts 保存带来源前缀的补充资料,
-- source_url 记录地点资料来源页面。均可为空,不影响已有种子数据。
ALTER TABLE `spot`
  ADD COLUMN `external_id` varchar(64) DEFAULT NULL,
  ADD COLUMN `extra_facts` varchar(4000) DEFAULT NULL,
  ADD COLUMN `source_url` varchar(512) DEFAULT NULL,
  ADD UNIQUE KEY `uk_spot_external_id` (`external_id`);
