-- Tables de SHFastTournament avec SHNetwork. Le plugin les crée seul au démarrage si l'utilisateur a le droit CREATE ;
-- ce fichier sert à les créer à la main et à ne donner à l'utilisateur que les droits de lecture et d'écriture.
--
-- Utilisation (base bo_net pour les essais multi-Opale) :
--   mysql -u root < sql/schema.sql   puis remplacer CHANGE_ME par le mot de passe de config.yml

CREATE DATABASE IF NOT EXISTS bo_net;

-- Un tournoi lancé. is_open vaut 1 tant qu'il est en cours et porte un index unique : la base refuse un deuxième
-- tournoi ouvert, quel que soit le serveur qui le lance. NULL une fois la conclusion réservée ou le tournoi arrêté.
-- status : RUNNING, ENDING (conclusion réservée par le meneur), DONE, STOPPED (arrêté par un administrateur).
CREATE TABLE IF NOT EXISTS bo_net.fasttournament_runs (
    run_id      CHAR(12)     NOT NULL,                  -- identifiant du tournoi lancé
    tournament  VARCHAR(64)  NOT NULL,                  -- id du tournoi dans config.yml
    started_at  BIGINT       NOT NULL,                  -- millisecondes depuis 1970
    ends_at     BIGINT       NOT NULL,                  -- heure de fin, la même pour tous les serveurs
    status      VARCHAR(8)   NOT NULL,
    is_open     TINYINT      NULL DEFAULT NULL,
    PRIMARY KEY (run_id),
    CONSTRAINT uq_fasttournament_open UNIQUE (is_open)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Points de chaque joueur, augmentés par « score = score + ? » : les serveurs s'additionnent.
CREATE TABLE IF NOT EXISTS bo_net.fasttournament_scores (
    run_id  CHAR(12)     NOT NULL,
    uuid    CHAR(36)     NOT NULL,                      -- uuid « hors ligne » du joueur, le même sur tous les serveurs
    name    VARCHAR(32)  NOT NULL DEFAULT '',
    score   DOUBLE       NOT NULL DEFAULT 0,
    PRIMARY KEY (run_id, uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Classement final, écrit une seule fois. tier : top1, top2, top3, participation ou none.
-- paid passe de 0 à 1 par « UPDATE ... WHERE paid = 0 » : le seul serveur qui touche la ligne envoie la récompense.
CREATE TABLE IF NOT EXISTS bo_net.fasttournament_results (
    run_id  CHAR(12)     NOT NULL,
    uuid    CHAR(36)     NOT NULL,
    name    VARCHAR(32)  NOT NULL DEFAULT '',
    place   INT          NOT NULL,
    score   DOUBLE       NOT NULL,
    tier    VARCHAR(16)  NOT NULL,
    paid    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (run_id, uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Victoires (première place), augmentées par « wins = wins + 1 » en même temps que l'écriture du classement.
CREATE TABLE IF NOT EXISTS bo_net.fasttournament_wins (
    uuid  CHAR(36)     NOT NULL,
    name  VARCHAR(32)  NOT NULL DEFAULT '',             -- dernier pseudo du gagnant
    wins  INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE USER IF NOT EXISTS 'shfasttournament'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME';
-- DELETE sert au ménage des tournois terminés (reseau.conservation-jours)
GRANT SELECT, INSERT, UPDATE, DELETE ON bo_net.fasttournament_runs TO 'shfasttournament'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON bo_net.fasttournament_scores TO 'shfasttournament'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON bo_net.fasttournament_results TO 'shfasttournament'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE ON bo_net.fasttournament_wins TO 'shfasttournament'@'127.0.0.1';
