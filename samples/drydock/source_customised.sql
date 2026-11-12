-- MySQL dump 10.13  Distrib 5.7.44, for Linux (x86_64)
--
-- Host: localhost    Database: bank_live
-- ------------------------------------------------------
-- SAMPLE for Dry Dock: an older release that a bank has customised. All names and data are invented.
-- Customisations made here: wider first_name, a branch table and code, a risk rating, a segment,
-- a renamed audit table, a dormant-account procedure, an audit trigger.

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET NAMES utf8 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `bank_live` /*!40100 DEFAULT CHARACTER SET utf8 */;
USE `bank_live`;

--
-- Table structure for table `branch_master`
--

DROP TABLE IF EXISTS `branch_master`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `branch_master` (
  `branch_code` varchar(10) NOT NULL,
  `branch_name` varchar(100) NOT NULL,
  `region` varchar(50) DEFAULT NULL,
  PRIMARY KEY (`branch_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='Bank customisation: branches';
/*!40101 SET character_set_client = @saved_cs_client */;

LOCK TABLES `branch_master` WRITE;
/*!40000 ALTER TABLE `branch_master` DISABLE KEYS */;
INSERT INTO `branch_master` VALUES ('B001','Harbour Road; main','North'),('B002','Dock Street ''East''','South');
/*!40000 ALTER TABLE `branch_master` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `customer`
--

DROP TABLE IF EXISTS `customer`;
CREATE TABLE `customer` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `customer_id` varchar(50) NOT NULL,
  `first_name` varchar(100) NOT NULL COMMENT 'widened by the bank',
  `last_name` varchar(50) NOT NULL,
  `email` varchar(100) DEFAULT NULL,
  `phone` varchar(20) DEFAULT NULL,
  `status_id` varchar(50) NOT NULL,
  `branch_code` varchar(10) DEFAULT NULL,
  `risk_rating` char(1) NOT NULL DEFAULT 'L',
  `segment` varchar(20) NOT NULL,
  `created_ts` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_id` (`customer_id`),
  KEY `idx_customer_branch` (`branch_code`),
  CONSTRAINT `fk_customer_branch` FOREIGN KEY (`branch_code`) REFERENCES `branch_master` (`branch_code`)
) ENGINE=InnoDB AUTO_INCREMENT=1042 DEFAULT CHARSET=utf8;

LOCK TABLES `customer` WRITE;
INSERT INTO `customer` VALUES (1,'C-0001','Anna','Lee','anna@example.test',NULL,'ACTIVE','B001','L','RETAIL','2024-01-02 10:00:00');
UNLOCK TABLES;

--
-- Table structure for table `account`
--

DROP TABLE IF EXISTS `account`;
CREATE TABLE `account` (
  `account_id` varchar(20) NOT NULL,
  `customer_ref` int(11) NOT NULL,
  `currency` char(3) NOT NULL DEFAULT 'USD',
  `balance` decimal(18,2) NOT NULL DEFAULT '0.00',
  `overdraft_limit` decimal(20,2) DEFAULT NULL,
  `status` enum('OPEN','CLOSED') NOT NULL DEFAULT 'OPEN',
  `remarks` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`account_id`),
  KEY `idx_acc_customer` (`customer_ref`),
  CONSTRAINT `fk_acc_cust` FOREIGN KEY (`customer_ref`) REFERENCES `customer` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

--
-- Table structure for table `cust_audit_log`
--

DROP TABLE IF EXISTS `cust_audit_log`;
CREATE TABLE `cust_audit_log` (
  `log_id` bigint(20) NOT NULL AUTO_INCREMENT,
  `customer_ref` int(11) NOT NULL,
  `action` varchar(30) NOT NULL,
  `changed_by` varchar(50) DEFAULT NULL,
  `changed_ts` datetime NOT NULL,
  PRIMARY KEY (`log_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

--
-- Temporary table structure for view `v_active_customer`
--

DROP TABLE IF EXISTS `v_active_customer`;
/*!50001 DROP VIEW IF EXISTS `v_active_customer`*/;
/*!50001 CREATE TABLE `v_active_customer` (
  `id` tinyint NOT NULL,
  `customer_id` tinyint NOT NULL
) ENGINE=MyISAM */;

--
-- Dumping routines for database 'bank_live'
--
/*!50003 DROP PROCEDURE IF EXISTS `sp_close_dormant` */;
DELIMITER ;;
/*!50003 CREATE*/ /*!50020 DEFINER=`app_user`@`%`*/ /*!50003 PROCEDURE `sp_close_dormant`(IN p_days INT)
BEGIN
  UPDATE account a SET a.status = 'CLOSED'
   WHERE a.balance = 0 AND a.remarks LIKE '%dormant;%';
  SELECT ROW_COUNT() AS closed_accounts;
END */;;
DELIMITER ;

/*!50003 SET @saved_sql_mode = @@sql_mode */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=`app_user`@`%`*/ /*!50003 TRIGGER `trg_customer_audit` AFTER UPDATE ON `customer` FOR EACH ROW BEGIN
  INSERT INTO cust_audit_log (customer_ref, action, changed_by, changed_ts) VALUES (NEW.id, 'UPDATE', USER(), NOW());
END */;;
DELIMITER ;

--
-- Final view structure for view `v_active_customer`
--

/*!50001 DROP TABLE IF EXISTS `v_active_customer`*/;
/*!50001 DROP VIEW IF EXISTS `v_active_customer`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`app_user`@`%` SQL SECURITY DEFINER */
/*!50001 VIEW `v_active_customer` AS select `customer`.`id` AS `id`,`customer`.`customer_id` AS `customer_id`,`customer`.`branch_code` AS `branch_code` from `customer` where (`customer`.`status_id` = 'ACTIVE') */;

/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
-- Dump completed
